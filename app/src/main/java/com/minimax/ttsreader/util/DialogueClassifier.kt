package com.minimax.ttsreader.util

/**
 * 对话分类器（v0.8.0 双引擎方案）
 *
 * 按字符串规则判断给定文本段是"人物台词"还是"旁白叙述"，决定走哪个 TTS 引擎：
 * - 台词 → MiniMax TTS（情绪化声音 + emotion 参数）
 * - 旁白 → 本地 Piper / 系统 TTS（零 token 成本）
 *
 * 判定优先级（任一命中即台词，否则旁白）：
 * 1. **引号对**：中文"" / 「」 / 英文"" / '' 都算（必须成对出现）
 * 2. **冒号格式**：1-6 个汉字 + 道/说/问/喊/叫/笑/叹/答/喝/议论/自语 + （可选空白）+ 中英文冒号
 * 3. **心理活动**：1-6 个汉字 + 心/想/忖/思/默念/忆 + （可选空白）+ 中英文冒号
 * 4. **默认旁白**
 *
 * 覆盖估算（中文小说主流场景）：
 * - 纯旁白小说 → 100% 旁白
 * - 对话 30% 小说 → 70% 字符走 Piper，30% 走 MiniMax
 * - 心理活动密集小说 → ~10% 漏判降级到旁白（不影响听感）
 *
 * 不调用 LLM——纯字符串规则，零 token 成本。
 *
 * v0.8.1（Stage 11 修复）：**移除了原"URL 信号"规则（speaker/toneID 非空 → DIALOGUE）**。
 * 原规则被两个事实绕过：
 * 1. `voice` 是用户全局音色（每次请求都带，永远非空），原代码把它当 toneID
 * 2. Rimchars Legado 多角色模块给每个 segment 都打 fallback speaker（"精英青年"），永远非空
 * 二者叠加导致纯旁白段（如 "结果他妈的在巴西挖了两年..."）也判 DIALOGUE。
 * 修复后纯按文本规则判定，与 Rimchars fallback 解耦。
 */
object DialogueClassifier {

    enum class SegmentType {
        DIALOGUE,    // 人物台词 → 走 MiniMax TTS
        NARRATION    // 旁白叙述 → 走本地 Piper / 系统 TTS
    }

    /**
     * 主判定入口
     *
     * @param text 待合成文本（已过滤 LLM 净化后的内容）
     * @param currentSpeakerName 保留参数以兼容调用方，**当前不再使用**（v0.8.1 修复后）
     * @param currentToneID 保留参数以兼容调用方，**当前不再使用**（v0.8.1 修复后）
     */
    fun classify(
        text: String,
        @Suppress("UNUSED_PARAMETER") currentSpeakerName: String? = null,
        @Suppress("UNUSED_PARAMETER") currentToneID: String? = null
    ): SegmentType {
        if (text.isBlank()) return SegmentType.NARRATION

        // 优先级 1: 引号对
        if (hasQuotePair(text)) {
            return SegmentType.DIALOGUE
        }

        // 优先级 2: 冒号格式（"张三道："）
        if (matchesSpeakerColon(text)) {
            return SegmentType.DIALOGUE
        }

        // 优先级 3: 心理活动
        if (matchesMentalActivity(text)) {
            return SegmentType.DIALOGUE
        }

        return SegmentType.NARRATION
    }

    /**
     * 检测引号对（中文/英文/书名号）
     *
     * 单一引号不算（避免误判），必须成对出现。
     */
    private fun hasQuotePair(text: String): Boolean {
        // 中文双引号 ""（必须成对）
        if ((text.count { it == '\u201C' } >= 1) && (text.count { it == '\u201D' } >= 1)) {
            return true
        }
        // 中文单引号 ''（必须成对）
        if ((text.count { it == '\u2018' } >= 1) && (text.count { it == '\u2019' } >= 1)) {
            return true
        }
        // 中文直角引号 「」（必须成对）
        if ((text.count { it == '\u300C' } >= 1) && (text.count { it == '\u300D' } >= 1)) {
            return true
        }
        // 英文双引号 ""（必须成对且非 0）
        val dq = text.count { it == '\"' }
        if (dq >= 2 && dq % 2 == 0) return true
        // 英文单引号 ''（必须成对）
        val sq = text.count { it == '\'' }
        if (sq >= 2 && sq % 2 == 0) return true

        return false
    }

    /**
     * 检测"XX 道：/说：/问：/喊："等冒号格式（小说常见的对白提示）
     *
     * 注意：不匹配"我在：/我们去："这类第一人称叙述（第一人称后不应有"道/说/问/喊"等动词）
     */
    private fun matchesSpeakerColon(text: String): Boolean {
        // 1-6 个汉字 + 道/说/问/喊/叫/笑/叹/答/喝/议/论/自语 + 可选空白 + 中英文冒号
        val pattern = Regex("""[\u4E00-\u9FA5]{1,6}(?:道|说|问|喊|叫|笑|叹|答|喝|议|论|自语)[\s]*[:：]""")
        return pattern.containsMatchIn(text)
    }

    /**
     * 检测心理活动（"XX 心想：/暗忖：/自思：/默念："）
     */
    private fun matchesMentalActivity(text: String): Boolean {
        val pattern = Regex("""[\u4E00-\u9FA5]{1,6}(?:心想|忖|思|默念|忆)[\s]*[:：]""")
        return pattern.containsMatchIn(text)
    }
}