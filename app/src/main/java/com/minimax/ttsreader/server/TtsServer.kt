package com.minimax.ttsreader.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.minimax.ttsreader.api.MiniMaxTtsClient
import com.minimax.ttsreader.cache.AudioCache
import com.minimax.ttsreader.model.MiniMaxTtsResponse
import com.minimax.ttsreader.model.VoiceConfig
import com.minimax.ttsreader.model.VoiceRegistry
import com.minimax.ttsreader.util.ConfigManager
import com.minimax.ttsreader.util.RateLimiter
import com.minimax.ttsreader.util.TextPreprocessor
import com.minimax.ttsreader.util.LlmLogger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TtsServer(
    private val context: Context,
    private val configProvider: () -> VoiceConfig,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "TtsServer"

        /** 请求 ID 计数器：日志关联多请求（决策 18） */
        private val requestIdCounter = AtomicLong(0)

        /**
         * 生成 legado 朗读引擎导入规则（数组格式），单点实现（决策：双份实现合并）
         * url 用 GET 模式，语速由 App 内 speed 配置控制
         */
        fun buildLegadoRule(port: Int): String {
            val rule = hashMapOf<String, Any>(
                "concurrentRate" to "5",
                "contentType" to "audio/wav",
                "enabledCookieJar" to false,
                "header" to "",
                "id" to System.currentTimeMillis(),
                "jsLib" to "",
                "lastUpdateTime" to System.currentTimeMillis(),
                "loginCheckJs" to "",
                "loginUi" to "",
                "loginUrl" to "",
                "name" to "MiniMax-TTS",
                // v0.7.x：URL 模板加 currentToneID / currentEmotionTag / currentSpeakerName 占位
                // 老 Legado（v3.25 及更早）没有这些变量 → 模板引擎对 undefined 用空字符串 → 服务端 .isNotBlank() 过滤 → 沿用全局 voice
                // Reading Archive 多角色模式启用时 → 占位替换为 per-segment voice_id → 实现多角色朗读
                "url" to "http://localhost:$port/api/reader/tts" +
                    "?text={{java.encodeURI(speakText)}}" +
                    "&voice={{currentToneID || ''}}" +
                    "&emotion={{currentEmotionTag || ''}}" +
                    "&speaker={{currentSpeakerName || ''}}",
                // v0.7.1：speaker 列表 —— Reading Archive 发言人管理 picker 的数据源
                // 不传这个字段 → picker 永远是空的（用户看到「除了分组名称只有 TTS 服务选项」就是这个原因）
                // 按 VoiceRegistry.PRESET_VOICES 的 category 分组生成 JSON（参考 httpTTSHelp.md 的格式）
                "speakersJson" to com.minimax.ttsreader.model.VoiceRegistry.buildSpeakersJsonForLegado()
            )
            // 关键修复：返回数组 [{...}] 而非单对象
            return Gson().toJson(listOf(rule))
        }
    }

    private val ttsClient: MiniMaxTtsClient
    private val rateLimiter: RateLimiter
    val audioCache: AudioCache
    private val gson = Gson()

    init {
        // 一次性从 ConfigManager 读 4 个新 pref，构建缓存/限速/客户端
        // 改动后用户需要重启 Service 生效（与现有 LLM 配置一致）
        val rpm = ConfigManager.getRateLimitRpm(context)
        val cacheEnabled = ConfigManager.getCacheEnabled(context)
        val cacheMaxEntries = ConfigManager.getCacheMaxEntries(context)
        val cacheTtlDays = ConfigManager.getCacheTtlDays(context)
        rateLimiter = RateLimiter(initialRpm = rpm)
        audioCache = AudioCache(
            context = context,
            maxEntries = cacheMaxEntries,
            ttlMillis = TimeUnit.DAYS.toMillis(cacheTtlDays),
            enabled = cacheEnabled
        )
        ttsClient = MiniMaxTtsClient(
            rateLimiter = rateLimiter,
            audioCache = audioCache,
            normalizeModeProvider = { ConfigManager.getNormalizeMode(context) }
        )
        Log.i(TAG, "TtsServer init — rate=${rpm}RPM cache=${if (cacheEnabled) "enabled" else "disabled"} max=${cacheMaxEntries} ttl=${cacheTtlDays}d normalize=${ConfigManager.getNormalizeMode(context)}")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val requestId = requestIdCounter.incrementAndGet()
        Log.d(TAG, "[#$requestId] Request: $method $uri")

        val response = try {
            when {
                uri == "/" && method == Method.GET -> serveIndex()
                uri == "/tts" -> handleTtsRequest(requestId, session)
                uri == "/api/reader/tts" -> handleReaderTtsRequest(requestId, session)
                uri == "/api/status" && method == Method.GET -> serveStatus()
                uri == "/api/cache/status" && method == Method.GET -> serveCacheStatus()
                // 安全审计 P0-2（2026-09-29）：删除 /api/config 端点（其返回完整 VoiceConfig 含 apiKey/groupId）。
                // 前端 WebView 走 JSBridge (Android.loadConfigByName) 拉配置，无需 HTTP endpoint。
                // 同设备其他 app / adb forward / 调试器调 127.0.0.1:9966/api/config 即可抽 apiKey，活靶子零门槛。
                uri == "/api/voices" && method == Method.GET -> serveVoices()
                uri == "/api/options" && method == Method.GET -> serveOptions()
                uri == "/api/llm-logs/dates" && method == Method.GET -> serveLlmLogDates()
                uri == "/api/llm-logs" && method == Method.GET -> serveLlmLogs(session)
                uri == "/api/legado/rule" && method == Method.GET -> serveLegadoRule()
                uri.startsWith("/web/") -> serveStaticFile(uri)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[#$requestId] Error handling request: $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Server Error: ${e.message}"
            )
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }

    /**
     * legado 朗读端点（GET）：非流式合成，返回 wav
     * 语速由 App 内 speed 配置控制，legado 不传 speed 参数
     */
    private fun handleReaderTtsRequest(requestId: Long, session: IHTTPSession): Response {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "API Key 未配置")
        }
        if (config.groupId.isBlank()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "GroupId 未配置（国内版必需）")
        }

        val params = parseQueryParams(session)
        val text = params["text"] ?: params["speakText"] ?: params["tex"] ?: ""
        if (text.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少 text 参数")
        }

        // v0.7.x：per-request 覆盖字段（多角色朗读支持）
        // Reading Archive 在多角色模式下会带 voice / emotion / speaker 三参；老 Legado 不带或带 "{{x }}" 字面字符串
        // 缺失或字面未替换 → 沿用 config 默认值
        val overrideVoice = params["voice"]?.takeIf { it.isNotBlank() && !it.startsWith("{{") }
        val overrideEmotion = params["emotion"]?.takeIf { it.isNotBlank() && !it.startsWith("{{") }
        val speakerName = params["speaker"]?.takeIf { it.isNotBlank() && !it.startsWith("{{") }

        val effectiveConfig = config.copy(
            voice = overrideVoice ?: config.voice,
            emotion = overrideEmotion ?: config.emotion
        )

        Log.i(TAG, "[#$requestId] Reader TTS: text='${text.take(50)}...', voice=${effectiveConfig.voice}, speaker=$speakerName, emotion=${effectiveConfig.emotion}, llm=${config.llmEnabled}")

        return try {
            val finalText = runBlocking { TextPreprocessor.preprocess(text, effectiveConfig) }
            val result = runBlocking {
                ttsClient.synthesize(config.apiKey, config.groupId, finalText, effectiveConfig)
            }
            // 决策 5：WAV 头观察日志，只观察不改数据
            logWavObservation(requestId, result.audio, result.extraInfo)
            Log.i(TAG, "[#$requestId] Reader TTS success: ${result.audio.size} bytes, text='${text.take(30)}'")
            newFixedLengthResponse(
                Response.Status.OK, "audio/wav",
                ByteArrayInputStream(result.audio), result.audio.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "[#$requestId] Reader TTS failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "TTS Error: ${e.message}")
        }
    }

    /**
     * 通用 TTS 端点（GET/POST）：兼容 legado POST 模式与 App 内测试
     */
    private fun handleTtsRequest(requestId: Long, session: IHTTPSession): Response {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "API Key 未配置")
        }
        if (config.groupId.isBlank()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "GroupId 未配置（国内版必需）")
        }

        val params = parseAllParams(session)
        val text = params["tex"] ?: params["text"] ?: params["speakText"] ?: ""
        if (text.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少 text 参数")
        }

        // 统一用 App 内 speed 配置，legado 传的 speed 不再覆盖
        Log.i(TAG, "[#$requestId] TTS: text='${text.take(50)}...', speed=${config.speed}, voice=${config.voice}, llm=${config.llmEnabled}")

        return try {
            val finalText = runBlocking { TextPreprocessor.preprocess(text, config) }
            val result = runBlocking {
                ttsClient.synthesize(config.apiKey, config.groupId, finalText, config)
            }
            Log.i(TAG, "[#$requestId] TTS success: ${result.audio.size} bytes, text='${text.take(30)}'")
            newFixedLengthResponse(
                Response.Status.OK, "audio/wav",
                ByteArrayInputStream(result.audio), result.audio.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "[#$requestId] TTS synthesis failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "TTS Error: ${e.message}")
        }
    }

    /**
     * WAV 头观察日志（决策 5）：解析返回 WAV 的 RIFF/data size/采样率/声道数，
     * 与 extra_info.audio_size 交叉验证头自洽性（飞掠 bug 取证）。只观察不改数据。
     */
    private fun logWavObservation(requestId: Long, wav: ByteArray, extraInfo: MiniMaxTtsResponse.ExtraInfo?) {
        val tag = "TTSWAV"
        if (wav.size < 44 || String(wav, 0, 4) != "RIFF") {
            Log.w(tag, "[#$requestId] 非标准 WAV 头（size=${wav.size}），跳过观察")
            return
        }
        val riffSize = ByteBuffer.wrap(wav, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val channels = ByteBuffer.wrap(wav, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
        val sampleRate = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var dataSize = -1
        var dataOffset = -1
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4)
            val chunkSize = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") {
                dataSize = chunkSize
                dataOffset = offset
                break
            }
            offset += 8 + chunkSize
        }
        // 实际数据量按 data chunk 位置计算（wav.size - dataOffset - 8），而非 wav.size - 44：
        // WAV 可能有 LIST 等其他 chunk，44 固定偏移假设会导致误报（决策 7.3-5A）
        val actualData = if (dataOffset >= 0) wav.size - dataOffset - 8 else -1
        val riffTotal = riffSize + 8
        val msg = StringBuilder()
            .append("[#$requestId] WAV 观察: riff=${riffSize}(${if (riffTotal == wav.size) "一致" else "不一致 total=${wav.size}"}) ")
            .append("data=$dataSize actual=$actualData${if (dataSize >= 0 && dataSize != actualData) " DATA不一致!" else ""} ")
            .append("rate=$sampleRate ch=$channels ")
            .append("extra=")
        if (extraInfo != null) {
            msg.append("audio_size=${extraInfo.audio_size} rate=${extraInfo.audio_sample_rate} ch=${extraInfo.audio_channel} fmt=${extraInfo.audio_format}")
            if (extraInfo.audio_size > 0 && extraInfo.audio_size != actualData.toLong()) {
                msg.append(" SIZE不一致!")
            }
        } else {
            msg.append("无")
        }
        Log.i(tag, msg.toString())
    }

    private fun serveStatus(): Response {
        val config = configProvider()
        val status = mapOf(
            "running" to true,
            "port" to config.serverPort,
            "voice" to config.voice,
            "model" to config.model,
            // v0.5.x：限速/缓存状态，运维排查用
            "rateLimitRpm" to rateLimiter.getRpm(),
            "cacheMemoryEntries" to audioCache.memorySize(),
            "cacheDiskBytes" to audioCache.diskSizeBytes()
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
    }

    /** 一次性返回完整 cache 配置 + 实时状态（前端面板用） */
    private fun serveCacheStatus(): Response {
        val status = mapOf(
            "rateLimitRpm" to rateLimiter.getRpm(),
            "cacheEnabled" to ConfigManager.getCacheEnabled(context),
            "cacheMaxEntries" to ConfigManager.getCacheMaxEntries(context),
            "cacheTtlDays" to ConfigManager.getCacheTtlDays(context),
            "cacheMemoryEntries" to audioCache.memorySize(),
            "cacheDiskBytes" to audioCache.diskSizeBytes(),
            // v0.6.x：响度归一化模式（前端下拉框 / 状态显示用）
            "normalizeMode" to ConfigManager.getNormalizeMode(context)
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
    }

    private fun serveVoices(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(VoiceRegistry.PRESET_VOICES))
    }

    /** 给前端拉取所有下拉选项（模型/情感/语言/音效/采样率等）*/
    private fun serveOptions(): Response {
        val options = mapOf(
            "models" to VoiceRegistry.MODELS,
            "emotions" to VoiceRegistry.EMOTIONS,
            "languageBoosts" to VoiceRegistry.LANGUAGE_BOOSTS,
            "soundEffects" to VoiceRegistry.SOUND_EFFECTS,
            "sampleRates" to VoiceRegistry.SAMPLE_RATES,
            "bitrates" to VoiceRegistry.BITRATES,
            "formats" to VoiceRegistry.FORMATS,
            "channels" to VoiceRegistry.CHANNELS
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(options))
    }

    /** LLM 预处理日志：可用日期列表（按日期倒序，最多 30 天）*/
    private fun serveLlmLogDates(): Response {
        val dates = LlmLogger.availableDates().take(30)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("dates" to dates)))
    }

    /** LLM 预处理日志：指定日期的条目（按时间倒序）；date 缺失或非法返回 400 */
    private fun serveLlmLogs(session: IHTTPSession): Response {
        val date = session.parms?.get("date") ?: ""
        if (!isValidLogDate(date)) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "date 参数缺失或非法（需 YYYY-MM-DD）"
            )
        }
        val logs = LlmLogger.readLogs(date)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("logs" to logs)))
    }

    /** 日期严格校验：格式 + 真实存在的日期（2026-13-99 之类拒绝） */
    private fun isValidLogDate(s: String): Boolean {
        if (!s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.isLenient = false
        return try {
            sdf.parse(s) != null
        } catch (_: Exception) {
            false
        }
    }

    /** 生成 legado 朗读引擎导入规则（数组格式），单点实现见 companion buildLegadoRule */
    private fun serveLegadoRule(): Response {
        val config = configProvider()
        return newFixedLengthResponse(Response.Status.OK, "application/json", buildLegadoRule(config.serverPort))
    }

    private fun serveIndex(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html", """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><title>MiniMax TTS Reader</title></head>
            <body><h1>MiniMax TTS Reader Service</h1><p>Service is running.</p>
            <p><a href="/web/index.html">打开设置界面</a></p></body></html>
        """.trimIndent())
    }

    private fun serveStaticFile(uri: String): Response {
        val assetPath = uri.removePrefix("/web/")
        if (assetPath.isBlank() || assetPath == "/") {
            return serveStaticAsset("web/index.html", "text/html")
        }
        val mimeType = when {
            assetPath.endsWith(".html") -> "text/html"
            assetPath.endsWith(".css") -> "text/css"
            assetPath.endsWith(".js") -> "application/javascript"
            assetPath.endsWith(".json") -> "application/json"
            assetPath.endsWith(".png") -> "image/png"
            assetPath.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return serveStaticAsset("web/$assetPath", mimeType)
    }

    private fun serveStaticAsset(assetPath: String, mimeType: String): Response {
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()
            newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            Log.w(TAG, "Static file not found: $assetPath")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: $assetPath")
        }
    }

    private fun parseQueryParams(session: IHTTPSession): Map<String, String> {
        val result = mutableMapOf<String, String>()
        session.parms?.forEach { (key, value) ->
            if (value != null) result[key] = value
        }
        return result
    }

    private fun parseAllParams(session: IHTTPSession): Map<String, String> {
        val result = mutableMapOf<String, String>()
        session.parms?.forEach { (key, value) ->
            if (value != null) result[key] = value
        }
        if (session.method == Method.POST) {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: ""
                if (body.isNotBlank()) parseUrlEncodedParams(body, result)
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun parseUrlEncodedParams(body: String, result: MutableMap<String, String>) {
        body.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                try {
                    result[URLDecoder.decode(parts[0], "UTF-8")] = URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                }
            }
        }
    }
}
