package eu.kanade.presentation.browse.util

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * 供索引侧边栏滑动期间使用：把标题转换为“首字母 + 小写全文”的排序键。
 *
 * 规则：
 *  - 汉字：取拼音首字母（via pinyin4j）
 *  - 平假名/片假名：取罗马音首字母（内置映射）
 *  - 谚文：取初声罗马音首字母（按 Hangul 分解公式）
 *  - 拉丁字母：原字母大写；数字/符号：'#'
 */
private val kanaToRomaji = buildMap {
    // 平假名
    val hiragana = listOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "wi", 'ゑ' to "we", 'を' to "wo", 'ん' to "n",
    )
    // 片假名
    val katakana = listOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "wi", 'ヱ' to "we", 'ヲ' to "wo", 'ン' to "n",
    )
    putAll(hiragana)
    putAll(katakana)
}

// 谚文初声（index 0..18）→ 罗马音
private val hangulInitialRomaji = listOf(
    "g", "gg", "n", "d", "dd", "r", "m", "b", "bb", "s", "ss",
    "", // ㅇ ：无初声时取元音
    "j", "jj", "ch", "k", "t", "p", "h",
)

// 谚文元音（index 0..20）→ 罗马音
private val hangulVowelRomaji = listOf(
    "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae",
    "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
)

// 拼音首字母缓存（避免滑动时重复调用 pinyin4j）
private val pinyinInitialCache = HashMap<Char, Char>()

/**
 * 返回 [ch] 对应的排序首字母（A-Z 或 '#'）。
 */
fun initialOf(ch: Char): Char {
    val code = ch.code
    return when {
        // 拉丁字母
        ch in 'a'..'z' -> ch.uppercaseChar()
        ch in 'A'..'Z' -> ch
        // 平假名/片假名
        code in 0x3040..0x309F || code in 0x30A0..0x30FF -> {
            kanaToRomaji[ch]?.firstOrNull()?.uppercaseChar() ?: '#'
        }
        // 谚文
        code in 0xAC00..0xD7A3 -> {
            val syllable = code - 0xAC00
            val initialIndex = syllable / 588
            if (initialIndex == 11) { // ㅇ：取元音
                val vowelIndex = (syllable % 588) / 28
                hangulVowelRomaji[vowelIndex].firstOrNull()?.uppercaseChar() ?: '#'
            } else {
                hangulInitialRomaji[initialIndex].firstOrNull()?.uppercaseChar() ?: '#'
            }
        }
        // 汉字（含常用于日文的汉字）→ 拼音首字母
        code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> {
            pinyinInitialCache.getOrPut(ch) {
                PinyinHelper.toHanyuPinyinStringArray(ch)
                    ?.firstOrNull()
                    ?.first()
                    ?.uppercaseChar()
                    ?: '#'
            }
        }
        // 其他（数字、符号等）
        else -> '#'
    }
}

/**
 * 左括号集合：标题以这些字符开头时，排序依据取第二个字符。
 */
private val openingBrackets = setOf(
    '（', '(', '【', '[', '｛', '{', '「', '《', '<', '＜',
    '［', '〖', '『', '〈', '〔', '«', '｢', '〔',
)

/**
 * 取用于排序的字符：跳过开头空白与左括号，返回首个有效字符（无则 '#'）。
 */
private fun firstSortChar(title: String): Char {
    for (c in title) {
        if (!c.isWhitespace() && c !in openingBrackets) return c
    }
    return '#'
}

/**
 * 返回标题对应的排序首字母（A-Z 或 '#'），即 [compareByInitial] 所用的首段排序键。
 */
fun initialOfTitle(title: String): Char = initialOf(firstSortChar(title))

/**
 * 返回"先按首字母、再按小写全文"的比较器，供滑动期间临时排序使用。
 */
fun <T> compareByInitial(selector: (T) -> String): Comparator<T> {
    return compareBy(
        { initialOf(firstSortChar(selector(it))) },
        { selector(it).lowercase() },
    )
}