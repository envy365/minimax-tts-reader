package com.minimax.ttsreader.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * TTS 音频的 LRU + 磁盘二级缓存。
 *
 * 设计目标：
 * - 同段文字再读一次时，**0 次调用** MiniMax API
 * - 同时把 Legado 朗读相同段落（如翻回去重听）合并请求，间接压低 RPM
 *
 * Key 策略：
 * - 输入：text + 关键 voice 参数（model/voice/speed/vol/pitch/emotion/sampleRate/channel/textNormalization）
 * - 哈希：SHA-1 → 40 字节 hex → 文件名，避免路径过长
 *
 * 容量策略：
 * - 内存：LinkedHashMap 接入顺序，maxEntries=100 默认
 * - 磁盘：context.cacheDir/tts_audio/，受全局磁盘配额约束；TTL 默认 7 天
 *
 * 线程：
 * - 内存访问 synchronized 在 memLock；磁盘访问假定外部 caller 在 IO dispatcher
 */
class AudioCache(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = TimeUnit.DAYS.toMillis(DEFAULT_TTL_DAYS),
    private val enabled: Boolean = true
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val DEFAULT_TTL_DAYS = 7L
        private const val CACHE_SUBDIR = "tts_audio"
    }

    private val cacheDir: File = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }

    // accessOrder=true → 读也会把 entry 推到队尾，配合 removeEldestEntry 实现真 LRU
    private val memCache = object : LinkedHashMap<String, ByteArray>(
        (maxEntries + 1).coerceAtLeast(16),
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > maxEntries
        }
    }

    private val memLock = Any()

    /** 从 cache key 派生 SHA-1 hex 文件名，避免 path too long / 特殊字符 */
    private fun digestKey(key: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(digest: String): File = File(cacheDir, "$digest.wav")

    /**
     * 查缓存。先内存，miss 再磁盘（命中磁盘会回填内存）。
     *
     * @return 命中返回 ByteArray，未命中或 disabled / 已过期返回 null
     */
    fun get(key: String): ByteArray? {
        if (!enabled) return null
        val d = digestKey(key)

        // L1: memory
        synchronized(memLock) {
            memCache[d]?.let { return it }   // 内存里的是"新"的（写入时 LRU 已记录）
        }

        // L2: disk
        val f = fileFor(d)
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age >= ttlMillis) {
            f.delete()
            return null
        }
        val bytes = try {
            f.readBytes()
        } catch (_: Exception) {
            return null
        }
        // 回填内存
        synchronized(memLock) { memCache[d] = bytes }
        return bytes
    }

    /**
     * 写缓存：内存立刻可见；磁盘同步落盘。
     *
     * @param key 规范化前的语义 key（内部会 hash）
     * @param bytes 完整的 wav 字节
     */
    fun put(key: String, bytes: ByteArray) {
        if (!enabled) return
        val d = digestKey(key)
        synchronized(memLock) { memCache[d] = bytes }
        try {
            fileFor(d).writeBytes(bytes)
        } catch (_: Exception) {
            // 磁盘写失败不致命，内存还在
        }
    }

    /** 清空缓存（设置面板里"清理缓存"按钮调这里）。 */
    fun clear() {
        synchronized(memLock) { memCache.clear() }
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** 当前内存缓存 size（供调试/状态接口用） */
    fun memorySize(): Int = synchronized(memLock) { memCache.size }

    /** 当前磁盘缓存总字节数 */
    fun diskSizeBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
}
