package com.minimax.ttsreader.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MiniMax 国内版 chat completion 客户端
 * 端点: https://api.minimaxi.com/v1/text/chatcompletion_v2
 * 认证: Header Authorization: Bearer {apiKey}
 * 复用与 T2A 同一组 API Key；GroupId 仍以 query 参数带上以兼容旧鉴权风格。
 */
class MiniMaxLlmClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "https://api.minimaxi.com/v1/text/chatcompletion_v2"
    }

    /**
     * LLM 单次调用的结果。
     * - [content]：最终回答全文（reasoning_split=true 保证 content 不含思考内容）
     * - [reasoning]：思考内容（仅支持思考且开启的模型可能返回，可为 null），供日志记录
     */
    data class LlmResult(
        val content: String,
        val reasoning: String? = null
    )

    /**
     * 单轮 chat completion，返回模型输出文本。
     * @param thinkingType 思考模式：""=不传 thinking（M2.x 全系不支持，不传保持现状）/
     *        "enabled"=显式开启 adaptive / "disabled"=关闭；仅 MiniMax-M3 生效，非 M3 传 "" 即可
     */
    suspend fun complete(
        apiKey: String,
        groupId: String,
        model: String,
        systemPrompt: String,
        userText: String,
        maxTokens: Int = 2048,
        temperature: Double = 0.1,
        thinkingType: String = ""
    ): LlmResult = withContext(Dispatchers.IO) {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userText)
        )
        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "max_completion_tokens" to maxTokens,
            "temperature" to temperature,
            "stream" to false,
            // 恒加：思考内容分离到 reasoning_content/reasoning_details，content 保证是最终回答。
            // 不传时思考内容可能混入 content（会被送进 TTS 朗读，必须防御）
            "reasoning_split" to true
        )
        // 思考模式仅 MiniMax-M3 支持；M2.x 传 disabled 无效，对非 M3 不传 thinking 字段
        if (model.startsWith("MiniMax-M3", ignoreCase = true) && thinkingType.isNotBlank()) {
            // 约定：thinkingType="enabled" 时请求体写 "adaptive"，"disabled" 时写 "disabled"
            val type = if (thinkingType == "enabled") "adaptive" else "disabled"
            payload["thinking"] = mapOf("type" to type)
        }
        val body = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = if (groupId.isNotBlank()) "$BASE_URL?GroupId=$groupId" else BASE_URL

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            val raw = response.body?.string()
                ?: throw RuntimeException("LLM 响应体为空")
            val json = gson.fromJson(raw, JsonObject::class.java)
            val baseResp = json.getAsJsonObject("base_resp")
            val status = baseResp?.get("status_code")?.asInt ?: 0
            if (status != 0) {
                val msg = baseResp?.get("status_msg")?.asString ?: "未知错误"
                throw RuntimeException("LLM 错误 $status: $msg")
            }
            val choices = json.getAsJsonArray("choices")
                ?: throw RuntimeException("LLM 响应无 choices")
            if (choices.size() == 0) throw RuntimeException("LLM 响应 choices 为空")
            val msg = choices[0].asJsonObject
                .getAsJsonObject("message")
            val content = msg?.get("content")?.asString?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw RuntimeException("LLM 响应内容为空")
            LlmResult(content = content, reasoning = extractReasoning(msg))
        }
    }

    /**
     * 提取思考内容：reasoning_split=true 时思考在 message.reasoning_content（字符串）
     * 或 message.reasoning_details（数组，元素含 text）中；任一存在则取，供日志记录。
     */
    private fun extractReasoning(message: JsonObject?): String? {
        if (message == null) return null
        message.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.let { v ->
            val s = v.asString.trim()
            if (s.isNotEmpty()) return s
        }
        message.get("reasoning_details")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            val sb = StringBuilder()
            for (el in arr) {
                when {
                    el.isJsonObject -> el.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }?.let { sb.append(it.asString) }
                    el.isJsonPrimitive -> sb.append(el.asString)
                }
            }
            if (sb.isNotBlank()) return sb.toString().trim()
        }
        return null
    }

    /** 探活：用一个极短输入做一次最小调用，验证鉴权与模型可用性 */
    suspend fun ping(
        apiKey: String,
        groupId: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
            "max_completion_tokens" to 4,
            "stream" to false
        )
        val body = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = if (groupId.isNotBlank()) "$BASE_URL?GroupId=$groupId" else BASE_URL
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            val raw = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${raw.take(200)}")
            }
            val json = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
                ?: throw RuntimeException("响应非 JSON: ${raw.take(200)}")
            val status = json.getAsJsonObject("base_resp")?.get("status_code")?.asInt ?: 0
            if (status != 0) {
                val msg = json.getAsJsonObject("base_resp")?.get("status_msg")?.asString ?: "未知"
                throw RuntimeException("LLM 错误 $status: $msg")
            }
            "LLM 连接正常"
        }
    }
}