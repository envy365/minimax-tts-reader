package com.minimax.ttsreader.util

/**
 * 子段切分器（v0.8.2 — Stage 14）
 *
 * 把 Legado 给的单个 segment 切成多个 (narrator + dialogue) 子段，让双引擎路由能按子段分配。
 * 借鉴 tts-server-android 项目的引号切分算法：纯字符遍历，遇到引号切段。
 *
 * 切分优先级（从高到低，命中后直接覆盖低优先级）：
 * 1. **心理活动边界**（"XX 心想："）—— 整段判 DIALOGUE（心理活动上下文不丢失）
 * 2. **中文双引号边界**（" "）—— 主边界，引号内台词、引号外旁白
 * 3. **冒号格式边界**（"XX 道："）—— 上半强制 NARRATE（叙述者提示），下半 DIALOGUE
 *
 * 输出 List<SubSegment>：每个子段带 text / type / 区间 / 触发规则标签。
 *
 * 与 DialogueClassifier.classify 1:1 等价：引号对/冒号格式/心理活动判定。
 */
object SegmentSplitter {

    /** 子段：text + 分类标签 + 在原段的位置区间 + 触发规则 */
    data class SubSegment(
        val text: String,
        val type: DialogueClassifier.SegmentType,
        val start: Int,
        val end: Int,
        val rule: String
    )

    // "XX 道/说/问/喊/叫/笑/叹/答/喝/议/论/自语[:：]" —— 引号切完后副边界
    private val SPEAKER_COLON_BOUNDARY = Regex(
        """[\u4E00-\u9FA5]{1,6}(?:道|说|问|喊|叫|笑|叹|答|喝|议|论|自语)[\s]*[:：]"""
    )

    // "XX心想/忖/思/默念/忆[:：]" —— 心理活动边界
    private val MENTAL_BOUNDARY = Regex(
        """[\u4E00-\u9FA5]{1,6}(?:心想|忖|思|默念|忆)[\s]*[:：]"""
    )

    // 中文引号字符
    private const val CN_OPEN_QUOTE_LEFT = '\u201C'   // "
    private const val CN_OPEN_QUOTE_RIGHT = '\u201D'  // "
    private const val CN_CORNER_LEFT = '\u300C'       // 「
    private const val CN_CORNER_RIGHT = '\u300D'      // 」

    /**
     * 主切分入口：先按引号对切，再叠加 "XX 道：" / "XX心想：" 边界。
     *
     * @param text Legado 给的完整 segment 文本
     * @return List<SubSegment>，单 segment 时返回 size=1 的 list
     */
    fun split(text: String): List<SubSegment> {
        if (text.isBlank()) return emptyList()

        // 第一层：按引号对切
        val cuts = mutableListOf<IntArray>()
        collectQuoteBoundaries(text, cuts)
        // 第二层：叠加 speaker-colon / mental 边界
        collectSpeakerColonBoundaries(text, cuts)
        collectMentalBoundaries(text, cuts)
        cuts.sortBy { it[0] }

        // 决策：mental 边界存在 → 整段 DIALOGUE
        val hasMental = cuts.any { it[1] == 5 }
        if (hasMental) {
            return listOf(
                SubSegment(
                    text = text,
                    type = DialogueClassifier.SegmentType.DIALOGUE,
                    start = 0,
                    end = text.length,
                    rule = "mental-overrides"
                )
            )
        }

        if (cuts.isEmpty()) {
            // 无任何边界，整段 DialogueClassifier
            val type = DialogueClassifier.classify(text)
            return listOf(SubSegment(text, type, 0, text.length, "no-split"))
        }

        val result = mutableListOf<SubSegment>()
        var cursor = 0
        for (cut in cuts) {
            val cutAt = cut[0]
            if (cutAt <= cursor) continue
            val sub = text.substring(cursor, cutAt)
            if (sub.isNotBlank()) {
                val type = classifySub(sub)
                val rule = ruleLabel(cut)
                result.add(SubSegment(sub, type, cursor, cutAt, rule))
            }
            cursor = cutAt
        }
        // 最后一个子段
        if (cursor < text.length) {
            val sub = text.substring(cursor)
            if (sub.isNotBlank()) {
                val type = classifySub(sub)
                result.add(SubSegment(sub, type, cursor, text.length, "tail"))
            }
        }
        return result
    }

    /**
     * 子段分类：speaker-colon 切的前半强制 NARRATE（叙述者提示），后半强制 DIALOGUE（台词）；
     * 引号切的无 speaker-colon 时按 DialogueClassifier 判（引号内 DIALOGUE，引号外 NARRATE）。
     */
    private fun classifySub(sub: String): DialogueClassifier.SegmentType {
        val match = SPEAKER_COLON_BOUNDARY.find(sub)
        if (match != null) {
            val cutAt = match.range.last + 1
            val head = sub.substring(0, cutAt)
            if (head.isNotBlank()) {
                return DialogueClassifier.SegmentType.NARRATION
            }
        }
        return DialogueClassifier.classify(sub)
    }

    private fun ruleLabel(cut: IntArray): String {
        val code = cut[1]
        return when {
            (code and 1) != 0 -> "quote-open"
            (code and 2) != 0 -> "quote-close"
            (code and 4) != 0 -> "speaker-colon"
            else -> "?"
        }
    }

    /**
     * 收集所有引号边界（cutAt = 引号位置 + 1，即 cutAt 处切分）。
     * 借鉴 tts-server-android 算法：纯字符遍历，遇到引号切换状态。
     */
    private fun collectQuoteBoundaries(text: String, cuts: MutableList<IntArray>) {
        var inQuote = false
        for (i in text.indices) {
            val c = text[i]
            val isOpenQuote = (c == CN_OPEN_QUOTE_LEFT || c == CN_CORNER_LEFT)
            val isCloseQuote = (c == CN_OPEN_QUOTE_RIGHT || c == CN_CORNER_RIGHT)
            if (isOpenQuote) {
                if (!inQuote) {
                    cuts.add(intArrayOf(i, 1))  // 1 = 开引号前
                    inQuote = true
                }
            } else if (isCloseQuote) {
                if (inQuote) {
                    cuts.add(intArrayOf(i + 1, 2))  // 2 = 闭引号后
                    inQuote = false
                }
            }
        }
    }

    /**
     * 收集"XX 道：" / "XX 说：" 等冒号格式边界（在 m.range.last + 1 处切断）。
     */
    private fun collectSpeakerColonBoundaries(text: String, cuts: MutableList<IntArray>) {
        for (match in SPEAKER_COLON_BOUNDARY.findAll(text)) {
            val cutAt = match.range.last + 1
            val exists = cuts.any { Math.abs(it[0] - cutAt) <= 2 }
            if (!exists) {
                cuts.add(intArrayOf(cutAt, 4))  // 4 = speaker-colon
            }
        }
    }

    private fun collectMentalBoundaries(text: String, cuts: MutableList<IntArray>) {
        for (match in MENTAL_BOUNDARY.findAll(text)) {
            val cutAt = match.range.last + 1
            val exists = cuts.any { Math.abs(it[0] - cutAt) <= 2 }
            if (!exists) {
                cuts.add(intArrayOf(cutAt, 5))  // 5 = mental
            }
        }
    }
}
