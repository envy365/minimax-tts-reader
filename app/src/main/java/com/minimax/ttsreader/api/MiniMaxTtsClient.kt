package com.minimax.ttsreader.api

import android.util.Log
import com.google.gson.Gson
import com.minimax.ttsreader.cache.AudioCache
import com.minimax.ttsreader.model.*
import com.minimax.ttsreader.util.AudioUtils
import com.minimax.ttsreader.util.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MiniMax TTS 客户端（国内版 t2a_v2）
 * https://api.minimaxi.com/v1/t2a_v2?GroupId={GroupId}
 *
 * 恒非流式合成、恒输出 wav（决策 1B/2B：Legado 需完整 WAV，流式路径已删除）
 *
 * ## v0.5.x 限速保护层（在原 synthesize 之上叠的三件套）
 *
 * 1. **L1 缓存**：相同 text + 关键 voice 参数 → 命中即返回 wav，0 次 API 调用
 * 2. **L2 速率限流**：固定窗口 token bucket，caps 出站 RPM 到用户配置（默认 60）
 * 3. **L3 错误重试**：遇 MiniMax 1002 / 1033（限速 / 并发超限）→ 指数退避重试
 *
 * 设计原则：
 * - **缓存 key 包含影响音频输出的所有参数**（model/voice/speed/vol/pitch/emotion/sampleRate/channel/textNormalization）
 *   但不包含 bitrate（wav 固定 64kbps）、pronunciationDict/voiceModify（极冷门配置，且变化后用户期望重新合成）
 *   也不包含 apiKey（每个账号自己的缓存文件）
 * - **限速只对实际发往 MiniMax 的请求生效**，即缓存 miss 之后才 acquire
 * - **重试只看 1002 / 1033**；其他错误（鉴权、配额、参数）直接抛
 */
class MiniMaxTtsClient(
    private val rateLimiter: RateLimiter,
    private val audioCache: AudioCache,
    private val maxRetries: Int = 3
) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "MiniMaxTtsClient"
        private const val BASE_URL = "https://api.minimaxi.com/v1/t2a_v2"

        // MiniMax T2A v2 base_resp.status_code 中"可重试"的错误码：
        // - 1002: Rate limit reached (RPM/字符级限速)
        // - 1033: Too many concurrent requests
        private val RETRYABLE_STATUS_CODES = setOf(1002, 1033)
    }

    /**
     * 非流式合成：返回完整 wav 音频，附响应 extra_info
     *
     * 流程：缓存查 → 命中即返回；否则限速 → 重试 → 落缓存 → 返回
     */
    suspend fun synthesize(
        apiKey: String,
        groupId: String,
        text: String,
        config: VoiceConfig
    ): TtsResult = withContext(Dispatchers.IO) {
        val key = buildCacheKey(text, config)

        // L1: 查缓存
        audioCache.get(key)?.let { cached ->
            Log.d(TAG, "[cache HIT] ${text.take(40)}... (${cached.size} bytes)")
            return@withContext TtsResult(audio = cached, extraInfo = null, fromCache = true)
        }

        // L2: 限速 + 重试
        val waited = rateLimiter.acquire()
        if (waited > 0) Log.d(TAG, "[rate limit] waited ${waited}ms")

        val result = synthesizeWithRetry(apiKey, groupId, text, config)

        // L3: 落缓存
        audioCache.put(key, result.audio)
        Log.d(TAG, "[cache MISS → saved] ${text.take(40)}... (${result.audio.size} bytes)")
        result
    }

    /**
     * 带指数退避的重试：仅 RETRYABLE_STATUS_CODES 重试，其他立即抛
     */
    private suspend fun synthesizeWithRetry(
        apiKey: String,
        groupId: String,
        text: String,
        config: VoiceConfig
    ): TtsResult {
        var attempt = 0
        var delayMs = 1000L
        while (true) {
            try {
                return doSynthesize(apiKey, groupId, text, config)
            } catch (e: TtsRetryableException) {
                if (attempt >= maxRetries) {
                    Log.w(TAG, "[retry] exhausted after $maxRetries attempts: ${e.message}")
                    throw e
                }
                Log.w(TAG, "[retry ${attempt + 1}/$maxRetries] in ${delayMs}ms — ${e.message}")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(8000L)
                attempt++
            } catch (e: Exception) {
                // 不可重试的错误：鉴权、参数、配额等，直接抛
                throw e
            }
        }
    }

    /**
     * 单次同步合成：HTTP 调用 → 解析 → hex→bytes → TtsResult
     * 不再做内部重试，由外层 [synthesizeWithRetry] 统一调度。
     */
    private fun doSynthesize(
        apiKey: String,
        groupId: String,
        text: String,
        config: VoiceConfig
    ): TtsResult {
        val voiceId = VoiceRegistry.getVoiceId(config.voice)
        val request = buildRequest(text, voiceId, config)
        val json = gson.toJson(request)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = if (groupId.isNotBlank()) "$BASE_URL?GroupId=$groupId" else BASE_URL

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw TtsRetryableException("Empty response body")

            val ttsResponse = gson.fromJson(responseBody, MiniMaxTtsResponse::class.java)
            val status = ttsResponse.base_resp?.status_code ?: -1
            if (status != 0) {
                val msg = ttsResponse.base_resp?.status_msg ?: "未知"
                if (status in RETRYABLE_STATUS_CODES) {
                    throw TtsRetryableException("MiniMax 限速/并发错误 $status: $msg")
                } else {
                    // 鉴权失败、配额耗尽、参数无效等：立刻抛给上层，不要重试
                    throw RuntimeException("MiniMax 错误 $status: $msg")
                }
            }

            val audioHex = ttsResponse.data?.audio
                ?: throw TtsRetryableException("响应中无音频数据")
            return TtsResult(
                audio = AudioUtils.hexToBytes(audioHex),
                extraInfo = ttsResponse.extra_info,
                fromCache = false
            )
        }
    }

    /**
     * 构造 MiniMax 请求体：恒非流式、恒 wav 格式
     */
    private fun buildRequest(
        text: String,
        voiceId: String,
        config: VoiceConfig
    ): MiniMaxTtsRequest {
        val voiceSetting = VoiceSetting(
            voice_id = voiceId,
            speed = config.speed,
            vol = config.vol,
            pitch = config.pitch,
            emotion = config.emotion.ifBlank { null },
            text_normalization = if (config.textNormalization) true else null
        )

        val audioSetting = AudioSetting(
            sample_rate = config.sampleRate,
            bitrate = config.bitrate,
            format = "wav",
            channel = config.channel
        )

        val pronunciationDict = if (config.pronunciationDict.isNotBlank()) {
            PronunciationDict(
                tone = config.pronunciationDict.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )
        } else null

        val voiceModify = if (config.vmPitch != 0 || config.vmIntensity != 0 ||
            config.vmTimbre != 0 || config.soundEffects.isNotBlank()
        ) {
            VoiceModify(
                pitch = config.vmPitch,
                intensity = config.vmIntensity,
                timbre = config.vmTimbre,
                sound_effects = config.soundEffects.ifBlank { null }
            )
        } else null

        return MiniMaxTtsRequest(
            model = config.model,
            text = text,
            stream = false,
            voice_setting = voiceSetting,
            audio_setting = audioSetting,
            pronunciation_dict = pronunciationDict,
            language_boost = config.languageBoost,
            voice_modify = voiceModify,
            output_format = "hex"
        )
    }

    /**
     * 缓存 key：规范化空白后拼接关键参数 → SHA-1 在 AudioCache 里再做。
     * 拼接而不是 hash，便于调试（如果某个组合重复合成，看 raw key 即可定位）。
     */
    private fun buildCacheKey(text: String, config: VoiceConfig): String {
        return buildString {
            append(text.trim())
            append('|')
            append(config.model)
            append('|')
            append(VoiceRegistry.getVoiceId(config.voice))
            append('|')
            append(config.speed)
            append('|')
            append(config.vol)
            append('|')
            append(config.pitch)
            append('|')
            append(config.emotion)
            append('|')
            append(config.sampleRate)
            append('|')
            append(config.channel)
            append('|')
            append(config.textNormalization)
        }
    }
}

/**
 * 表明 MiniMax 返回了可重试的错误（限速、并发超限、空响应）。
 * 不影响业务异常的传播；仅供 [MiniMaxTtsClient.synthesizeWithRetry] 捕获。
 */
class TtsRetryableException(message: String) : RuntimeException(message)

/**
 * 合成结果：音频字节 + 响应附带的 extra_info + 是否来自缓存
 */
data class TtsResult(
    val audio: ByteArray,
    val extraInfo: MiniMaxTtsResponse.ExtraInfo? = null,
    val fromCache: Boolean = false
)
