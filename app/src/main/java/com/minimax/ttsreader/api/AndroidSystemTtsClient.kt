package com.minimax.ttsreader.api

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.minimax.ttsreader.cache.AudioCache
import com.minimax.ttsreader.util.AudioNormalizer
import com.minimax.ttsreader.util.ConfigManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android 系统 TTS 客户端（v0.8.0 双引擎方案的本地引擎）
 *
 * 通过 Android TextToSpeech API 合成音频，写入 wav 文件并返回字节。
 * 主要用于旁白段落（占字符最多，零 token 成本）。
 *
 * 设计原则：
 * - 不依赖外部服务（与 MiniMaxTtsClient 完全独立）
 * - 同步等待合成完成（suspendCancellableCoroutine + UtteranceProgressListener）
 * - 失败抛异常，由 TtsServer 兜底链路降级
 * - 缓存和响度归一化模式与 MiniMaxTtsClient 一致（共享 AudioCache 实例）
 * - cacheKeyPrefix 用于双引擎方案下区分桶位（避免和 MiniMaxTtsClient 撞 cache）
 *
 * 使用约束：
 * - 必须在 Activity Context 或 Application Context 上构造（TextToSpeech 需要）
 * - 多次并发调用是安全的：TTS 引擎本身排队处理 utterance
 * - 应用退出前必须调 shutdown() 释放资源
 */
class AndroidSystemTtsClient(
    private val context: Context,
    private val audioCache: AudioCache? = null,
    private val normalizeModeProvider: () -> String = { AudioNormalizer.MODE_OFF },
    private val drcConfigProvider: () -> ConfigManager.DrcConfig = { ConfigManager.DrcConfig() },
    /**
     * 缓存 key 前缀（v0.8.0 双引擎方案）：避免不同引擎的合成结果撞同一 cache slot。
     * 默认 "[system]" —— 双引擎模式下用，单独使用系统 TTS 时也安全（不与 prefix 为空的历史 cache 撞）
     */
    private val cacheKeyPrefix: String = "[system]",
    /**
     * 指定 TTS 引擎包名（null = Android 系统默认）。
     * Sherpa-onnx Piper 装好后包名形如 "com.k2fsa.sherpaonnx.tts"。
     * 不指定时用 Android 系统设置里用户配置的默认引擎（可能是 Google TTS 或 Sherpa）。
     */
    private val preferredEngineName: String? = null
) {
    companion object {
        private const val TAG = "AndroidSystemTtsClient"
    }

    private var tts: TextToSpeech? = null
    private val initialized = AtomicBoolean(false)
    private val initDeferred = CompletableDeferred<Boolean>()
    // 用 Mutex（而不是 synchronized）是因为 initLock.withLock 内需要 await suspend points：
    // Kotlin 不允许在 synchronized 这种 critical section 内 suspend（可能跨线程挂起后死锁）。
    private val initLock = Mutex()

    /**
     * 初始化 TTS 引擎（首次合成前自动调用，重复调用安全）
     */
    private suspend fun ensureInitialized(): Boolean {
        if (initialized.get()) return true

        return initLock.withLock {
            if (initialized.get()) return@withLock true

            val initResult = CompletableDeferred<Int>()
            withContext(Dispatchers.Main) {
                if (tts != null) return@withContext
                tts = if (preferredEngineName.isNullOrBlank()) {
                    TextToSpeech(context) { status -> initResult.complete(status) }
                } else {
                    Log.i(TAG, "使用指定 TTS 引擎: $preferredEngineName")
                    TextToSpeech(
                        context,
                        { status -> initResult.complete(status) },
                        preferredEngineName
                    )
                }
            }
            val status = initResult.await()
            if (status == TextToSpeech.SUCCESS) {
                initialized.set(true)
                Log.i(TAG, "TTS 初始化成功")
                true
            } else {
                initialized.set(false)
                Log.w(TAG, "TTS 初始化失败: status=$status")
                false
            }
        }
    }

    /**
     * 同步合成：返回 wav 字节（含 normalize + cache 处理）
     *
     * @param text 待合成文本
     * @param speed 语速 0.5-2.0（传给 setSpeechRate）
     * @param pitchAndroidPitch 系统 TTS pitch（100 = 原速）
     * @param locale 目标语言（null = 当前 locale）
     * @param cacheKey cache key 业务部分（不含 prefix；prefix 自动加）
     */
    suspend fun synthesize(
        text: String,
        speed: Double = 1.0,
        pitchAndroidPitch: Int = 100,
        locale: Locale? = null,
        cacheKey: String = text.trim()
    ): ByteArray {
        if (text.isBlank()) {
            throw IllegalArgumentException("text 不能为空")
        }

        // L1: 查缓存
        if (audioCache != null) {
            val fullCacheKey = cacheKeyPrefix + cacheKey
            audioCache.get(fullCacheKey)?.let { cached ->
                Log.d(TAG, "[cache HIT] ${text.take(30)}... (${cached.size} bytes)")
                return cached
            }
        }

        if (!ensureInitialized()) {
            throw IllegalStateException("TTS 引擎初始化失败（status != SUCCESS）")
        }

        val engine = tts ?: throw IllegalStateException("TTS 实例为空")

        // 应用参数（在主线程操作 TTS 引擎）
        withContext(Dispatchers.Main) {
            if (locale != null) {
                engine.setLanguage(locale)
            }
            engine.setSpeechRate(speed.toFloat().coerceIn(0.1f, 10f))
            engine.setPitch((pitchAndroidPitch / 100f).coerceIn(0.5f, 2f))
        }

        // L2: 合成原始 wav
        // synthesizeToFile 必须在 main thread 调（Android TextToSpeech 文档约定）
        val rawWav = withContext(Dispatchers.Main) {
            val outFile = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
            val utteranceId = UUID.randomUUID().toString()

            suspendCancellableCoroutine<ByteArray> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "[TTS] start: ${text.take(30)}...")
                    }

                    override fun onDone(utteranceId: String?) {
                        // 同步读 wav 文件（典型几 KB~几十 KB，main 阻塞几 ms 可接受）
                        val bytes = try {
                            outFile.readBytes()
                        } catch (e: Exception) {
                            if (outFile.exists()) outFile.delete()
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        if (outFile.exists()) outFile.delete()
                        Log.d(TAG, "[TTS] done: ${text.take(30)}... (${bytes.size} bytes)")
                        if (cont.isActive) cont.resume(bytes)
                    }

                    @Suppress("DEPRECATION")
                    @Deprecated("Required by API but superseded by onError(utteranceId, errorCode)")
                    override fun onError(utteranceId: String?) {
                        if (outFile.exists()) outFile.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                RuntimeException("TTS 合成失败 (utteranceId=$utteranceId)")
                            )
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (outFile.exists()) outFile.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                RuntimeException("TTS 合成失败 (utteranceId=$utteranceId, code=$errorCode)")
                            )
                        }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (outFile.exists()) outFile.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                RuntimeException("TTS 合成被停止 (interrupted=$interrupted)")
                            )
                        }
                    }
                })

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val result = engine.synthesizeToFile(text, params, outFile, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    if (outFile.exists()) outFile.delete()
                    if (cont.isActive) {
                        cont.resumeWithException(RuntimeException("synthesizeToFile 返回 ERROR"))
                    }
                }
            }
        }

        // L3: 响度归一化（DRC / RMS）
        val mode = normalizeModeProvider()
        val drcConfig = drcConfigProvider()
        val processed = AudioNormalizer.process(rawWav, mode, drcConfig)
        Log.d(TAG, "[cache MISS + $mode] ${text.take(30)}... (raw=${rawWav.size} → out=${processed.size} bytes)")

        // L4: 落缓存
        if (audioCache != null) {
            val fullCacheKey = cacheKeyPrefix + cacheKey
            audioCache.put(fullCacheKey, processed)
        }

        return processed
    }

    /**
     * MiniMax pitch [-12, +12] -> 系统 TTS pitch（100=原速）
     * -12 -> 50 (半低音)，0 -> 100 (原速)，+12 -> 200 (倍高音)
     * 简单线性：pitchAndr = 100 + miniMaxPitch × 8
     */
    fun miniMaxPitchToAndroidPitch(miniMaxPitch: Int): Int {
        val clamped = miniMaxPitch.coerceIn(-12, 12)
        return (100 + clamped * 8).coerceIn(50, 200)
    }

    /**
     * MiniMax speed [0.5, 2.0] -> 系统 TTS speed（Android 直接接收 float）
     */
    fun miniMaxSpeedToAndroidSpeed(miniMaxSpeed: Double): Float {
        return miniMaxSpeed.coerceIn(0.5, 2.0).toFloat()
    }

    /**
     * 当前 TTS 引擎是否初始化成功
     */
    fun isReady(): Boolean = initialized.get()

    /**
     * 释放 TTS 资源（应用退出前必须调）
     */
    fun shutdown() {
        synchronized(initLock) {
            tts?.let {
                try {
                    it.stop()
                    it.shutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "shutdown 异常", e)
                }
            }
            tts = null
            initialized.set(false)
        }
    }
}