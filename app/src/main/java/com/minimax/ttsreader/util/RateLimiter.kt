package com.minimax.ttsreader.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token-bucket 风格的速率限制器（协程安全，单进程内有效）。
 *
 * 用法：
 * ```
 * val rl = RateLimiter(rpm = 60)
 * suspend fun handle() {
 *     rl.acquire()           // 调用 MiniMax 前 ensure 不超 rpm
 *     // ...实际请求
 * }
 * ```
 *
 * - [setRpm] 可运行时调整（例如用户在 Web UI 改配置后立即生效）
 * - 非阻塞：排队靠 suspend 延迟，不占线程
 * - 锁粒度：Mutex withLock 包裹"读 lastAcquireAt + 计算延迟 + 推进"，CAS 单变量做不到这种原子"读-算-写"
 *
 * 边界：
 * - rpm < 1 → 当作 1 处理（防除零）
 * - 第一次 acquire() 立即放行
 */
class RateLimiter(initialRpm: Int) {

    @Volatile private var rpm: Int = initialRpm.coerceAtLeast(1)
    private val mutex = Mutex()
    @Volatile private var lastAcquireAtMs: Long = 0L

    fun setRpm(newRpm: Int) {
        rpm = newRpm.coerceAtLeast(1)
    }

    fun getRpm(): Int = rpm

    /** 距离下个可发放 token 还需要等多久（不会推进 lastAcquireAt）。 */
    private fun nextDelayMs(now: Long, intervalMs: Long): Long {
        val next = lastAcquireAtMs + intervalMs
        return (next - now).coerceAtLeast(0L)
    }

    /**
     * 取得一个 token。阻塞到下个允许发放的时间点。
     *
     * @return 实际等到的毫秒数（含 0 表示立即放行，便于上层打日志）
     */
    suspend fun acquire(): Long {
        // 先快照一个 rpm，避免持锁时其他线程改了值
        val intervalMs = 60_000L / rpm.coerceAtLeast(1)
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = nextDelayMs(now, intervalMs)
            if (wait > 0) delay(wait)
            lastAcquireAtMs = System.currentTimeMillis()
            wait
        }
    }
}
