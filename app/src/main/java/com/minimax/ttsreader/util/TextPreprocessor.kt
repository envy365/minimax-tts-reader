package com.minimax.ttsreader.util

import com.minimax.ttsreader.api.MiniMaxLlmClient
import com.minimax.ttsreader.model.VoiceConfig
import com.minimax.ttsreader.model.VoiceRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * 朗读文本预处理：在 T2A 合成前用 LLM 对原文做符号/语气净化。
 * - llmEnabled 关闭或文本过短/纯空白时直接返回原文，避免无谓调用。
 * - LLM 调用失败时回退原文，绝不阻断合成主流程（保证朗读可用性优先）。
 * - 全流程写入 LlmLogger（v0.4.0）：跳过/成功/失败各产出一条记录；
 *   日志自身异常静默吞掉，不影响主流程。
 */
object TextPreprocessor {

    private val llmClient = MiniMaxLlmClient()

    /** 日志关联用请求 ID（自增） */
    private val requestId = AtomicLong(0)

    /** 主入口：返回最终送入 TTS 的文本 */
    suspend fun preprocess(text: String, config: VoiceConfig): String {
        val id = requestId.incrementAndGet()
        val textLen = text.length
        val preview = text.take(60)
        val startMs = System.currentTimeMillis()
        val model = config.llmModel

        // ---- 跳过分支（未调用 LLM，各记录一条 skipped 日志） ----
        if (!config.llmEnabled) {
            logSkipped(id, config, textLen, preview, startMs, "llm disabled")
            return text
        }
        if (text.isBlank()) {
            logSkipped(id, config, textLen, preview, startMs, "text blank")
            return text
        }
        if (text.trim().length < 8) {
            logSkipped(id, config, textLen, preview, startMs, "text too short")
            return text
        }
        if (config.apiKey.isBlank() || model.isBlank()) {
            logSkipped(
                id, config, textLen, preview, startMs,
                if (config.apiKey.isBlank()) "no api key" else "no llm model"
            )
            return text
        }

        val prompt = VoiceRegistry.getPromptForModel(config.model, config.llmPrompt)
        // 思考模式仅 MiniMax-M3 生效；M2.x 全系不支持关闭思考，对非 M3 不传 thinking 字段
        val thinkingType = if (model.startsWith("MiniMax-M3", ignoreCase = true)) config.llmThinking else ""

        return try {
            val result = llmClient.complete(
                apiKey = config.apiKey,
                groupId = config.groupId,
                model = model,
                systemPrompt = prompt,
                userText = text,
                maxTokens = config.llmMaxTokens,
                temperature = config.llmTemperature,
                thinkingType = thinkingType
            )
            // 防御：若 LLM 返回仍空，用原文兜底
            val out = result.content.ifBlank { text }
            log(
                id, config, textLen, preview, startMs,
                output = out,
                reasoning = result.reasoning,
                skipped = false, fallback = false, error = null
            )
            out
        } catch (e: Exception) {
            // 预处理失败不阻断合成，恢复原文
            log(
                id, config, textLen, preview, startMs,
                output = null,
                reasoning = null,
                skipped = false, fallback = true, error = e.message ?: "unknown error"
            )
            text
        }
    }

    /**
     * promptType 判定：与 VoiceRegistry.getPromptForModel 逻辑一致，
     * 基于 TTS 模型（config.model）：自定义 prompt=custom；speech-2.8=default_28；否则 default_basic
     */
    private fun promptTypeOf(config: VoiceConfig): String = when {
        config.llmPrompt.isNotBlank() -> "custom"
        config.model.startsWith("speech-2.8") -> "default_28"
        else -> "default_basic"
    }

    private fun logSkipped(id: Long, config: VoiceConfig, textLen: Int, preview: String, startMs: Long, reason: String) {
        log(id, config, textLen, preview, startMs, null, null, skipped = true, fallback = false, error = "skipped: $reason")
    }

    /** 统一入口：日志异常静默吞掉（LlmLogger 内部已兜底，此处再包一层） */
    private fun log(
        id: Long, config: VoiceConfig, textLen: Int, preview: String, startMs: Long,
        output: String?, reasoning: String?, skipped: Boolean, fallback: Boolean, error: String?
    ) {
        try {
            LlmLogger.log(
                LlmLogger.LogEntry(
                    requestId = id,
                    model = config.llmModel,
                    promptType = promptTypeOf(config),
                    textLen = textLen,
                    textPreview = preview,
                    output = output,
                    reasoning = reasoning,
                    durationMs = System.currentTimeMillis() - startMs,
                    skipped = skipped,
                    fallback = fallback,
                    error = error
                )
            )
        } catch (_: Exception) {
            // 日志失败不影响主流程
        }
    }
}
