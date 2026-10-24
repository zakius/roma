package roma.core

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.MMap
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.dictionary.BinaryDictionary
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
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
    /** "classical" (β = b, no accents) or "elot" (ELOT 743, modern: β = v). */
    val greek: String = "classical",
    /** "ja" or "zh": what Han text is when neither it nor its sibling fields hold kana. */
    val hanDefault: String = "ja",
)

/** A user correction. Scope "text" applies everywhere, "song" and "artist" only to strings of that song or artist (id). */
@Serializable
class Override(val scope: String, val id: String, val original: String, val latin: String)

/**
 * [dictionary] supplies SudachiDict's system_small.dic, memory-mapped, little-endian. It is called once, on the first
 * Japanese string (or [warmUp]). By default it maps the file named by the system property roma.sudachi (the JVM tests).
 */
class Romanizer(
    private val icu: Icu,
    var settings: RomanizeSettings = RomanizeSettings(),
    private val dictionary: () -> ByteBuffer = { MMap.map(System.getProperty("roma.sudachi") ?: error("roma.sudachi: path to system_small.dic")) },
) {
    var overrides: List<Override> = emptyList()

    /**
     * Romanizes s, honouring overrides. Siblings are the other fields of the same item (artist, album, title).
     * A song or album title passes [titleOf] = true, so "スピカ - Spica" shows its own Latin form.
     */
    fun text(s: String, siblings: List<String> = emptyList(), songId: String? = null, artistId: String? = null, titleOf: Boolean = false): Text {
        val o = overrides.firstOrNull { it.original == s && it.scope == "song" && it.id == songId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "artist" && it.id == artistId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "text" }
        return Text(s, o?.latin ?: (if (titleOf) ownLatin(s) else null) ?: machine(s, siblings))
    }

    /** Title case for names and titles; [sentence] case (only the first letter) for lyric lines. */
    fun machine(s: String, siblings: List<String> = emptyList(), sentence: Boolean = false): String {
        val n = normalize(s)
        if (!hasNonLatin(n)) return n
        return capFirst(n, raw(n, siblings, !sentence))
    }

    private fun raw(n: String, siblings: List<String>, title: Boolean, cyrLang: String = cyrillicLanguage(n)) =
        punctuation(if (isJapanese(n, siblings)) japanese(n, title).joinToString("") { it.second } else other(n, title, cyrLang))

    /**
     * Romanizes a line split into parts (karaoke words), one result per part, so word timings map 1:1.
     * Japanese is read as a whole line, so each reading stays right in context; a token that spans several parts
     * goes to the part where it starts. Lyrics are in sentence case: only the line's first letter is capitalized.
     * [cyrLang] is the Cyrillic language decided for the whole song; by default it is read from this line.
     */
    fun parts(parts: List<String>, siblings: List<String> = emptyList(), songId: String? = null, cyrLang: String? = null): List<String> {
        // Karaoke splits ブル|一|ライト into separate parts, so the long-vowel fix runs again across the joined line
        // (it replaces one character with one, so the parts keep their lengths).
        val joined = longVowelMarks(parts.map(::normalize).joinToString(""))
        val norm = parts.map(::normalize).runningFold(0) { at, p -> at + p.length }.zipWithNext { a, b -> joined.substring(a, b) }
        val line = joined
        val override = overrides.filter { it.scope == "song" && it.id == songId }
        val out: MutableList<String> = if (!isJapanese(line, siblings)) {
            val lang = cyrLang ?: cyrillicLanguage(line)
            norm.map { if (hasNonLatin(it)) raw(it, siblings, false, lang) else it }.toMutableList()
        } else {
            val words = Array(norm.size) { StringBuilder() }
            val starts = norm.runningFold(0) { acc, p -> acc + p.length }
            var pos = 0
            for ((surface, romaji) in japanese(line, false)) {
                val i = (starts.indexOfLast { it <= pos }).coerceIn(0, norm.size - 1)
                words[i].append(romaji)
                pos += surface.length
            }
            words.map { punctuation(it.toString()) }.toMutableList()
        }
        val first = out.indexOfFirst { it.isNotBlank() }
        if (first >= 0) out[first] = capFirst(norm[first], out[first])
        return parts.indices.map { i -> override.firstOrNull { it.original == parts[i].trim() }?.latin ?: out[i] }
    }

    fun isJapanese(s: String, siblings: List<String>): Boolean = when {
        s.any(::isKana) -> true
        siblings.any { sib -> sib.any(::isKana) } -> true
        !hasHan(s) -> false
        else -> settings.hanDefault == "ja"
    }

    // Non-Japanese text: convert each run of one script, keep a space between words. Stylized words stay as they are.
    private fun other(s: String, title: Boolean, cyrLang: String): String {
        val out = StringBuilder()
        for (chunk in Regex("""\s+|\S+""").findAll(s).map { it.value }) {
            if (chunk.isBlank() || stylized(chunk)) {
                out.append(chunk)
                continue
            }
            val cps = chunk.codePoints().toArray()
            var i = 0
            while (i < cps.size) {
                val script = Character.UnicodeScript.of(cps[i])
                var j = i + 1
                // An apostrophe inside a Cyrillic word belongs to it: Ukrainian м'ясо, Belarusian сям'я.
                while (j < cps.size && (Character.UnicodeScript.of(cps[j]) == script ||
                        script == Character.UnicodeScript.CYRILLIC && cps[j].toChar() in APOSTROPHES &&
                        cps.getOrNull(j + 1)?.let { Character.UnicodeScript.of(it) } == script)) j++
                val run = String(cps, i, j - i)
                // A lone Greek or Cyrillic letter between symbols is part of a kaomoji: (´・ω・`)
                val kaomoji = j - i == 1 && i > 0 && j < cps.size && !Character.isLetter(cps[i - 1]) && !Character.isLetter(cps[j])
                val converted = when (script) {
                    Character.UnicodeScript.HAN -> words(chinese(run), title)
                    Character.UnicodeScript.HANGUL -> words(hangul(run), title)
                    Character.UnicodeScript.CYRILLIC -> if (kaomoji) run else cyrillic(run, cyrLang)
                    Character.UnicodeScript.GREEK -> if (kaomoji) run else greek(run)
                    Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> run
                    else -> words(icu.transliterate("Any-Latin", run), title)
                }
                appendWord(out, converted)
                i = j
            }
        }
        return out.toString()
    }

    private fun chinese(s: String): String {
        val pinyin = icu.transliterate("Han-Latin", s)
        return if (settings.chineseTones) pinyin else stripMarks(pinyin)
    }

    // ICU's Greek-Latin is the classical scheme (β = b, η = ē); we drop its marks. UNGEGN is ELOT 743, whose
    // macron below (η = i̱) only serves reversibility.
    fun greek(s: String): String =
        if (settings.greek == "elot") icu.transliterate("Greek-Latin/UNGEGN", s).replace("\u0331", "")
        else stripMarks(icu.transliterate("Greek-Latin", s))

    // ---- Japanese ----

    // A Sudachi tokenizer isn't thread-safe: calls take turns on it (a line takes well under a millisecond).
    private val tokenizer by lazy {
        DictionaryFactory().create(Config.defaultConfig().systemDictionary(BinaryDictionary.loadSystem(dictionary()))).create()
    }

    /** Loads the dictionary now, so the first Japanese string doesn't wait for it. Call off the main thread. */
    fun warmUp() { tokenizer }

    private class Token(val surface: String, val reading: String, val pos: List<String>)

    private fun tokens(s: String): List<Token> {
        val raw = synchronized(tokenizer) {
            // A morpheme's begin/end index the original text; surface() can be rewritten (君(きみ) keeps its yomigana).
            tokenizer.tokenize(Tokenizer.SplitMode.C, s).map { Token(s.substring(it.begin(), it.end()), it.readingForm(), it.partOfSpeech()) }
        }
        // Lyric readings: a run of whole tokens that spells a key takes the table's reading as one noun.
        val out = ArrayList<Token>()
        var i = 0
        while (i < raw.size) {
            var j = i
            var text = ""
            var hit = -1
            while (j < raw.size && text.length < LYRIC_KEY_MAX) {
                text += raw[j++].surface
                if (text in LYRIC_READINGS) hit = j
            }
            if (hit < 0) {
                out.add(raw[i++])
                continue
            }
            val surface = raw.subList(i, hit).joinToString("") { it.surface }
            out.add(Token(surface, LYRIC_READINGS.getValue(surface), if (hit - i == 1) raw[i].pos else NOUN))
            i = hit
        }
        return out
    }

    /** (surface, romaji with its leading space) per token. In [title] case every word but a particle is capitalized. */
    private fun japanese(s: String, title: Boolean): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val tokens = tokens(s)
        var prevWord = false
        var prev: Token? = null
        var sokuon = false // the previous token ended in ッ, so this one's first consonant doubles: dat|ta
        var stray = false
        for ((i, t) in tokens.withIndex()) {
            val surface = t.surface
            val pos = t.pos[0]
            val prevPos = prev?.pos?.get(0)
            val japaneseText = surface.any(::isKana) || hasHan(surface)
            // A kanji the dictionary doesn't know (a simplified Chinese 词 in a credit line) has no kana reading: it goes
            // through the Chinese path below instead of being left in the output. Joined numbers (三) are OOV too, but
            // keep their reading.
            val unknownHan = hasHan(surface) && t.reading.none(::isKana)
            var romaji = when {
                japaneseText && !unknownHan -> kana(spoken(if (t.reading.none(::isKana)) toKatakana(surface) else t.reading, t, tokens.getOrNull(i + 1)))
                hasNonLatin(surface) -> other(surface, title, cyrillicLanguage(surface))
                else -> surface
            }
            if (sokuon && japaneseText) {
                val c = romaji.firstOrNull()
                if (romaji.startsWith("ch")) romaji = "t$romaji" else if (c != null && c.isLetter() && c !in "aiueonāīūēō") romaji = c + romaji
            }
            sokuon = japaneseText && toKatakana(surface).endsWith('ッ')
            // Auxiliaries, suffixes, a prefix, the te-form (and ちゃ じゃ for ては では) and ば attach to their word: "aruita", "aruite",
            // "tabemasu", "okāsan", "sannin", "ikeba", "datte". A subsidiary verb straight after a verb stem glues
            // ("hashiridasu", "ureshikunai" for ない); after the te-form or an adjective it is its own word:
            // "tokete yuku", "tabete iru", "tsuyoku nareru". Contractions such as てる and ちゃう are
            // auxiliaries, so they glue. Auxiliaries that read as words of their own (です, らしい) stay apart, and
            // so do よう and みたい, which Sudachi tags as 形状詞.
            val afterInflected = prevPos == "動詞" || prevPos == "形容詞" || prevPos == "助動詞"
            val glue = pos == "助動詞" && afterInflected && t.pos[4] !in LOOSE_AUX ||
                pos == "接尾辞" || prevPos == "接頭辞" ||
                pos == "動詞" && t.pos[1] == "非自立可能" && prevPos == "動詞" ||
                pos == "形容詞" && t.pos[1] == "非自立可能" && afterInflected ||
                t.pos[1] == "接続助詞" && (surface in setOf("て", "で", "ちゃ", "じゃ") || surface == "ば" && afterInflected) ||
                surface == "って" && prev?.surface == "だ" ||
                prev?.pos?.get(1) == "数詞" && pos == "名詞" || stray
            // A sentence-final particle can't start a phrase: there it is a misread first syllable. Sudachi reads
            // よふかし (夜更かし) as よ|ふかし, so it joins the next word: "Yofukashi no Uta".
            stray = t.pos[1] == "終助詞" && !prevWord && tokens.getOrNull(i + 1)?.surface?.any(::isKana) == true
            // Particles stay lowercase in titles, and so does だ as に, な or で after a na-adjective ("Shizumu Yō ni").
            val particle = pos == "助詞" && !stray || pos == "助動詞" && t.pos[4] == "助動詞-ダ" && surface in setOf("に", "な", "で")
            val isWord = romaji.firstOrNull()?.isLetterOrDigit() == true
            val space = prevWord && isWord && !glue
            if (title && japaneseText && isWord && !(prevWord && glue) && !particle) romaji = capitalize(romaji, false)
            result.add(surface to (if (space) " " else "") + romaji)
            prevWord = romaji.lastOrNull()?.isLetterOrDigit() == true
            prev = t
        }
        return result
    }

    /**
     * Sudachi's reading is the kana spelling (東京 トウキョウ, particle は ハ). Hepburn writes what is said: particles は
     * へ as wa e, and a long o or u as ō ū where the spelling has オウ, オオ or ウウ inside a word (tōkyō, ōkii, kūki).
     * エイ and イイ stay (eiga, ōkii). A verb's final ウ is its ending, not a long vowel (思う omou), except in the
     * volitional (行こう ikō). 何 is nan before t, d and n sounds and as a number (nande, nando), else nani.
     */
    private fun spoken(reading: String, t: Token, next: Token?): String {
        if (t.pos[0] == "助詞") when (t.surface) { "は" -> return "ワ"; "へ" -> return "エ" }
        if (t.surface == "何" && t.pos[0] == "代名詞") return if (next?.reading?.firstOrNull()?.let { it in "タチツテトダヂヅデドナニヌネノ" } == true) "ナン" else "ナニ"
        val verbEnding = t.pos[0] == "動詞" && t.pos[5] != "意志推量形"
        val sb = StringBuilder()
        for ((i, c) in reading.withIndex()) {
            val before = reading.getOrNull(i - 1)
            val long = before != null && (c == 'ウ' && (before in O_ROW || before in U_ROW) || c == 'オ' && before in O_ROW) &&
                !(verbEnding && i == reading.lastIndex)
            sb.append(if (long) 'ー' else c)
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

    /**
     * Russian uses the chosen scheme (English-style, Polish-style, ISO 9). Other languages use their national Latin
     * form, unless ISO 9 is chosen. Whatever a table leaves goes through ICU's Cyrillic-Latin, so no Cyrillic remains.
     */
    fun cyrillic(s: String, lang: String = cyrillicLanguage(s)): String {
        val out = when {
            settings.cyrillic == "iso9" -> table(s, ISO9)
            lang == "ru" && settings.cyrillic == "polish" -> polish(s)
            lang == "ru" -> table(s, ENGLISH)
            lang == "mn" -> runCatching { icu.transliterate("mn-mn_Latn/MNS", s) }.getOrDefault(s)
            else -> national(s, lang)
        }
        if (!hasCyrillic(out)) return out
        val icuOut = icu.transliterate("Cyrillic-Latin", out)
        if (!hasCyrillic(icuOut)) return icuOut
        // ICU skips letters such as Tatar җ and Tajik ҷ: take the letter they are built on (ж, ч), or drop them.
        val based = icuOut.map { c -> if (hasCyrillic(c.toString())) baseLetter(c)?.toString() ?: "" else c.toString() }.joinToString("")
        return icu.transliterate("Cyrillic-Latin", based).filterNot { hasCyrillic(it.toString()) }
    }

    private fun table(s: String, table: Map<Char, String>): String {
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

    // Kazakh: the 2021 Latin alphabet. Ukrainian: the 2010 national transliteration. Belarusian: the 2007 national
    // system. Serbian: Gaj's alphabet. Macedonian: the official romanization. Bulgarian: the 2009 Streamlined System.
    private fun national(s: String, lang: String): String {
        val table = NATIONAL.getValue(lang)
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            val prev = s.getOrNull(i - 1)?.lowercaseChar()
            val next = s.getOrNull(i + 1)?.lowercaseChar()
            val initial = prev == null || !prev.isLetter() && prev !in APOSTROPHES
            if (lower in APOSTROPHES && (lang == "uk" || lang == "be")) continue
            var r = when {
                lang == "uk" && lower in "єїйюя" -> if (initial) UK_INITIAL.getValue(lower) else table.getValue(lower)
                lang == "uk" && lower == 'г' && prev == 'з' -> "gh" // зг: zgh
                lang == "be" && lower in "еёюя" -> {
                    val soft = initial || prev in VOWELS_CYR || prev in APOSTROPHES || prev == 'ь' || prev == 'ў'
                    (if (soft) "j" else "i") + table.getValue(lower)
                }
                lang == "be" && lower == 'ь' -> {
                    // зь ль нь сь ць (and дзь) take an acute: ź ĺ ń ś ć. ь elsewhere is silent.
                    if (prev != null && prev in "злнсц") out.setCharAt(out.length - 1, Normalizer.normalize(out.last() + "\u0301", Normalizer.Form.NFC).single())
                    continue
                }
                lang == "bg" && lower == 'я' && prev == 'и' && (next == null || !next.isLetter()) -> "a" // final ия: ia
                else -> table[lower] ?: c.toString()
            }
            if (c.isUpperCase()) {
                r = capitalize(r, s.getOrNull(i + 1)?.isUpperCase() == true)
                if (lang == "kk" && r.startsWith('I') && lower != 'і') r = "İ" + r.drop(1) // и й: İ, і: I
            }
            out.append(r)
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


    companion object {
        fun isKana(c: Char) = (c in 'ぁ'..'ヺ') || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾝ'
        fun hasHan(s: String) = s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        fun hasNonLatin(s: String) = s.codePoints().anyMatch {
            val sc = Character.UnicodeScript.of(it)
            sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.COMMON && sc != Character.UnicodeScript.INHERITED
        }

        /**
         * The language of a Cyrillic string, from its distinctive letters: "kk", "uk", "be", "sr", "mk", "bg", "mn" or "ru".
         * Bulgarian has no letter of its own, only ъ before a consonant (път, България), which Russian never writes
         * now, and no ы э ё. Anything else is Russian, the safe default.
         */
        fun cyrillicLanguage(s: String): String {
            val t = s.lowercase()
            fun has(letters: String) = t.any { it in letters }
            return when {
                has("әғқңұһ") -> "kk"
                has("өү") -> if (has("і")) "kk" else "mn"
                has("ў") || has("і") && has("ыэё") && !has("иєїґ") -> "be"
                has("єїґі") -> "uk"
                has("ѓќѕ") -> "mk"
                has("ђјљњћџ") -> "sr"
                Regex("ъ[бвгджзйклмнпрстфхцчшщ]").containsMatchIn(t) && !has("ыэё") -> "bg"
                else -> "ru"
            }
        }

        private const val RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        private val ALPHABETS = mapOf(
            "ru" to RU,
            "kk" to RU + "әғқңөұүһі",
            "mn" to RU + "өү",
            "uk" to "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя",
            "be" to "абвгдеёжзійклмнопрстуўфхцчшыьэюя",
            "bg" to "абвгдежзийклмнопрстуфхцчшщъьюя",
            "sr" to "абвгдђежзијклљмнњопрстћуфхцчџш",
            "mk" to "абвгдѓежзѕијклљмнњопрстќуфхцчџш",
        )

        /** True when every Cyrillic letter of s belongs to the language's alphabet. */
        fun cyrillicFits(language: String, s: String): Boolean {
            val alphabet = ALPHABETS[language] ?: return true
            return s.lowercase().none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC && it !in alphabet }
        }

        /**
         * A lyric line's language: the song's, unless the line has a letter that language lacks (a Russian line with ы
         * in a Ukrainian song); then the line's own. Kazakh contains every Russian letter, so Russian lines in a Kazakh
         * song stay Kazakh: the outputs differ little and a correction covers the rest.
         */
        fun cyrillicLanguageOfLine(line: String, song: String) = if (cyrillicFits(song, line)) song else cyrillicLanguage(line)

        private fun hasCyrillic(s: String) = s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }

        private val CYRILLIC_NAMES by lazy { (0x400..0x45F).associateBy { Character.getName(it) } }

        /** "CYRILLIC SMALL LETTER ZHE WITH DESCENDER" → ж; "… BASHKIR KA" → к. Null if no basic letter fits. */
        private fun baseLetter(c: Char): Char? {
            val name = Character.getName(c.code) ?: return null
            val case = if (c.isUpperCase()) "CAPITAL" else "SMALL"
            val letter = name.substringAfter("LETTER ").substringBefore(" WITH ")
            return (CYRILLIC_NAMES["CYRILLIC $case LETTER $letter"] ?: CYRILLIC_NAMES["CYRILLIC $case LETTER ${letter.substringAfterLast(' ')}"])?.toChar()
        }

        fun stripMarks(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        /**
         * NFKC, but only on the half-width and full-width forms block: ＬＯＶＥ → LOVE, ｱｲ → アイ, ～ → ~.
         * Full NFKC would also change Latin text (² → 2, ﬁ → fi) and kaomoji (´ → space + accent).
         * A full-width parenthesis carries its own spacing, so it gets a space next to a word: テトリス（feat. X）.
         */
        fun normalize(s: String) = Regex("[\\uFF00-\\uFFEF]+").replace(s.replace(Regex("(?<=\\S)（"), " （").replace(Regex("）(?=[^\\s)）])"), "） ")) {
            Normalizer.normalize(it.value, Normalizer.Form.NFKC)
        }.let(::longVowelMarks)

        /**
         * Lyrics typed on Chinese keyboards often write the katakana long-vowel mark ー as the kanji 一 or a dash:
         * ブル一ライト. After katakana, and not before a kanji (カード一枚 is "one card"), those mean ー.
         */
        fun longVowelMarks(s: String) = s
            .replace(Regex("(?<=[ァ-ヺ])[一—―－‐](?=[ァ-ヺ]|[^\\p{IsHan}]|$)"), "ー")
            .replace(Regex("(?<=[ァ-ヺ])-(?=[ァ-ヺ])"), "ー")

        /** "スピカ - Spica" → "Spica": the Latin form a title carries after " - ", " / " or in parentheses. */
        fun ownLatin(s: String): String? {
            val n = normalize(s)
            val m = Regex("""^(.+?) (?:-|/) (.+)$""").find(n) ?: Regex("""^(.+?)\s*\(([^()]+)\)$""").find(n) ?: return null
            val (left, right) = m.destructured
            // The tag check skips the Latin part's own brackets: "すずめ - Suzume (feat. Toaka)" is still "Suzume (feat. Toaka)".
            val tagged = VERSION_TAG.containsMatchIn(right.replace(Regex("""\(.*?\)"""), ""))
            if (!hasNonLatin(left) || hasNonLatin(right) || right.none { it.isLetter() } || tagged) return null
            return right.trim()
        }

        // ponytail: a word list for version tags, so "(Ballade Ver.)" and "(feat. X)" aren't taken for a translation.
        private val VERSION_TAG = Regex("""(?i)\b(ver|version|size|mix|remix|live|edit|acoustic|instrumental|inst|cover|feat|ft|prod|from|op|ed|opening|ending|theme|remaster|remastered|mv|short|full|tv|demo|karaoke|off vocal)\b""")

        /** A word mixing Latin with Greek or Cyrillic letters is stylized (ΛRCTIC, ЯOCK, μ's): keep it. */
        private fun stylized(word: String): Boolean {
            val scripts = word.codePoints().filter(Character::isLetter).toArray().map { Character.UnicodeScript.of(it) }.toSet()
            return Character.UnicodeScript.LATIN in scripts &&
                (Character.UnicodeScript.GREEK in scripts || Character.UnicodeScript.CYRILLIC in scripts)
        }

        // 、。「」『』・～ as Latin punctuation. ・ becomes a space only between words, so (´・ω・`) keeps it.
        private fun punctuation(s: String): String = s
            .replace(Regex("、(?=\\p{L})"), ", ").replace('、', ',')
            .replace(Regex("。(?=\\p{L})"), ". ").replace('。', '.')
            .replace(Regex("(?<=[\\p{L}\\p{N}])[「『]"), " \"").replace(Regex("[」』](?=[\\p{L}\\p{N}])"), "\" ").replace(Regex("[「」『』]"), "\"")
            .replace(Regex("(?<=\\p{L})・(?=\\p{L})"), " ")
            .replace('〜', '~').replace('～', '~')

        /** Capitalizes each space-separated word: Korean, Chinese (one word per syllable) and other caseless scripts. */
        private fun words(s: String, title: Boolean) = if (title) s.split(' ').joinToString(" ") { capitalize(it, false) } else s

        // The first word is always capitalized (βίος → "Bios"), unless it was Latin already or stayed as it was (ω in a kaomoji).
        private fun capFirst(original: String, latin: String): String {
            val cp = original.codePoints().filter(Character::isLetter).findFirst().orElse(-1)
            val out = latin.firstOrNull { it.isLetter() } ?: return latin
            if (cp < 0 || Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN || Character.UnicodeScript.of(out.code) != Character.UnicodeScript.LATIN) return latin
            return capitalize(latin, false)
        }

        // Uppercases the first letter, wherever it is: "~anata" → "~Anata".
        private fun capitalize(r: String, allCaps: Boolean): String {
            if (allCaps) return r.uppercase()
            val i = r.indexOfFirst { it.isLetter() }
            return if (i < 0) r else r.substring(0, i) + r[i].uppercase() + r.substring(i + 1)
        }

        private fun appendWord(out: StringBuilder, word: String) {
            if (out.isNotEmpty() && word.isNotEmpty() && out.last().isLetterOrDigit() && word.first().isLetterOrDigit()) out.append(' ')
            out.append(word)
        }

        // Auxiliaries (by conjugation type) that stay separate words after a verb: "iku deshō", "kuru rashii".
        private val LOOSE_AUX = setOf("助動詞-デス", "助動詞-ラシイ", "助動詞-ダ")
        private val NOUN = listOf("名詞", "普通名詞", "一般", "*", "*", "*")
        private const val O_ROW = "オコソトノホモヨロゴゾドボポョォ"
        private const val U_ROW = "ウクスツヌフムユルグズヅブプュゥ"

        /**
         * Readings Sudachi gets wrong for songs, as kana with ー for a long vowel. A key matches a run of whole tokens.
         * Sudachi gives the formal or written reading (明日 アス, 私 ワタクシ, 一度 ヒトタビ, 言う ユウ); lyrics sing
         * the everyday one. Its small dictionary splits the family words (母|さん reads ハハサン) and number + counter
         * pairs that change sound (一|回 イチカイ). お before 母さん is a prefix and glues, so "okāsan".
         */
        private val LYRIC_READINGS = mapOf(
            "明日" to "アシタ", "私" to "ワタシ", "一度" to "イチド", "一回" to "イッカイ", "一日" to "イチニチ", "一歩" to "イッポ", "言う" to "イウ",
            "母さん" to "カーサン", "父さん" to "トーサン", "兄さん" to "ニイサン", "姉さん" to "ネーサン",
            "母ちゃん" to "カーチャン", "父ちゃん" to "トーチャン", "兄ちゃん" to "ニイチャン", "姉ちゃん" to "ネーチャン",
        )
        private val LYRIC_KEY_MAX = LYRIC_READINGS.keys.maxOf { it.length }

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
        private const val APOSTROPHES = "'’ʼ"
        private fun cyr(letters: String, vararg latin: String) = letters.withIndex().associate { (i, c) -> c to latin[i] }
        private const val CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґў"
        private val ENGLISH = cyr(CYR, "a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "yi", "ye", "g", "w")
        private val ISO9 = cyr(CYR, "a", "b", "v", "g", "d", "e", "ë", "ž", "z", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "c", "č", "š", "ŝ", "ʺ", "y", "ʹ", "è", "û", "â", "ì", "ï", "ê", "g̀", "ŭ")
        private val NATIONAL = mapOf(
            "kk" to cyr("абвгдеёжзийклмнопрстуфхцчшщъыьэюяәғқңөұүһі", "a", "b", "v", "g", "d", "e", "io", "j", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "tş", "ş", "ştş", "", "y", "", "e", "iu", "ia", "ä", "ğ", "q", "ñ", "ö", "ū", "ü", "h", "ı"),
            "uk" to cyr("абвгґдеєжзиіїйклмнопрстуфхцчшщьюя", "a", "b", "v", "h", "g", "d", "e", "ie", "zh", "z", "y", "i", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "iu", "ia"),
            // е ё ю я are j or i plus this vowel.
            "be" to cyr("абвгґдеёжзійклмнопрстуўфхцчшыэюя", "a", "b", "v", "h", "g", "d", "e", "o", "ž", "z", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "ŭ", "f", "ch", "c", "č", "š", "y", "e", "u", "a"),
            "sr" to cyr("абвгдђежзијклљмнњопрстћуфхцчџш", "a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š"),
            "mk" to cyr("абвгдѓежзѕијклљмнњопрстќуфхцчџш", "a", "b", "v", "g", "d", "gj", "e", "ž", "z", "dz", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s", "t", "kj", "u", "f", "h", "c", "č", "dž", "š"),
            "bg" to cyr("абвгдежзийклмнопрстуфхцчшщъьюя", "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sht", "a", "y", "yu", "ya"),
        )
        private val UK_INITIAL = mapOf('є' to "ye", 'ї' to "yi", 'й' to "y", 'ю' to "yu", 'я' to "ya")
        private val POLISH = cyr(CYR, "a", "b", "w", "g", "d", "e", "io", "ż", "z", "i", "j", "k", "ł", "m", "n", "o", "p", "r", "s", "t", "u", "f", "ch", "c", "cz", "sz", "szcz", "", "y", "", "e", "iu", "ia", "i", "ji", "je", "g", "u")
    }
}
