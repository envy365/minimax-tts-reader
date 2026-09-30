package com.minimax.ttsreader.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.minimax.ttsreader.api.AndroidSystemTtsClient
import com.minimax.ttsreader.api.MiniMaxTtsClient
import com.minimax.ttsreader.cache.AudioCache
import com.minimax.ttsreader.model.MiniMaxTtsResponse
import com.minimax.ttsreader.model.VoiceConfig
import com.minimax.ttsreader.model.VoiceRegistry
import com.minimax.ttsreader.util.ConfigManager
import com.minimax.ttsreader.util.DialogueClassifier
import com.minimax.ttsreader.util.LlmLogger
import com.minimax.ttsreader.util.RateLimiter
import com.minimax.ttsreader.util.SegmentSplitter
import com.minimax.ttsreader.util.TextPreprocessor
import com.minimax.ttsreader.util.WavConcatenator
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

        /**
         * v0.7.2：导给 Legado 的 HttpTTS 规则用稳定 id，避免一键导入时重复创建记录。
         *
         * 冲突风险评估 —— 固定 id 在用户手机多个 TTS App（不同 packageName）共存时可能撞，
         * 但本项目应用 ID 是 com.minimax.ttsreader；用一个非常大的、与真人时间戳不相撞的 magic number 即可。
         * 即便撞了，Legado 会按 URL + contentType 判真而不是 id，user-visible 影响 = 0。
         */
        private const val LEGADO_RULE_STABLE_ID = 918273645L  // v0.7.2 起固定，不再变

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
                // v0.7.2：固定 id（之前用 System.currentTimeMillis() 导致每次一键导入都新建 HttpTTS）
                // 旧 id 残留会导致用户误删时级联失效 SpeakerGroup 全部 entries
                // 固定 id 后重导入是更新同一条记录，speaker 引用保持有效
                "id" to LEGADO_RULE_STABLE_ID,
                "jsLib" to "",
                "lastUpdateTime" to System.currentTimeMillis(),
                "loginCheckJs" to "",
                "loginUi" to "",
                "loginUrl" to "",
                "name" to "MiniMax-TTS",
                // v0.8.1（Stage 12）：URL 模板去掉 currentToneID / currentEmotionTag / currentSpeakerName 占位
                // 让 Legado 把我们当单角色 TTS，不再触发 Rimchars Legado 的多角色切分路径
                // （原设计让 Legado 看到 speakersJson 误认为支持多角色发言人 → Reading Archive
                // 进入多角色路径 → 给每段打 fallback speaker='精英青年' → 叙述+对话合并切分）
                "url" to "http://localhost:$port/api/reader/tts" +
                    "?text={{java.encodeURI(speakText)}}"
                // v0.8.1：移除 speakersJson / emotionsJson 字段（不输出）。
                // VoiceRegistry.buildSpeakersJsonForLegado / buildEmotionsJsonForLegado 函数保留以备未来恢复。
            )
            // 关键修复：返回数组 [{...}] 而非单对象
            return Gson().toJson(listOf(rule))
        }
    }

    private val ttsClient: MiniMaxTtsClient
    private val androidSystemClient: AndroidSystemTtsClient
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
            normalizeModeProvider = { ConfigManager.getNormalizeMode(context) },
            drcConfigProvider = { ConfigManager.getDrcConfig(context) },
            cacheKeyPrefix = "[minimax]"
        )
        // v0.8.0：本地 TTS 引擎（Sherpa-onnx Piper 或 Google TTS，取决于 Android 系统设置）
        // Sherpa 装好后 Android 系统会把它注册为可选 TTS 引擎，用户在系统设置里选为默认
        // preferredEngineName = null 让 Android 用系统当前默认引擎（包括 Sherpa-onnx）
        androidSystemClient = AndroidSystemTtsClient(
            context = context.applicationContext,
            audioCache = audioCache,
            normalizeModeProvider = { ConfigManager.getNormalizeMode(context) },
            drcConfigProvider = { ConfigManager.getDrcConfig(context) },
            cacheKeyPrefix = "[system]",
            preferredEngineName = null
        )
        Log.i(TAG, "TtsServer init — rate=${rpm}RPM cache=${if (cacheEnabled) "enabled" else "disabled"} max=${cacheMaxEntries} ttl=${cacheTtlDays}d normalize=${ConfigManager.getNormalizeMode(context)} drc=${ConfigManager.getDrcConfig(context)} dualEngine=ready")
    }

    /**
     * 服务关闭时释放 AndroidSystemTtsClient 资源（由 Service.onDestroy 调）
     */
    fun shutdownSystemTts() {
        androidSystemClient.shutdown()
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
     *
     * v0.8.2（Stage 14）双引擎 + 子段切分：
     * 1. 用 SegmentSplitter 把 text 切成 N 个子段（引号/冒号/心理活动边界）
     * 2. 每个子段按 DialogueClassifier 分流：
     *    - DIALOGUE → MiniMaxTtsClient（情绪化声音）
     *    - NARRATION → AndroidSystemTtsClient（零 token 成本）
     * 3. 用 WavConcatenator 把所有子段 wav 拼回一个连续 wav
     *
     * 跳过 LLM 文本预处理（v0.7.5 起用户偏好：浪费 token）
     * 跳过整段 cache（v0.8.2）：子段 cache 已由各客户端内部处理，整段 cache 收益小、占用大
     */
    private fun handleReaderTtsRequest(requestId: Long, session: IHTTPSession): Response {
        val config = configProvider()

        val params = parseQueryParams(session)
        val text = params["text"] ?: params["speakText"] ?: params["tex"] ?: ""
        if (text.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少 text 参数")
        }

        // v0.7.x：per-request 覆盖字段（多角色朗读支持）
        val overrideVoice = params["voice"]?.takeIf { it.isNotBlank() && !it.startsWith("{{") }
        val overrideEmotion = params["emotion"]?.takeIf { it.isNotBlank() && !it.startsWith("{{") }
        // v0.8.1（Stage 11）：不再把 voice/speaker 喂给 DialogueClassifier。
        val speakerName: String? = null  // 保留变量名以兼容旧 log，不参与判定
        val toneID: String? = null  // 同上

        // v0.8.0：双引擎开关（用户在 App 内配置；改后需重启 Service 生效）
        val dualEnabled = ConfigManager.getDualEngineMode(context)

        val effectiveConfig = config.copy(
            voice = overrideVoice ?: config.voice,
            emotion = overrideEmotion ?: config.emotion
        )

        Log.i(TAG, "[#$requestId] Reader TTS: text='${text.take(50)}...', dual=$dualEnabled, voice=${effectiveConfig.voice}, speaker=$speakerName, emotion=${effectiveConfig.emotion}")

        return try {
            val wav = runBlocking {
                if (dualEnabled) {
                    // v0.8.2（Stage 14）：双引擎路由 + 子段切分
                    val subs = SegmentSplitter.split(text)
                    Log.d(TAG, "[#$requestId] Reader TTS split → ${subs.size} subs (rules=${subs.joinToString(",") { it.rule }})")

                    if (subs.size == 1 && subs[0].rule == "no-split") {
                        // 单子段且无切分：走原 DialogueClassifier 路径（保留 v0.8.0 行为）
                        val sub = subs[0]
                        synthesizeSubSegment(sub, config, effectiveConfig)
                    } else {
                        // 多子段：分别合成 → 拼 wav
                        val wavs = subs.map { synthesizeSubSegment(it, config, effectiveConfig) }
                        try {
                            WavConcatenator.concat(wavs)
                        } catch (e: Exception) {
                            // 参数不一致（采样率/声道/位深）→ 降级：只用第一个子段 wav
                            Log.w(TAG, "[#$requestId] WAV concat 失败（参数不一致？），降级用第一个子段: ${e.message}")
                            wavs.first()
                        }
                    }
                } else {
                    // 单引擎模式（v0.7.4 旧行为）：全部走 MiniMax
                    if (config.apiKey.isBlank() || config.groupId.isBlank()) {
                        throw IllegalStateException("FORBIDDEN:请先配置 MiniMax API Key + GroupId")
                    }
                    ttsClient.synthesize(config.apiKey, config.groupId, text, effectiveConfig).audio
                }
            }
            Log.i(TAG, "[#$requestId] Reader TTS success: ${wav.size} bytes, text='${text.take(30)}'")
            newFixedLengthResponse(
                Response.Status.OK, "audio/wav",
                ByteArrayInputStream(wav), wav.size.toLong()
            )
        } catch (e: IllegalStateException) {
            // 特殊 FORBIDDEN 透传：单引擎模式未配置凭证时返回 403 而不是 500
            val msg = e.message ?: ""
            if (msg.startsWith("FORBIDDEN:")) {
                Log.w(TAG, "[#$requestId] Reader TTS 403: ${msg.removePrefix("FORBIDDEN:")}")
                newFixedLengthResponse(
                    Response.Status.FORBIDDEN, MIME_PLAINTEXT,
                    msg.removePrefix("FORBIDDEN:")
                )
            } else {
                Log.e(TAG, "[#$requestId] Reader TTS failed (dual=$dualEnabled)", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "TTS Error: $msg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[#$requestId] Reader TTS failed (dual=$dualEnabled)", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "TTS Error: ${e.message}")
        }
    }

    /**
     * 合成单个子段 wav（双引擎路由实现细节）
     *
     * @param sub SegmentSplitter.SubSegment，含 text + type + rule
     * @return wav 字节
     * @throws IllegalStateException 台词段但未配置 MiniMax 凭证
     */
    private suspend fun synthesizeSubSegment(
        sub: SegmentSplitter.SubSegment,
        config: VoiceConfig,
        effectiveConfig: VoiceConfig
    ): ByteArray {
        return when (sub.type) {
            DialogueClassifier.SegmentType.DIALOGUE -> {
                // 台词 → MiniMax TTS（需要凭证）
                if (config.apiKey.isBlank() || config.groupId.isBlank()) {
                    throw IllegalStateException(
                        "台词段需要 MiniMax API Key + GroupId（旁白段不需要）" +
                            " | sub.text='${sub.text.take(30)}' rule=${sub.rule}"
                    )
                }
                ttsClient.synthesize(config.apiKey, config.groupId, sub.text, effectiveConfig).audio
            }
            DialogueClassifier.SegmentType.NARRATION -> {
                // 旁白 → 本地系统 TTS（无需凭证，零 token 成本）
                androidSystemClient.synthesize(
                    text = sub.text,
                    speed = effectiveConfig.speed.toDouble(),
                    pitchAndroidPitch = androidSystemClient.miniMaxPitchToAndroidPitch(effectiveConfig.pitch),
                    locale = Locale.SIMPLIFIED_CHINESE
                )
            }
        }
    }

    /**
     * 通用 TTS 端点（GET/POST）：兼容 legado POST 模式与 App 内测试
     *
     * v0.8.2（Stage 14）：与 handleReaderTtsRequest 同样的 split + 双引擎路由 + WavConcatenator 逻辑
     */
    private fun handleTtsRequest(requestId: Long, session: IHTTPSession): Response {
        val config = configProvider()

        val params = parseAllParams(session)
        val text = params["tex"] ?: params["text"] ?: params["speakText"] ?: ""
        if (text.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少 text 参数")
        }

        // v0.8.0：双引擎开关
        val dualEnabled = ConfigManager.getDualEngineMode(context)

        // 统一用 App 内 speed 配置，legado 传的 speed 不再覆盖
        Log.i(TAG, "[#$requestId] TTS: text='${text.take(50)}...', dual=$dualEnabled, speed=${config.speed}, voice=${config.voice}, llm=${config.llmEnabled}")

        return try {
            val wav = runBlocking {
                if (dualEnabled) {
                    // v0.8.2（Stage 14）：双引擎路由 + 子段切分
                    val subs = SegmentSplitter.split(text)
                    Log.d(TAG, "[#$requestId] TTS split → ${subs.size} subs (rules=${subs.joinToString(",") { it.rule }})")

                    if (subs.size == 1 && subs[0].rule == "no-split") {
                        val sub = subs[0]
                        synthesizeSubSegment(sub, config, config)
                    } else {
                        val wavs = subs.map { synthesizeSubSegment(it, config, config) }
                        try {
                            WavConcatenator.concat(wavs)
                        } catch (e: Exception) {
                            Log.w(TAG, "[#$requestId] WAV concat 失败（参数不一致？），降级用第一个子段: ${e.message}")
                            wavs.first()
                        }
                    }
                } else {
                    // 单引擎模式（v0.7.4 旧行为）：全部走 MiniMax
                    if (config.apiKey.isBlank() || config.groupId.isBlank()) {
                        throw IllegalStateException("FORBIDDEN:请先配置 MiniMax API Key + GroupId")
                    }
                    ttsClient.synthesize(config.apiKey, config.groupId, text, config).audio
                }
            }
            Log.i(TAG, "[#$requestId] TTS success: ${wav.size} bytes, text='${text.take(30)}'")
            newFixedLengthResponse(
                Response.Status.OK, "audio/wav",
                ByteArrayInputStream(wav), wav.size.toLong()
            )
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            if (msg.startsWith("FORBIDDEN:")) {
                Log.w(TAG, "[#$requestId] TTS 403: ${msg.removePrefix("FORBIDDEN:")}")
                newFixedLengthResponse(
                    Response.Status.FORBIDDEN, MIME_PLAINTEXT,
                    msg.removePrefix("FORBIDDEN:")
                )
            } else {
                Log.e(TAG, "[#$requestId] TTS synthesis failed (dual=$dualEnabled)", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "TTS Error: $msg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[#$requestId] TTS synthesis failed (dual=$dualEnabled)", e)
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
