package com.minimax.ttsreader.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.minimax.ttsreader.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * LLM 预处理全流程日志（v0.4.0）。
 *
 * 存储：filesDir/llm_logs/YYYY-MM-DD.jsonl，每行一条 JSON。
 * 保留策略：只保留最近 7 天（含当天），每次写入后清理该目录下更早的文件。
 * 线程安全：写入 synchronized；所有异常内部吞掉，绝不影响 LLM 主流程。
 * 敏感字段：日志内容不含 apiKey/groupId 等。
 *
 * Context 获取：通过 App.appContext 静态引用（App.onCreate 必然先于 Service/Activity 执行）；
 * 若 App 未初始化则静默丢弃日志，绝不 crash。
 */
object LlmLogger {

    private const val TAG = "LlmLogger"
    private const val LOG_DIR_NAME = "llm_logs"

    /** 保留天数（含当天） */
    private const val RETAIN_DAYS = 7

    private val gson = Gson()
    private val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /**
     * 日志条目（字段契约为前端解析依据，勿随意改名）。
     * [time] 由 [log] 内部自动生成（ISO8601 带时区），调用方传空字符串即可。
     */
    data class LogEntry(
        val time: String = "",
        val requestId: Long,
        val model: String,
        val promptType: String,   // custom | default_28 | default_basic
        val textLen: Int,
        val textPreview: String,  // 原文前 60 字
        val output: String?,      // LLM 输出全文；skipped 时为 null
        val reasoning: String?,   // 思考内容（可为空/摘要）
        val durationMs: Long,
        val skipped: Boolean,     // llm 关闭/文本过短/无 key 等未调用 LLM 的分支
        val fallback: Boolean,    // LLM 调用失败回退原文
        val error: String?        // 错误原因；skipped 时形如 "skipped: llm disabled"
    )

    private fun context(): Context? = try {
        App.appContext
    } catch (_: Exception) {
        null
    }

    /** 写一条日志。任何失败都静默吞掉，不影响调用方主流程。 */
    fun log(entry: LogEntry) {
        try {
            val ctx = context() ?: return  // App 未初始化，静默丢弃
            synchronized(this) {
                val dir = File(ctx.filesDir, LOG_DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                val stamped = entry.copy(time = timeSdf.format(Date()))
                val file = File(dir, dateSdf.format(Date()) + ".jsonl")
                file.appendText(gson.toJson(stamped) + "\n")
                cleanup(dir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "log failed", e)
        }
    }

    /** 删除 7 天前的日志文件（含当天共保留 7 天） */
    private fun cleanup(dir: File) {
        try {
            val cutoff = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -(RETAIN_DAYS - 1))
            }
            val cutoffStr = dateSdf.format(cutoff.time)
            dir.listFiles()?.forEach { f ->
                val name = f.name.removeSuffix(".jsonl")
                // 文件名非法日期或早于保留窗口则删除；yyyy-MM-dd 字符串可直接字典序比较
                if (!isValidDate(name) || name < cutoffStr) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** 全部日志日期，按日期倒序（最新在前） */
    fun availableDates(): List<String> {
        val ctx = context() ?: return emptyList()
        val dir = File(ctx.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .map { it.name.removeSuffix(".jsonl") }
            .filter { isValidDate(it) }
            .sortedDescending()
    }

    /** 读取指定日期的日志条目，按时间倒序返回（ISO8601 字符串可直接字典序比较） */
    fun readLogs(date: String): List<JsonObject> {
        if (!isValidDate(date)) return emptyList()
        val ctx = context() ?: return emptyList()
        val file = File(File(ctx.filesDir, LOG_DIR_NAME), "$date.jsonl")
        if (!file.exists()) return emptyList()
        val entries = mutableListOf<JsonObject>()
        try {
            file.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    runCatching { entries.add(JsonParser.parseString(line).asJsonObject) }
                }
            }
        } catch (_: Exception) {
        }
        return entries.sortedByDescending {
            it.get("time")?.takeIf { j -> j.isJsonPrimitive }?.asString ?: ""
        }
    }

    private fun isValidDate(s: String): Boolean = s.matches(DATE_REGEX)

    private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
}
