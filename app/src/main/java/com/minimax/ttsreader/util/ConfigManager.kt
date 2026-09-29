package com.minimax.ttsreader.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.minimax.ttsreader.model.VoiceConfig

object ConfigManager {

    private const val PREFS_NAME = "minimax_tts_reader_prefs"
    private const val KEY_VOICE_CONFIGS = "voice_configs"
    private const val KEY_ACTIVE_CONFIG = "active_config_name"
    private const val KEY_LEGACY_CONFIG = "voice_config"  // 旧版单配置 key
    private const val KEY_GLOBAL_API_KEY = "global_api_key"
    private const val KEY_GLOBAL_GROUP_ID = "global_group_id"
    private const val KEY_LLM_ENABLED = "global_llm_enabled"
    private const val KEY_LLM_MODEL = "global_llm_model"
    private const val KEY_LLM_PROMPT = "global_llm_prompt"
    private const val KEY_LLM_MAX_TOKENS = "global_llm_max_tokens"
    private const val KEY_LLM_TEMPERATURE = "global_llm_temperature"
    private const val KEY_LLM_THINKING = "global_llm_thinking"

    // ===== 全局限速/缓存设置（rate limit + cache，对应 MiniMax T2A 60 RPM 约束）=====
    private const val KEY_RATE_LIMIT_RPM = "global_rate_limit_rpm"
    private const val KEY_CACHE_ENABLED = "global_cache_enabled"
    private const val KEY_CACHE_MAX_ENTRIES = "global_cache_max_entries"
    private const val KEY_CACHE_TTL_DAYS = "global_cache_ttl_days"

    // ===== 全局响度处理（v0.6.x，针对 MiniMax 后端 RMS 不归一化）=====
    private const val KEY_NORMALIZE_MODE = "global_normalize_mode"  // off / rms / drc

    const val DEFAULT_CONFIG_NAME = "默认配置"

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取所有配置名列表，"默认配置" 始终在首位
     */
    fun getConfigList(context: Context): List<String> {
        val configs = loadConfigs(context)
        val names = mutableListOf(DEFAULT_CONFIG_NAME)
        names.addAll(configs.keys.filter { it != DEFAULT_CONFIG_NAME })
        return names
    }

    /**
     * 获取指定名称的配置。apiKey/groupId/LLM 设置始终从全局存储读取
     */
    fun getConfigByName(context: Context, name: String): VoiceConfig {
        val apiKey = getGlobalApiKey(context)
        val groupId = getGlobalGroupId(context)
        val llmEnabled = getLlmEnabled(context)
        val llmModel = getLlmModel(context)
        val llmPrompt = getLlmPrompt(context)
        val llmMaxTokens = getLlmMaxTokens(context)
        val llmTemperature = getLlmTemperature(context)
        val llmThinking = getLlmThinking(context)
        if (name == DEFAULT_CONFIG_NAME) {
            return VoiceConfig(
                apiKey = apiKey, groupId = groupId,
                llmEnabled = llmEnabled, llmModel = llmModel, llmPrompt = llmPrompt,
                llmMaxTokens = llmMaxTokens, llmTemperature = llmTemperature, llmThinking = llmThinking
            )
        }
        val configs = loadConfigs(context)
        val config = configs[name] ?: VoiceConfig()
        return config.copy(
            apiKey = apiKey, groupId = groupId,
            llmEnabled = llmEnabled, llmModel = llmModel, llmPrompt = llmPrompt,
            llmMaxTokens = llmMaxTokens, llmTemperature = llmTemperature, llmThinking = llmThinking
        )
    }

    /**
     * 保存配置。"默认配置" 不允许保存，直接忽略
     */
    fun saveConfigByName(context: Context, name: String, config: VoiceConfig) {
        if (name == DEFAULT_CONFIG_NAME) return
        val configs = loadConfigs(context)
        configs[name] = config
        saveConfigs(context, configs)
    }

    /**
     * 删除配置。"默认配置" 不允许删除
     */
    fun deleteConfigByName(context: Context, name: String) {
        if (name == DEFAULT_CONFIG_NAME) return
        val configs = loadConfigs(context)
        configs.remove(name)
        saveConfigs(context, configs)
        // 删除的是当前活跃配置，切回默认
        if (getActiveConfigName(context) == name) {
            setActiveConfigName(context, DEFAULT_CONFIG_NAME)
        }
    }

    /**
     * 重命名配置
     */
    fun renameConfig(context: Context, oldName: String, newName: String) {
        if (oldName == DEFAULT_CONFIG_NAME || newName == DEFAULT_CONFIG_NAME) return
        val configs = loadConfigs(context)
        val config = configs[oldName] ?: return
        configs.remove(oldName)
        configs[newName] = config
        saveConfigs(context, configs)
        if (getActiveConfigName(context) == oldName) {
            setActiveConfigName(context, newName)
        }
    }

    fun getActiveConfigName(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_CONFIG, DEFAULT_CONFIG_NAME) ?: DEFAULT_CONFIG_NAME
    }

    fun setActiveConfigName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_CONFIG, name).apply()
    }

    /**
     * 获取当前活跃配置（兼容旧代码直接调 getConfig 的场景）
     */
    fun getConfig(context: Context): VoiceConfig {
        return getConfigByName(context, getActiveConfigName(context))
    }

    /**
     * 保存到当前活跃配置（兼容旧代码直接调 saveConfig 的场景）
     */
    fun saveConfig(context: Context, config: VoiceConfig) {
        saveConfigByName(context, getActiveConfigName(context), config)
    }

    fun getApiKey(context: Context): String = getGlobalApiKey(context)

    // ===== 全局账户凭证（独立于配置） =====

    fun getGlobalApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_GLOBAL_API_KEY, "") ?: ""
    }

    fun setGlobalApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GLOBAL_API_KEY, key).apply()
    }

    fun getGlobalGroupId(context: Context): String {
        return getPrefs(context).getString(KEY_GLOBAL_GROUP_ID, "") ?: ""
    }

    fun setGlobalGroupId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_GLOBAL_GROUP_ID, id).apply()
    }

    fun saveAccount(context: Context, apiKey: String, groupId: String) {
        setGlobalApiKey(context, apiKey)
        setGlobalGroupId(context, groupId)
    }

    // ===== 全局 LLM 设置（独立于配置） =====

    fun getLlmEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_LLM_ENABLED, false)
    fun setLlmEnabled(context: Context, v: Boolean) { getPrefs(context).edit().putBoolean(KEY_LLM_ENABLED, v).apply() }

    fun getLlmModel(context: Context): String = getPrefs(context).getString(KEY_LLM_MODEL, "MiniMax-M2.7-highspeed") ?: "MiniMax-M2.7-highspeed"
    fun setLlmModel(context: Context, v: String) { getPrefs(context).edit().putString(KEY_LLM_MODEL, v).apply() }

    fun getLlmPrompt(context: Context): String = getPrefs(context).getString(KEY_LLM_PROMPT, "") ?: ""
    fun setLlmPrompt(context: Context, v: String) { getPrefs(context).edit().putString(KEY_LLM_PROMPT, v).apply() }

    fun getLlmMaxTokens(context: Context): Int = getPrefs(context).getInt(KEY_LLM_MAX_TOKENS, 2048)
    fun setLlmMaxTokens(context: Context, v: Int) { getPrefs(context).edit().putInt(KEY_LLM_MAX_TOKENS, v).apply() }

    fun getLlmTemperature(context: Context): Double = getPrefs(context).getString(KEY_LLM_TEMPERATURE, "0.1")?.toDoubleOrNull() ?: 0.1
    fun setLlmTemperature(context: Context, v: Double) { getPrefs(context).edit().putString(KEY_LLM_TEMPERATURE, v.toString()).apply() }

    /** LLM 思考模式：""=不传(adaptive) / "enabled"=显式开启 / "disabled"=关闭（仅 MiniMax-M3 生效） */
    fun getLlmThinking(context: Context): String = getPrefs(context).getString(KEY_LLM_THINKING, "disabled") ?: "disabled"
    fun setLlmThinking(context: Context, v: String) { getPrefs(context).edit().putString(KEY_LLM_THINKING, v).apply() }

    // ===== 全局限速/缓存（最小入侵：仅 4 个 pref，配合 AudioCache/RateLimiter 使用）=====

    /**
     * 全局 T2A RPM 上限（默认 60 = MiniMax 国内版官方限速）。
     * 注意：这是**实际发往 MiniMax** 的请求速率，不是从 Legado 收的请求速率。
     * 即使 Legado 以 200 RPM 推请求过来，本组件也会把速率压到 rpm 这一档。
     */
    fun getRateLimitRpm(context: Context): Int = getPrefs(context).getInt(KEY_RATE_LIMIT_RPM, 60).coerceIn(1, 600)
    fun setRateLimitRpm(context: Context, v: Int) { getPrefs(context).edit().putInt(KEY_RATE_LIMIT_RPM, v.coerceIn(1, 600)).apply() }

    /** 是否启用 TTS 音频缓存（命中时 0 次 API 调用） */
    fun getCacheEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_CACHE_ENABLED, true)
    fun setCacheEnabled(context: Context, v: Boolean) { getPrefs(context).edit().putBoolean(KEY_CACHE_ENABLED, v).apply() }

    /** LRU 内存缓存条目上限（默认 100）。磁盘缓存无上限，依赖 TTL 兜底。 */
    fun getCacheMaxEntries(context: Context): Int = getPrefs(context).getInt(KEY_CACHE_MAX_ENTRIES, 100).coerceAtLeast(1)
    fun setCacheMaxEntries(context: Context, v: Int) { getPrefs(context).edit().putInt(KEY_CACHE_MAX_ENTRIES, v.coerceAtLeast(1)).apply() }

    /** 缓存 TTL（天），超过此值从磁盘驱逐。默认 7 天。 */
    fun getCacheTtlDays(context: Context): Long = getPrefs(context).getLong(KEY_CACHE_TTL_DAYS, 7L).coerceAtLeast(1L)
    fun setCacheTtlDays(context: Context, v: Long) { getPrefs(context).edit().putLong(KEY_CACHE_TTL_DAYS, v.coerceAtLeast(1L)).apply() }

    // ===== 全局响度处理 =====

    /**
     * 响度处理模式（v0.6.x）：
     * - "off"（默认）：不做处理，保留 MiniMax 原始响度（可能忽大忽小）
     * - "rms"：每段 wav 整体 gain 到目标 dBFS（-18 dBFS），所有段响度一致
     * - "drc"：动态范围压缩，保留抑扬但拉近极值
     *
     * 注：缓存里存原始 wav，模式在合成返回前才生效 —— 切换模式立即生效，无需清缓存。
     */
    fun getNormalizeMode(context: Context): String {
        val v = getPrefs(context).getString(KEY_NORMALIZE_MODE, "off") ?: "off"
        return if (v in listOf("off", "rms", "drc")) v else "off"
    }

    fun setNormalizeMode(context: Context, mode: String) {
        val safe = if (mode in listOf("off", "rms", "drc")) mode else "off"
        getPrefs(context).edit().putString(KEY_NORMALIZE_MODE, safe).apply()
    }

    /**
     * 加载所有用户配置，含旧数据迁移
     */
    private fun loadConfigs(context: Context): MutableMap<String, VoiceConfig> {
        val prefs = getPrefs(context)
        // 旧数据迁移：检测单配置 key
        val legacyJson = prefs.getString(KEY_LEGACY_CONFIG, null)
        if (legacyJson != null) {
            try {
                val config = gson.fromJson(legacyJson, VoiceConfig::class.java)
                // 迁移 apiKey/groupId 到全局存储
                if (config.apiKey.isNotBlank()) setGlobalApiKey(context, config.apiKey)
                if (config.groupId.isNotBlank()) setGlobalGroupId(context, config.groupId)
                // 迁移 LLM 设置到全局存储
                setLlmEnabled(context, config.llmEnabled)
                setLlmModel(context, config.llmModel)
                setLlmPrompt(context, config.llmPrompt)
                setLlmMaxTokens(context, config.llmMaxTokens)
                setLlmTemperature(context, config.llmTemperature)
                setLlmThinking(context, config.llmThinking)
                val configs = mutableMapOf<String, VoiceConfig>()
                // 只有非默认值才迁移，否则丢弃
                val hasNonDefault = config.model != "speech-2.8-hd" ||
                    config.voice != "audiobook_male_1" ||
                    config.speed != 1.0 || config.vol != 1.0 || config.pitch != 0
                if (hasNonDefault) {
                    configs["我的配置"] = config.copy(apiKey = "", groupId = "")
                    setActiveConfigName(context, "我的配置")
                }
                saveConfigs(context, configs)
                prefs.edit().remove(KEY_LEGACY_CONFIG).apply()
                return configs
            } catch (_: Exception) {
                prefs.edit().remove(KEY_LEGACY_CONFIG).apply()
            }
        }
        // 正常加载
        val json = prefs.getString(KEY_VOICE_CONFIGS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<MutableMap<String, VoiceConfig>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }
    }

    private fun saveConfigs(context: Context, configs: Map<String, VoiceConfig>) {
        val json = gson.toJson(configs)
        getPrefs(context).edit().putString(KEY_VOICE_CONFIGS, json).apply()
    }
}
