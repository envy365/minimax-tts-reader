package com.minimax.ttsreader.model

/**
 * MiniMax T2A v2 接口数据模型
 * 文档: https://platform.minimaxi.com/document/T2A%20Large%20v2
 */

// ===================== 请求体 =====================
data class MiniMaxTtsRequest(
    val model: String,
    val text: String,
    val stream: Boolean = false,
    val voice_setting: VoiceSetting,
    val audio_setting: AudioSetting? = null,
    val pronunciation_dict: PronunciationDict? = null,
    val language_boost: String = "auto",
    val voice_modify: VoiceModify? = null,
    val output_format: String = "hex"   // hex 默认；流式用 pcm 拼接
)

data class VoiceSetting(
    val voice_id: String,
    val speed: Double = 1.0,        // [0.5, 2]
    val vol: Double = 1.0,          // (0, 10]
    val pitch: Int = 0,             // [-12, 12]
    val emotion: String? = null,    // 9 种情感，null=不传
    val text_normalization: Boolean? = null
)

data class AudioSetting(
    val sample_rate: Int = 32000,
    val bitrate: Int = 128000,
    val format: String = "mp3",     // mp3/pcm/flac
    val channel: Int = 1            // 1/2
)

data class PronunciationDict(
    val tone: List<String>
)

data class VoiceModify(
    val pitch: Int = 0,             // [-100, 100]
    val intensity: Int = 0,         // [-100, 100]
    val timbre: Int = 0,            // [-100, 100]
    val sound_effects: String? = null
)

// ===================== 响应体（非流式）=====================
data class MiniMaxTtsResponse(
    val data: TtsData? = null,
    val extra_info: ExtraInfo? = null,
    val base_resp: BaseResp? = null
) {
    data class TtsData(
        val audio: String = "",
        val status: Int = 0
    )

    data class ExtraInfo(
        val audio_length: Long = 0,
        val audio_sample_rate: Int = 0,
        val audio_size: Long = 0,
        val bitrate: Int = 0,
        val word_count: Int = 0,
        val audio_format: String = "",
        val audio_channel: Int = 0
    )

    data class BaseResp(
        val status_code: Int = 0,       // 0=成功 1004=鉴权失败 1002=限流 1013=非法字符超10%
        val status_msg: String = ""
    )
}

// ===================== 音色信息 =====================
data class VoiceInfo(
    val id: String,
    val name: String,
    val language: String,
    val gender: String,
    val category: String = ""
)

// ===================== 音色与选项注册表 =====================
object VoiceRegistry {

    /** MiniMax 系统音色（有声书音色置顶，最适合听书） */
    val PRESET_VOICES = listOf(
        // 有声书（推荐听书）
        VoiceInfo("audiobook_male_1", "男性有声书1", "中文", "男性", "有声书"),
        VoiceInfo("audiobook_male_2", "男性有声书2", "中文", "男性", "有声书"),
        VoiceInfo("audiobook_female_1", "女性有声书1", "中文", "女性", "有声书"),
        VoiceInfo("audiobook_female_2", "女性有声书2", "中文", "女性", "有声书"),
        // 主持人
        VoiceInfo("presenter_male", "男性主持人", "中文", "男性", "主持"),
        VoiceInfo("presenter_female", "女性主持人", "中文", "女性", "主持"),
        // 基础青年音色
        VoiceInfo("male-qn-qingse", "青涩青年", "中文", "男性", "青年"),
        VoiceInfo("male-qn-jingying", "精英青年", "中文", "男性", "青年"),
        VoiceInfo("male-qn-badao", "霸道青年", "中文", "男性", "青年"),
        VoiceInfo("male-qn-daxuesheng", "青年大学生", "中文", "男性", "青年"),
        VoiceInfo("female-shaonv", "少女", "中文", "女性", "青年"),
        VoiceInfo("female-yujie", "御姐", "中文", "女性", "青年"),
        VoiceInfo("female-chengshu", "成熟女性", "中文", "女性", "青年"),
        VoiceInfo("female-tianmei", "甜美女性", "中文", "女性", "青年"),
        // 童声/卡通
        VoiceInfo("clever_boy", "聪明男童", "中文", "男性", "童声"),
        VoiceInfo("cute_boy", "可爱男童", "中文", "男性", "童声"),
        VoiceInfo("lovely_girl", "萌萌女童", "中文", "女性", "童声"),
        VoiceInfo("cartoon_pig", "卡通猪小琪", "中文", "混合", "卡通"),
        // beta 精品音色
        VoiceInfo("male-qn-qingse-jingpin", "青涩青年-beta", "中文", "男性", "精品"),
        VoiceInfo("male-qn-jingying-jingpin", "精英青年-beta", "中文", "男性", "精品"),
        VoiceInfo("male-qn-badao-jingpin", "霸道青年-beta", "中文", "男性", "精品"),
        VoiceInfo("male-qn-daxuesheng-jingpin", "青年大学生-beta", "中文", "男性", "精品"),
        VoiceInfo("female-shaonv-jingpin", "少女-beta", "中文", "女性", "精品"),
        VoiceInfo("female-yujie-jingpin", "御姐-beta", "中文", "女性", "精品"),
        VoiceInfo("female-chengshu-jingpin", "成熟女性-beta", "中文", "女性", "精品"),
        VoiceInfo("female-tianmei-jingpin", "甜美女性-beta", "中文", "女性", "精品")
    )

    /** 可用模型 */
    val MODELS = listOf(
        "speech-2.8-hd" to "Speech-2.8-HD（最新，音质好，支持语气词标签）",
        "speech-2.8-turbo" to "Speech-2.8-Turbo（最新，低延迟，支持语气词标签）",
        "speech-2.6-hd" to "Speech-2.6-HD",
        "speech-2.6-turbo" to "Speech-2.6-Turbo",
        "speech-02-hd" to "Speech-02-HD（旧版，稳定）",
        "speech-02-turbo" to "Speech-02-Turbo（旧版，低延迟）",
        "speech-01-hd" to "Speech-01-HD（旧版）",
        "speech-01-turbo" to "Speech-01-Turbo（旧版）"
    )

    /** 情感（仅部分模型生效）*/
    val EMOTIONS = listOf(
        "" to "不指定（默认）",
        "neutral" to "中性",
        "happy" to "高兴",
        "sad" to "悲伤",
        "angry" to "愤怒",
        "fearful" to "害怕",
        "disgusted" to "厌恶",
        "surprised" to "惊讶",
        "calm" to "平静（2.6+）",
        "fluent" to "生动（2.6+）",
        "whisper" to "低语（2.6，不支持2.8）"
    )

    /** 语言优化 */
    val LANGUAGE_BOOSTS = listOf(
        "auto" to "自动判断",
        "Chinese" to "中文",
        "Chinese,Yue" to "粤语",
        "English" to "英语",
        "Japanese" to "日语",
        "Korean" to "韩语",
        "French" to "法语",
        "German" to "德语",
        "Spanish" to "西班牙语",
        "Russian" to "俄语",
        "Arabic" to "阿拉伯语",
        "Portuguese" to "葡萄牙语",
        "Italian" to "意大利语",
        "Vietnamese" to "越南语",
        "Indonesian" to "印尼语",
        "Thai" to "泰语",
        "Hindi" to "印地语"
    )

    /** 音效 */
    val SOUND_EFFECTS = listOf(
        "" to "无音效",
        "spacious_echo" to "空旷回音",
        "auditorium_echo" to "礼堂广播",
        "lofi_telephone" to "电话失真",
        "robotic" to "机械音"
    )

    val SAMPLE_RATES = listOf(8000, 16000, 22050, 24000, 32000, 44100)
    val BITRATES = listOf(32000, 64000, 128000, 256000)
    val FORMATS = listOf("wav", "mp3", "pcm", "flac")
    val CHANNELS = listOf(1 to "单声道", 2 to "双声道")

    /** 可用 LLM 模型（国内版 chatcompletion_v2）*/
    val LLM_MODELS = listOf(
        "MiniMax-M2.7-highspeed" to "MiniMax-M2.7-highspeed（速度快，低成本，推荐预处理用）",
        "MiniMax-M2.7" to "MiniMax-M2.7（旗舰文本模型）",
        "MiniMax-M2.5-highspeed" to "MiniMax-M2.5-highspeed（高速）",
        "MiniMax-M2.5" to "MiniMax-M2.5（旗舰）",
        "MiniMax-M2.1-highspeed" to "MiniMax-M2.1-highspeed（推理高速）",
        "MiniMax-M2.1" to "MiniMax-M2.1（推理）",
        "MiniMax-M2" to "MiniMax-M2（通用均衡）",
        "MiniMax-M3" to "MiniMax-M3（最新旗舰，贵但能力最强）",
        "abab6.5s-chat" to "abab6.5s-chat（旧版长上下文）"
    )

    /** 默认文本净化 prompt：只处理符号/冗余/语气标记，不改写语义 */
    /** 默认文本净化 prompt（高级版，适用于 speech-2.8 模型，含语气词标签/停顿/行内发音） */
    val DEFAULT_LLM_PROMPT = """你是一个文本净化助手，用于 TTS 朗读前置处理（针对 MiniMax Speech 2.8 模型）。
输入是来自书源/网页/手动粘贴的中文文本（含 Markdown、表情、动作括注、多音字、冗余空行等）。
输出是 TTS 友好的纯文本：去杂、规范标点、可选插入行内标签和停顿标记。
不要翻译、不要扩充、不要概括、不要补充。输出必须是净化后的文本本身，不加任何解释或包裹。
# 规则
1. Markdown 与冗余符号清除
   - 去除 Markdown 符号：*、#、>、`、[]() 链接、~~删除线~~、| 表格分隔、加粗 xxx。
   - 去除 HTML 残留：&nbsp; &amp; <br> 等。
   - 不去除原有的中文逗号、句号、引号、问号、感叹号、省略号、破折号--这些是 TTS 节奏控制的关键。
2. 表情、署名、水印清除
   - 去除表情符号（😀👍🔥）、颜文字（(╯°□°）╯）、Emoji、unicode 装饰字符（❄️✨）。
   - 去除署名/出处/水印：「作者：xxx」「转载自」「公众号 xxx」「本文来源于…」「全文完」「（完）」「END」等文末/文首标识。
   - 去除广告虚词：「分享自 xxx」「扫码关注」。
3. 动作/情绪括注 -> MiniMax Speech 2.8 行内标签
   原文里"（）"形式的中文动作描述转换为 MiniMax 行内标签。映射表：
     （笑）/（笑声）/（大笑）   -> (laughs)
     （轻笑）/（微笑）/（浅笑）  -> (chuckle)
     （叹气）/（叹息）         -> (sighs)
     （咳嗽）                -> (coughs)
     （吸气）/（深呼吸）       -> (inhale)
     （呼气）                -> (exhale)
     （喘气）/（喘息）        -> (pant)
     （倒吸气）              -> (gasps)
     （吸鼻子）              -> (sniffs)
     （清嗓子）              -> (clear-throat)
     （呻吟）                -> (groans)
     （嗯）/（呃）            -> (emm)
     （哼唱）                -> (humming)
     （抽泣）                -> (crying)
     （嘶嘶声）              -> (hissing)
     （咂嘴）                -> (lip-smacking)
     （打嗝）                -> (burps)
     （喷鼻息）              -> (snorts)
     （喷嚏）                -> (sneezes)
     （鼓掌）                -> (applause)
     （口哨）                -> (whistles)
   - 标签紧贴原文位置插入，保留原标点结构：「他笑了。」->「他(laughs)笑了。」或「他笑了(laughs)。」
   - 不可映射的（如「（小声）」「（急促地）」）直接丢弃括注本身，保留上下文。
   - 不要硬加标签：只在原文有动作/情绪描述时转写，没有就不动。
4. 行内标签使用约束（避免滥用）
   - 单段文本最多 1-2 个标签，三五百字听书里出现 1 次即可。
   - 标签放句首或句尾，不放从句中间。
   - （打嗝）（喷嚏）（鼓掌）（口哨）（抽泣）这类听感油腻的更要节制。
5. 标点规范化
   - 多个省略号合并为一个「……」：「. . . .」「........」->「……」。
   - 连续重复标点合并为单个：「！！！」->「！」，「？？？」->「？」。
   - 英文标点转中文：句末的 "."、"!"、"?" 转 "。""！""？"；半角引号 'xxx' 转全角 "xxx"。
   - "..." 转为省略号形式 "……"。
6. 停顿标记 <#x#>
   - 仅在明确需要时插入：场景切换（时间跳转、视角切换）、关键转折前。
   - x ∈ [0.01, 99.99] 秒，常用 0.3、0.5、0.8。
   - 长篇听书每章最多 3-5 次，不要滥用。
   - 必须放在两个可发音文字之间，不可连用。
7. 长句加逗号
   - 单句长度 ≤ 20 个中文字符（不含标点）。
   - 超过时在最近可停顿位置插入全角逗号"，"，按优先级选位置：
     顿号 < 主谓之间 < 并列分句之间 < 状语前后。
   - 这是同一句内加逗号，不是拆句拆行--与"不拆句"不冲突。
   - 绝不主动改句号、问号、感叹号。
8. 多音字行内发音替换
   - 仅在原文语境下明显易误读的词才标注，明确无歧义的不标。
   - 沿用项目既定格式(lao2)行走（拼音 + 行内嵌入）。
   - 拼音数字标调：1 一声 / 2 二声 / 3 三声 / 4 四声 / 5 轻声。
   - 常见需标注：行(xing2)/为(wei2)/重(zhong4)/长(chang2)/便(pián)/觉(jiao4)/藏(cang2)。
9. AI 口水词黑名单
   原文不会出现，但改写时偶尔会引入，发现即删除/改写：
   赋能、打造、深入探讨、值得一提的是、不难发现、众所周知、总的来说、综上所述、
   进一步凸显了…、为…注入了新的活力、深入浅出、举足轻重、至关重要。
10. 段落结构保留
    - 不重排、不合并、不拆分段落顺序。
    - 删除多余空行、全角空格、连续换行。
    - 段落内自然换行保留（用于 TTS 段落停顿）；如需强制间隔可插 <#0.8#>。
"""

    /** 基础净化 prompt（适用于 speech-2.6/02/01 等旧模型，不支持语气词标签） */
    val BASIC_LLM_PROMPT = """你是一个文本净化助手，用于 TTS 朗读前置处理（适配 MiniMax Speech 2.8 以前版本：speech-2.6 系列、speech-02 系列等）。
输入是来自书源/网页/手动粘贴的中文文本（含 Markdown、表情、动作括注、多音字、冗余空行等）。
输出是 TTS 友好的纯文本：去杂、规范标点、可选插入停顿标记。
不要翻译、不要扩充、不要概括、不要补充。输出必须是净化后的文本本身，不加任何解释或包裹。
# 规则
1. Markdown 与冗余符号清除
   - 去除 Markdown 符号：*、#、>、`、[]() 链接、~~删除线~~、| 表格分隔、加粗 xxx。
   - 去除 HTML 残留：&nbsp; &amp; <br> 等。
   - 不去除原有的中文逗号、句号、引号、问号、感叹号、省略号、破折号--这些是 TTS 节奏控制的关键。
2. 表情、署名、水印清除
   - 去除表情符号（😀👍🔥）、颜文字（(╯°□°）╯）、Emoji、unicode 装饰字符（❄️✨）。
   - 去除署名/出处/水印：「作者：xxx」「转载自」「公众号 xxx」「本文来源于…」「全文完」「（完）」「END」等文末/文首标识。
   - 去除广告虚词：「分享自 xxx」「扫码关注」。
3. 动作/情绪括注清除
   - 去除中文全角括号"（）"及其内部的动作/情绪描述文字，保留括号外的上下文。
   - 例如：「他（笑）着说」->「他说」，「（叹了口气）好吧」->「好吧」，「她微微一笑（轻笑）」->「她微微一笑」。
   - 2.8 以前版本不支持行内音效标签，括注内容若保留会被模型逐字朗读，因此必须丢弃。
   - 丢弃后不添加任何替代标记或符号。
   - 不要试图保留情绪信息--2.8 以前版本的 emotion 通过 API 参数控制，不在文本层传递。
4. 标点规范化
   - 多个省略号合并为一个「……」：「. . . .」「........」->「……」。
   - 连续重复标点合并为单个：「！！！」->「！」，「？？？」->「？」。
   - 英文标点转中文：句末的 "."、"!"、"?" 转 "。""！""？"；半角引号 'xxx' 转全角 "xxx"。
   - "..." 转为省略号形式 "……"。
5. 停顿标记 <#x#>
   - MiniMax TTS 全版本通用的停顿语法。插入 <#x#> 控制两段文本之间的静默时长。
   - x ∈ [0.01, 99.99] 秒，常用 0.3、0.5、0.8。
   - 仅在明确需要时插入：场景切换（时间跳转、视角切换）、关键转折前。
   - 长篇听书每章最多 3-5 次，不要滥用。
   - 必须放在两个可发音文字之间，不可连用。
6. 长句加逗号
   - 单句长度 ≤ 20 个中文字符（不含标点）。
   - 超过时在最近可停顿位置插入全角逗号"，"，按优先级选位置：
     顿号 < 主谓之间 < 并列分句之间 < 状语前后。
   - 这是同一句内加逗号，不是拆句拆行。
   - 绝不主动改句号、问号、感叹号。
7. 多音字行内发音替换
   - 仅在原文语境下明显易误读的词才标注，明确无歧义的不标。
   - 沿用项目既定格式：(lao2)行走（拼音 + 行内嵌入）。
   - 拼音数字标调：1 一声 / 2 二声 / 3 三声 / 4 四声 / 5 轻声。
   - 常见需标注：行(xing2)/为(wei2)/重(zhong4)/长(chang2)/便(pian2)/觉(jiao4)/藏(cang2)。
8. AI 口水词黑名单
   原文不会出现，但改写时偶尔会引入，发现即删除/改写：
   赋能、打造、深入探讨、值得一提的是、不难发现、众所周知、总的来说、综上所述、
   进一步凸显了…、为…注入了新的活力、深入浅出、举足轻重、至关重要。
9. 段落结构保留
   - 不重排、不合并、不拆分段落顺序。
   - 删除多余空行、全角空格、连续换行。
   - 段落内自然换行保留（用于 TTS 段落停顿）；如需强制间隔可插 <#0.8#>。
"""

    /** 根据模型自动选择 prompt：speech-2.8 用高级版（含语气词标签），其他用基础版 */
    fun getPromptForModel(model: String, customPrompt: String): String {
        if (customPrompt.isNotBlank()) return customPrompt
        return if (model.startsWith("speech-2.8")) DEFAULT_LLM_PROMPT else BASIC_LLM_PROMPT
    }

    fun getVoiceId(name: String): String {
        return PRESET_VOICES.find { it.name == name || it.id == name }?.id ?: "audiobook_male_1"
    }

    /**
     * 生成 Reading Archive HttpTTS.speakersJson 字段（v0.7.1）。
     *
     * 用途：填进 buildLegadoRule() 的 JSON，让 Legado 的「发言人管理」picker 看到可用的 voice 列表。
     * 不传这个字段 → picker 永远空（用户在 Legado 端只能选「TTS 服务」但看不到具体 voice）。
     *
     * 格式参考：https://github.com/ReadingArchive/assets/web/help/md/httpTTSHelp.md
     * - 平铺: `[{ "speakerName": "...", "toneID": "..." }]`
     * - 分组: `[{ "groupId": "...", "groupName": "...", "items": [...] }]`（按 category 分组）
     *
     * 实现：按 VoiceInfo.category 分组，category 字段含「有声书」「主持」「青年」「童声」「卡通」「其他」。
     */
    fun buildSpeakersJsonForLegado(): String {
        val grouped = PRESET_VOICES.groupBy { it.category.ifBlank { "其他" } }
        val sb = StringBuilder("[")
        var first = true
        for ((category, voices) in grouped) {
            if (!first) sb.append(",")
            first = false
            sb.append("{")
            sb.append("\"groupId\":").append(jsonString(category))
            sb.append(",")
            sb.append("\"groupName\":").append(jsonString(category))
            sb.append(",")
            sb.append("\"items\":[")
            voices.forEachIndexed { idx, v ->
                if (idx > 0) sb.append(",")
                sb.append("{\"speakerName\":").append(jsonString(v.name))
                sb.append(",\"toneID\":").append(jsonString(v.id))
                sb.append(",\"gender\":").append(jsonString(v.gender))
                sb.append("}")
            }
            sb.append("]")
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    /** JSON 字符串字面量转义（仅处理中文/英文/数字/标点够用，不处理控制字符） */
    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
