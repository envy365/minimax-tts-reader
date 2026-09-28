package com.minimax.ttsreader.model

/**
 * MiniMax TTS 配置（国内版 t2a_v2）
 * 覆盖 voice_setting / audio_setting / voice_modify / pronunciation_dict 全部可调参数
 */
data class VoiceConfig(
    // ===== 认证 =====
    val apiKey: String = "",          // MiniMax API Key
    val groupId: String = "",         // 国内版必需，拼接在 URL 末尾

    // ===== 模型与音色 =====
    val model: String = "speech-2.8-hd",
    val voice: String = "audiobook_male_1",  // voice_id，默认男性有声书（最适合听书）

    // ===== 基础声音参数 (voice_setting) =====
    val speed: Double = 1.0,          // [0.5, 2] 语速
    val vol: Double = 1.0,            // (0, 10] 音量
    val pitch: Int = 0,               // [-12, 12] 语调
    val emotion: String = "",         // happy/sad/angry/fearful/disgusted/surprised/neutral/calm/fluent/whisper，空=不传
    val languageBoost: String = "auto",
    val textNormalization: Boolean = false,  // 文本规范化，数字场景正确读数字，略微增加延迟

    // ===== 高级 voice_modify（wav 生效，当前恒 wav）=====
    val vmPitch: Int = 0,             // [-100, 100] 音高 -100更低沉/100更明亮
    val vmIntensity: Int = 0,         // [-100, 100] 强度 -100更刚劲/100更轻柔
    val vmTimbre: Int = 0,            // [-100, 100] 音色 -100更浑厚/100更清脆
    val soundEffects: String = "",    // spacious_echo/auditorium_echo/lofi_telephone/robotic，空=无

    // ===== 音频设置 (audio_setting) =====
    val sampleRate: Int = 32000,      // 8000/16000/22050/24000/32000/44100
    val bitrate: Int = 128000,        // 32000/64000/128000/256000（仅 mp3 生效，wav 下为无效参数）
    val audioFormat: String = "wav",  // 固定 wav（决策 1B：ExoPlayer 最稳容器）；字段保留仅为兼容旧配置反序列化；注意 Gson 不走构造函数，旧配置缺失该字段时反序列化为 null 而非默认 "wav"（当前无消费点，影响为零）
    val channel: Int = 1,             // 1单声道 / 2双声道

    // ===== 发音字典 (pronunciation_dict.tone) =====
    val pronunciationDict: String = "",  // 逗号分隔，如 "燕少飞/(yan4)(shao3)(fei1),omg/oh my god"

    // ===== 服务 =====
    val serverPort: Int = 9966,

    // ===== LLM 文本预处理（朗读前净化）=====
    val llmEnabled: Boolean = false,            // 是否在合成前用 LLM 预处理文本
    val llmModel: String = "MiniMax-M2.7-highspeed",  // MiniMax 国内版 chat 模型
    val llmPrompt: String = "",               // 自定义 system prompt，空则用默认净化规则
    val llmMaxTokens: Int = 2048,              // LLM 单次最大输出 token
    val llmTemperature: Double = 0.1,          // 低温保稳定，避免改写发散
    // LLM 思考模式（chatcompletion_v2 thinking 参数）：
    //   ""        = 不传 thinking 字段（服务端默认 adaptive）
    //   "enabled" = 显式开启思考（请求体写 adaptive）
    //   "disabled"= 关闭思考
    // 仅 MiniMax-M3 生效；M2.x 全系不支持关闭思考，传了也无效，故对非 M3 模型不传
    val llmThinking: String = "disabled"
)
