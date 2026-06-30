package com.xbot.android.voice

/**
 * 汉字 → 带声调拼音 转换（对应 Flutter 用 lpinyin 做的部分）。
 *
 * 完整 lpinyin 字典太大不便内联；这里内置常用字（覆盖典型唤醒词 + 常见用字），
 * 未命中时调用方应直接传预计算好的 token 行（[buildKeywordLine]）。
 * 声调标记格式与 sherpa-onnx tokens.txt 一致（ā á ǎ à 等）。
 */
object Pinyin {
    /** 常用字 → 带声调拼音（声调标在主要元音上）。 */
    private val dict: Map<Char, String> = mapOf(
        // 唤醒词「你好小白」及常见字
        '你' to "nǐ", '好' to "hǎo", '小' to "xiǎo", '白' to "bái",
        // 打招呼/称谓
        '嗨' to "hāi", '哈' to "hā", '喂' to "wèi", '嘿' to "hēi",
        '狗' to "gǒu", '蛋' to "dàn", '宝' to "bǎo", '贝' to "bèi",
        '主' to "zhǔ", '人' to "rén",
        // 数字
        '一' to "yī", '二' to "èr", '三' to "sān", '四' to "sì", '五' to "wǔ",
        '六' to "liù", '七' to "qī", '八' to "bā", '九' to "jiǔ", '十' to "shí",
        // 常见
        '是' to "shì", '的' to "de", '了' to "le", '在' to "zài", '我' to "wǒ",
        '吗' to "ma", '啊' to "a", '吧' to "ba", '呢' to "ne",
    )

    /** 声母表（长在前避免 zh/ch/sh 被 z/c/s 误匹配）。 */
    private val initials = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n",
        "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    /** 中文 → 带声调拼音音节列表（空格分隔未用，这里直接返回音节列表）。 */
    fun toPinyinSyllables(text: String): List<String>? {
        val out = ArrayList<String>(text.length)
        for (ch in text) {
            val py = dict[ch] ?: return null // 未命中返回 null
            out.add(py)
        }
        return out
    }

    /**
     * 把一个拼音音节（如 "xiǎo"）拆成 [声母, 韵母]（如 ["x","iǎo"]）。
     * 零声母音节（如 "ài"）整音节作为单 token。
     * 对应 Flutter _splitSyllable。
     */
    fun splitSyllable(syllable: String): List<String> {
        for (ini in initials) {
            if (syllable.startsWith(ini)) {
                val finalPart = syllable.substring(ini.length)
                return if (finalPart.isNotEmpty()) listOf(ini, finalPart) else listOf(ini)
            }
        }
        return listOf(syllable)
    }

    /**
     * 构造 sherpa-onnx 内联关键词行：形如 "n ǐ h ǎo x iǎo b ái @你好小白"。
     * 多个关键词用 "/" 分隔。
     * 返回 null 表示字典未覆盖（调用方应回退到预计算行）。
     */
    fun buildKeywordLine(keyword: String): String? {
        val syllables = toPinyinSyllables(keyword) ?: return null
        val tokens = ArrayList<String>()
        for (syl in syllables) tokens.addAll(splitSyllable(syl))
        return "${tokens.joinToString(" ")} @$keyword"
    }
}
