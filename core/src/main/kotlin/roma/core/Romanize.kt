package roma.core

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** A string as it came from the service, next to its Latin form. Both are equal when the original is already Latin. */
@Serializable
class Text(val original: String, val latin: String) {
    val isLatin get() = original == latin
}

/** ICU transliteration, supplied by the platform: android.icu on Android, ICU4J on a desktop JVM. */
fun interface Icu {
    fun transliterate(id: String, text: String): String
}

class RomanizeSettings(
    val japaneseMacrons: Boolean = true,
    val chineseTones: Boolean = true,
    /** "english", "polish" or "iso9". */
    val cyrillic: String = "english",
    /** "ja" or "zh": what Han text is when neither it nor its sibling fields hold kana. */
    val hanDefault: String = "ja",
)

/** A user correction. Scope "text" applies everywhere, "song" and "artist" only to strings of that song or artist (id). */
@Serializable
class Override(val scope: String, val id: String, val original: String, val latin: String)

class Romanizer(private val icu: Icu, var settings: RomanizeSettings = RomanizeSettings()) {
    var overrides: List<Override> = emptyList()

    /** Romanizes s, honouring overrides. Siblings are the other fields of the same item (artist, album, title). */
    fun text(s: String, siblings: List<String> = emptyList(), songId: String? = null, artistId: String? = null): Text {
        val o = overrides.firstOrNull { it.original == s && it.scope == "song" && it.id == songId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "artist" && it.id == artistId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "text" }
        return Text(s, o?.latin ?: machine(s, siblings))
    }

    fun machine(s: String, siblings: List<String> = emptyList()): String {
        if (!hasNonLatin(s)) return s
        return if (isJapanese(s, siblings)) japanese(s).joinToString("") { it.second } else other(s)
    }

    /**
     * Romanizes a line split into parts (karaoke words), one result per part, so word timings map 1:1.
     * Japanese is read as a whole line, so each reading stays right in context; a token that spans several parts
     * goes to the part where it starts.
     */
    fun parts(parts: List<String>, siblings: List<String> = emptyList(), songId: String? = null): List<String> {
        val line = parts.joinToString("")
        val override = overrides.filter { it.scope == "song" && it.id == songId }
        if (!isJapanese(line, siblings)) {
            return parts.map { p -> override.firstOrNull { it.original == p.trim() }?.latin ?: machine(p, siblings) }
        }
        val out = Array(parts.size) { StringBuilder() }
        val starts = parts.runningFold(0) { acc, p -> acc + p.length }
        var pos = 0
        for ((surface, romaji) in japanese(line)) {
            val i = (starts.indexOfLast { it <= pos }).coerceIn(0, parts.size - 1)
            out[i].append(romaji)
            pos += surface.length
        }
        return parts.indices.map { i -> override.firstOrNull { it.original == parts[i].trim() }?.latin ?: out[i].toString() }
    }

    fun isJapanese(s: String, siblings: List<String>): Boolean = when {
        s.any(::isKana) -> true
        siblings.any { sib -> sib.any(::isKana) } -> true
        s.none(::isHan) -> false
        else -> settings.hanDefault == "ja"
    }

    // Non-Japanese text: convert each run of one script, keep a space between words.
    private fun other(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val script = scriptOf(s[i])
            var j = i + 1
            while (j < s.length && scriptOf(s[j]) == script) j++
            val run = s.substring(i, j)
            val converted = when (script) {
                Character.UnicodeScript.HAN -> chinese(run)
                Character.UnicodeScript.HANGUL -> hangul(run)
                Character.UnicodeScript.CYRILLIC -> cyrillic(run)
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> run
                else -> icu.transliterate("Any-Latin", run)
            }
            appendWord(out, converted)
            i = j
        }
        return out.toString()
    }

    private fun chinese(s: String): String {
        val pinyin = icu.transliterate("Han-Latin", s)
        return if (settings.chineseTones) pinyin else stripMarks(pinyin)
    }

    // ---- Japanese ----

    private val tokenizer by lazy { Tokenizer() }

    /** (surface, romaji with its leading space) per token. */
    private fun japanese(s: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var prevWord = false
        var prevPos = ""
        var sokuon = false // the previous token ended in ッ, so this one's first consonant doubles: dat|ta
        for (t in tokenizer.tokenize(s)) {
            val surface = t.surface
            val japaneseText = surface.any { isKana(it) || isHan(it) }
            var romaji = when {
                japaneseText -> {
                    val reading = if (t.reading == "*") toKatakana(surface) else t.reading
                    kana(spoken(reading, t.pronunciation))
                }
                hasNonLatin(surface) -> other(surface)
                else -> surface
            }
            if (sokuon && japaneseText) {
                val c = romaji.firstOrNull()
                if (romaji.startsWith("ch")) romaji = "t$romaji" else if (c != null && c.isLetter() && c !in "aiueonāīūēō") romaji = c + romaji
            }
            sokuon = japaneseText && toKatakana(surface).endsWith('ッ')
            // Short auxiliaries, suffixes and the te-form attach to the word before them: "aruita", "aruite".
            // Longer auxiliaries such as よう and みたい stay separate words.
            val aux = t.partOfSpeechLevel1 == "助動詞" && surface.length <= 2 && surface !in LOOSE_AUX &&
                prevPos in setOf("動詞", "形容詞", "助動詞")
            val glue = aux || t.partOfSpeechLevel2 == "接尾" ||
                t.partOfSpeechLevel2 == "非自立" && t.partOfSpeechLevel1 == "動詞" ||
                (t.partOfSpeechLevel2 == "接続助詞" && (surface == "て" || surface == "で"))
            val isWord = romaji.firstOrNull()?.isLetterOrDigit() == true
            val space = prevWord && isWord && !glue
            result.add(surface to (if (space) " " else "") + romaji)
            prevWord = romaji.lastOrNull()?.isLetterOrDigit() == true
            prevPos = t.partOfSpeechLevel1
        }
        return result
    }

    // IPADIC's pronunciation marks long vowels (トーキョー) and particles (ワ); its reading keeps the spelling (トウキョウ).
    // Take the long vowel only where the spelling has ウ or オ, so 映画 stays "eiga" while 東京 becomes "tōkyō".
    private fun spoken(reading: String, pron: String?): String {
        if (pron == null || pron == "*" || pron.length != reading.length) return reading
        val sb = StringBuilder()
        for (i in reading.indices) {
            val r = reading[i]
            val p = pron[i]
            sb.append(
                when {
                    p == 'ー' && (r == 'ウ' || r == 'オ') -> 'ー'
                    r == 'ハ' && p == 'ワ' || r == 'ヘ' && p == 'エ' || r == 'ヲ' && p == 'オ' -> p
                    else -> r
                },
            )
        }
        return sb.toString()
    }

    /** Katakana (or hiragana) to Modified Hepburn. */
    fun kana(input: String): String {
        val s = toKatakana(input)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == 'ッ' -> {
                    val following = syllable(s, i + 1).first
                    if (following.startsWith("ch")) out.append('t') else if (following.firstOrNull()?.let { it !in "aiueon" } == true) out.append(following[0])
                    i++
                }
                c == 'ン' -> {
                    val following = syllable(s, i + 1).first
                    out.append(if (following.firstOrNull()?.let { it in "aiueoy" } == true) "n'" else "n")
                    i++
                }
                c == 'ー' -> {
                    val last = out.lastOrNull()
                    if (last != null && last in "aiueo") {
                        if (settings.japaneseMacrons) out.setCharAt(out.length - 1, macron(last))
                    } else if (last == null || !isMacron(last)) out.append('-')
                    i++
                }
                else -> {
                    val (romaji, used) = syllable(s, i)
                    out.append(romaji)
                    i += used
                }
            }
        }
        return out.toString()
    }

    private fun syllable(s: String, i: Int): Pair<String, Int> {
        val c = s.getOrNull(i) ?: return "" to 1
        val base = KANA[c] ?: return c.toString() to 1
        val n = s.getOrNull(i + 1)
        if (n != null && n in "ャュョ") {
            val v = KANA[n]!!.drop(1) // a, u, o
            val stem = base.dropLast(1)
            return (if (stem.endsWith("sh") || stem.endsWith("ch") || stem.endsWith("j")) stem + v else stem + "y" + v) to 2
        }
        if (n != null && n in "ァィゥェォ" && c !in "ァィゥェォャュョッン") {
            val stem = if (c == 'ウ') "w" else base.dropLast(1)
            return stem + KANA[n]!! to 2
        }
        return base to 1
    }

    // ---- Korean: Revised Romanization ----

    fun hangul(s: String): String {
        val out = StringBuilder()
        var pendingInitial: String? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c !in '가'..'힣') {
                out.append(c); i++; pendingInitial = null; continue
            }
            val code = c - '가'
            var initial = INITIAL[code / 588]
            val vowel = VOWEL[(code % 588) / 28]
            val t = code % 28
            if (pendingInitial != null) initial = pendingInitial
            pendingInitial = null
            var coda = CODA[t]
            val nextC = s.getOrNull(i + 1)
            if (nextC != null && nextC in '가'..'힣' && t != 0) {
                val nextL = (nextC - '가') / 588
                when {
                    nextL == 11 && t != 21 -> { // ㅇ: the final consonant moves to the next syllable
                        coda = LIAISON[t].first
                        pendingInitial = LIAISON[t].second
                    }
                    nextL == 2 || nextL == 6 -> { // ㄴ ㅁ: nasalization
                        coda = when (coda) { "k" -> "ng"; "t" -> "n"; "p" -> "m"; else -> coda }
                        if (coda == "l" && nextL == 2) pendingInitial = "l"
                    }
                    nextL == 5 -> { // ㄹ
                        if (coda == "n" || coda == "l") { coda = "l"; pendingInitial = "l" }
                        else { coda = when (coda) { "k" -> "ng"; "t" -> "n"; "p" -> "m"; else -> coda }; pendingInitial = "n" }
                    }
                    (t == 27 || t == 6 || t == 15) && nextL in listOf(0, 3, 12) -> { // ㅎ before ㄱ ㄷ ㅈ: aspiration
                        coda = if (t == 27) "" else CODA[t]
                        pendingInitial = when (nextL) { 0 -> "k"; 3 -> "t"; else -> "ch" }
                    }
                }
            }
            out.append(initial).append(vowel).append(coda)
            i++
        }
        return out.toString()
    }

    // ---- Cyrillic ----

    fun cyrillic(s: String): String {
        val table = when (settings.cyrillic) {
            "polish" -> return polish(s)
            "iso9" -> ISO9
            else -> ENGLISH
        }
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            var r = table[lower] ?: c.toString()
            // English style writes е as "ye" at the start of a word and after a vowel.
            if (table === ENGLISH && lower == 'е' && (i == 0 || s[i - 1].lowercaseChar() in VOWELS_CYR || !s[i - 1].isLetter())) r = "ye"
            out.append(if (c.isUpperCase()) capitalize(r, s.getOrNull(i + 1)?.isUpperCase() == true) else r)
        }
        return out.toString()
    }

    private fun polish(s: String): String {
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            val prev = s.getOrNull(i - 1)?.lowercaseChar()
            val next = s.getOrNull(i + 1)?.lowercaseChar()
            val afterConsonant = prev != null && prev.isLetter() && prev !in VOWELS_CYR && prev != 'ь' && prev != 'ъ'
            val hard = prev != null && prev in "жшцчщ"
            var r = when (lower) {
                'е' -> if (!afterConsonant) "je" else if (hard) "e" else "ie"
                'ё' -> if (!afterConsonant) "jo" else if (hard) "o" else "io"
                'ю' -> if (!afterConsonant) "ju" else "iu"
                'я' -> if (!afterConsonant) "ja" else "ia"
                'л' -> if (next != null && next in "ьеёюяи") "l" else "ł"
                'ь' -> when (prev) { 'н' -> "́"; 'с' -> "́"; 'з' -> "́"; else -> "" }
                else -> POLISH[lower] ?: c.toString()
            }
            if (r == "́") { // нь сь зь: ń ś ź
                val last = out.last()
                out.setLength(out.length - 1)
                out.append(Normalizer.normalize(last.toString() + "́", Normalizer.Form.NFC))
                continue
            }
            if (c.isUpperCase()) r = capitalize(r, next?.isUpperCase() == true && s[i + 1].isUpperCase())
            out.append(r)
        }
        return out.toString()
    }

    private fun capitalize(r: String, allCaps: Boolean) = if (allCaps) r.uppercase() else r.replaceFirstChar { it.uppercaseChar() }

    companion object {
        fun isKana(c: Char) = (c in 'ぁ'..'ヺ') || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾝ'
        fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        fun hasNonLatin(s: String) = s.any {
            val sc = Character.UnicodeScript.of(it.code)
            sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.COMMON && sc != Character.UnicodeScript.INHERITED
        }

        fun stripMarks(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        private fun scriptOf(c: Char) = Character.UnicodeScript.of(c.code)

        private fun appendWord(out: StringBuilder, word: String) {
            if (out.isNotEmpty() && word.isNotEmpty() && out.last().isLetterOrDigit() && word.first().isLetterOrDigit()) out.append(' ')
            out.append(word)
        }

        private val LOOSE_AUX = setOf("よう", "様", "そう", "らしい")

        private fun toKatakana(s: String) = buildString { for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }
        private fun macron(c: Char) = "āīūēō"["aiueo".indexOf(c)]
        private fun isMacron(c: Char) = c in "āīūēō"

        private val KANA: Map<Char, String> = run {
            val m = HashMap<Char, String>()
            fun row(chars: String, vararg romaji: String) = chars.forEachIndexed { i, c -> m[c] = romaji[i] }
            row("アイウエオ", "a", "i", "u", "e", "o")
            row("カキクケコ", "ka", "ki", "ku", "ke", "ko")
            row("サシスセソ", "sa", "shi", "su", "se", "so")
            row("タチツテト", "ta", "chi", "tsu", "te", "to")
            row("ナニヌネノ", "na", "ni", "nu", "ne", "no")
            row("ハヒフヘホ", "ha", "hi", "fu", "he", "ho")
            row("マミムメモ", "ma", "mi", "mu", "me", "mo")
            row("ヤユヨ", "ya", "yu", "yo")
            row("ラリルレロ", "ra", "ri", "ru", "re", "ro")
            row("ワヰヱヲ", "wa", "i", "e", "o")
            row("ガギグゲゴ", "ga", "gi", "gu", "ge", "go")
            row("ザジズゼゾ", "za", "ji", "zu", "ze", "zo")
            row("ダヂヅデド", "da", "ji", "zu", "de", "do")
            row("バビブベボ", "ba", "bi", "bu", "be", "bo")
            row("パピプペポ", "pa", "pi", "pu", "pe", "po")
            row("ァィゥェォ", "a", "i", "u", "e", "o")
            row("ャュョヮヵヶヴ", "ya", "yu", "yo", "wa", "ka", "ke", "vu")
            m
        }

        private val INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        private val VOWEL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        private val CODA = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
        // Final consonant before a silent ㅇ: what stays, what becomes the next syllable's initial.
        private val LIAISON = listOf(
            "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "n" to "", "" to "d", "" to "r", "l" to "g",
            "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "l" to "", "" to "m", "" to "b", "p" to "s", "" to "s",
            "" to "ss", "ng" to "", "" to "j", "" to "ch", "" to "k", "" to "t", "" to "p", "" to "",
        )

        private const val VOWELS_CYR = "аеёиоуыэюяіїєaeiou"
        private fun cyr(letters: String, vararg latin: String) = letters.withIndex().associate { (i, c) -> c to latin[i] }
        private const val CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґў"
        private val ENGLISH = cyr(CYR, "a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "yi", "ye", "g", "w")
        private val ISO9 = cyr(CYR, "a", "b", "v", "g", "d", "e", "ë", "ž", "z", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "c", "č", "š", "ŝ", "ʺ", "y", "ʹ", "è", "û", "â", "ì", "ï", "ê", "g̀", "ŭ")
        private val POLISH = cyr(CYR, "a", "b", "w", "g", "d", "e", "io", "ż", "z", "i", "j", "k", "ł", "m", "n", "o", "p", "r", "s", "t", "u", "f", "ch", "c", "cz", "sz", "szcz", "", "y", "", "e", "iu", "ia", "i", "ji", "je", "g", "u")
    }
}
