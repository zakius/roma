package roma.core

import com.ibm.icu.text.Transliterator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val jvmIcu = Icu { id, text -> Transliterator.getInstance(id).transliterate(text) }

class RomanizeTest {
    private val r = Romanizer(jvmIcu)

    @Test fun japanese() {
        assertEquals("Yoru ni Kakeru", r.machine("夜に駆ける"))
        assertEquals("Kimi no Koe", r.machine("君の声"))
        assertEquals("Kimi no Na wa", r.machine("君の名は"))
        assertEquals("Tōkyō", r.machine("東京"))
        assertEquals("Eiga", r.machine("映画"))
        assertEquals("Watashi wa", r.machine("私は"))
        assertEquals("Kōhī", r.machine("コーヒー"))
        assertEquals("Shizumu Yō ni", r.machine("沈むように"))
        assertEquals("Sayonara dake Datta", r.machine("さよならだけだった"))
        assertEquals("Subete ga Wakatta", r.machine("全てが分かった"))
        assertEquals("matcha", r.kana("マッチャ"))
        assertEquals("kon'ya", r.kana("コンヤ"))
        assertEquals("fan", r.kana("ファン"))
    }

    @Test fun sentenceCase() {
        // Lyric lines: only the first letter is capitalized.
        assertEquals("Shizumu yō ni", r.machine("沈むように", sentence = true))
        assertEquals("Sayonara dake datta", r.machine("さよならだけだった", sentence = true))
        assertEquals("Subete ga wakatta", r.machine("全てが分かった", sentence = true))
        assertEquals("No uta", r.machine("の歌", sentence = true))
    }

    @Test fun titleCase() {
        assertEquals("Departures ~Anata ni Okuru Ai no Uta~", r.machine("Departures ~あなたにおくるアイの歌~"))
        // Latin parts are never changed.
        assertEquals("Bokura no LIVE Kimi to no LIFE", r.machine("ぼくらのLIVE 君とのLIFE"))
        assertEquals("LiSA no Uta (Ballade Ver.)", r.machine("LiSAの歌 (Ballade Ver.)"))
        // Korean and Chinese: every word; Chinese has one word per syllable.
        assertEquals("BTS Bomnal", r.machine("BTS 봄날"))
        assertEquals("Qíng Tiān", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).machine("晴天"))
        // Cyrillic keeps the original's capitals.
        assertEquals("Gruppa krovi", r.machine("Группа крови"))
        assertEquals("Ya lyublyu tebya", r.machine("Я люблю тебя"))
    }

    @Test fun subsidiaryVerbs() {
        assertEquals("Tokete Yuku", r.machine("溶けてゆく"))
        assertEquals("tokete yuku", r.machine("溶けてゆく", sentence = true).lowercase())
        assertEquals("Tabete iru", r.machine("食べている", sentence = true))
        assertEquals("Tabeteru", r.machine("食べてる", sentence = true))
        assertEquals("Tabechau", r.machine("食べちゃう", sentence = true))
    }

    @Test fun mixedScript() {
        // Full-width Latin and half-width kana are normalized first.
        assertEquals("LOVE", r.machine("ＬＯＶＥ"))
        assertEquals("Ai", r.machine("ｱｲ"))
        assertEquals("Kimi ni, Todoke.", r.machine("君に、届け。"))
        assertEquals("Romio Jurietto", r.machine("ロミオ・ジュリエット"))
        assertEquals("Kimi no \"Uta\"", r.machine("君の「歌」"))
        // Code points, not UTF-16 chars: an emoji doesn't make a title non-Latin.
        assertTrue(r.text("Happy 🎉").isLatin)
        assertEquals("Yě Jiā", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).machine("𠮷野家").substringAfter(' '))
    }

    @Test fun stylizedStaysAsIs() {
        assertEquals("(´・ω・`)", r.machine("(´・ω・`)"))
        assertEquals("ΛRCTIC", r.machine("ΛRCTIC"))
        assertEquals("ЯOCK", r.machine("ЯOCK"))
        assertEquals("μ's", r.machine("μ's"))
        assertEquals("Ya i ty", r.machine("Я и ты"))
        assertEquals("Zvyozdy i ya", r.machine("звёзды и я"))
    }

    @Test fun greek() {
        assertEquals("Bios", r.machine("Βίος"))
        assertEquals("Bios", r.machine("βίος"))
        assertEquals("Athena", r.machine("Αθηνα"))
        val elot = Romanizer(jvmIcu, RomanizeSettings(greek = "elot"))
        assertEquals("Víos", elot.machine("βίος"))
        assertEquals("Efcharistó", elot.machine("ευχαριστώ"))
    }

    @Test fun ownLatinForm() {
        assertEquals("Spica", r.text("スピカ - Spica", titleOf = true).latin)
        assertEquals("Idol", r.text("アイドル - Idol", titleOf = true).latin)
        assertEquals("Suzume (feat. Toaka)", r.text("すずめ - Suzume (feat. Toaka)", titleOf = true).latin)
        assertEquals("Yoru ni Kakeru - Live", r.text("夜に駆ける - Live", titleOf = true).latin)
        assertEquals("Silhouette", r.text("シルエット (Silhouette)", titleOf = true).latin)
        // Version tags are not a Latin form.
        assertEquals("Yoru ni Kakeru (Ballade Ver.)", r.text("夜に駆ける (Ballade Ver.)", titleOf = true).latin)
        assertEquals("Tetorisu (feat. Kasane Teto)", r.text("テトリス（feat. Kasane Teto）", titleOf = true).latin)
        // Only titles: an artist name is romanized whole.
        assertEquals("Supika - Spica", r.text("スピカ - Spica").latin)
    }

    @Test fun correctionShownAsTyped() {
        val o = Romanizer(jvmIcu)
        o.overrides = listOf(Override("song", "s1", "夜に駆ける", "yoru ni KAKERU"), Override("song", "s1", "アイドル", "アイドル"))
        assertEquals("yoru ni KAKERU", o.text("夜に駆ける", songId = "s1").latin)
        assertTrue(o.text("アイドル", songId = "s1").isLatin) // "Don't romanize"
    }

    @Test fun noMacrons() {
        val plain = Romanizer(jvmIcu, RomanizeSettings(japaneseMacrons = false))
        assertEquals("Tokyo", plain.machine("東京"))
    }

    @Test fun hanDecision() {
        // Kana in the string or a sibling means Japanese; otherwise the default language decides.
        assertTrue(r.isJapanese("君の声", emptyList()))
        assertTrue(r.isJapanese("紅蓮華", listOf("LiSA", "紅蓮華 (ぐれんげ)")))
        val zh = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        assertFalse(zh.isJapanese("晴天", listOf("周杰伦")))
        assertTrue(zh.isJapanese("晴天", listOf("あいみょん")))
        assertEquals("Qíng Tiān", zh.machine("晴天"))
        assertEquals("Qing Tian", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).machine("晴天"))
    }

    @Test fun korean() {
        assertEquals("Saranghae", r.machine("사랑해"))
        assertEquals("Hangugeo", r.machine("한국어"))
        assertEquals("Gamsahamnida", r.machine("감사합니다"))
        assertEquals("Silla", r.machine("신라"))
        assertEquals("Joahae", r.machine("좋아해"))
        assertEquals("BTS Bomnal", r.machine("BTS 봄날"))
    }

    @Test fun cyrillic() {
        assertEquals("Kino", r.machine("Кино"))
        assertEquals("Zhanna Aguzarova", r.machine("Жанна Агузарова"))
        assertEquals("Yevgeniy", r.machine("Евгений"))
        val pl = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "polish"))
        assertEquals("Jewgienij", pl.machine("Евгений"))
        assertEquals("Szczedrin", pl.machine("Щедрин"))
        assertEquals("Ałła", pl.machine("Алла"))
        assertEquals("Žanna", Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9")).machine("Жанна"))
    }

    @Test fun otherCyrillicLanguages() {
        assertEquals("kk", Romanizer.cyrillicLanguage("Ерлерін елін сүйген"))
        assertEquals("Izı qalğan babalardyñ qasterlı jerım!", r.machine("Ізі қалған бабалардың қастерлі жерім!", sentence = true))
        assertEquals("Erlerın elın süigen", r.machine("Ерлерін елін сүйген", sentence = true))
        assertEquals("İt pen mysyq", r.machine("Ит пен мысық", sentence = true))
        assertEquals("Dobryi vechir, my z Ukrainy", r.machine("Добрий вечір, ми з України", sentence = true))
        assertEquals("Yizhak i zghoda, miaso", r.machine("Їжак і згода, м'ясо", sentence = true))
        assertEquals("Dobraj ranicy, siabry z Bielarusi", r.machine("Добрай раніцы, сябры з Беларусі", sentence = true))
        assertEquals("Jon liubić svaju siamju ŭ Homieli", r.machine("Ён любіць сваю сям'ю ў Гомелі", sentence = true))
        assertEquals("Ljubav i život u Beogradu", r.machine("Љубав и живот у Београду", sentence = true))
        assertEquals("Đorđe, ćerka, džem, njega, LJUBAV", r.machine("Ђорђе, ћерка, џем, њега, ЉУБАВ", sentence = true))
        assertEquals("Gjorgji, kje dojdam", r.machine("Ѓорѓи, ќе дојдам", sentence = true))
        assertEquals("Savsem sam v Balgaria", r.machine("Съвсем сам в България", sentence = true))
        assertEquals("Öglöönii mend", r.machine("Өглөөний мэнд", sentence = true))
        // Russian keeps its schemes; ISO 9 applies to every language.
        assertEquals("ru", Romanizer.cyrillicLanguage("Объявление в подъезде"))
        val iso = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9"))
        assertEquals("Dobrij večìr", iso.machine("Добрий вечір", sentence = true))
        // Lyrics decide once per song: a line without Kazakh letters in a Kazakh song is still Kazakh.
        assertEquals(listOf("Ūly dala", "Erlerın"), r.parts(listOf("Ұлы дала"), cyrLang = "kk") + r.parts(listOf("Ерлерін"), cyrLang = "kk"))
        // Whatever no table covers goes through ICU, so no Cyrillic letter is left (Tatar җ).
        val out = r.machine("Җир, Ӂ, Ԥ, ҷ") + r.machine("Ізі җир")
        assertFalse(out.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }, out)
    }

    @Test fun latinUntouched() {
        assertEquals("Daft Punk", r.machine("Daft Punk"))
        assertEquals("Beyoncé", r.machine("Beyoncé"))
    }

    @Test fun overridesWin() {
        val o = Romanizer(jvmIcu)
        o.overrides = listOf(Override("artist", "UC1", "米津玄師", "Kenshi Yonezu"), Override("text", "", "君の声", "Kimi no Koe"))
        assertEquals("Kenshi Yonezu", o.text("米津玄師", artistId = "UC1").latin)
        assertEquals("Kimi no Koe", o.text("君の声").latin)
        assertEquals(o.machine("米津玄師"), o.text("米津玄師", artistId = "UC2").latin)
    }

    @Test fun partsMapOneToOne() {
        val words = listOf("夜", "に", "駆", "ける")
        val latin = r.parts(words)
        assertEquals(4, latin.size)
        assertEquals("Yoru ni kakeru", latin.joinToString("").trim())
        assertEquals(listOf("Ni", "hao"), Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).parts(listOf("你", "好")))
    }

    @Test fun localSearch() {
        val s = Song("x", r.text("君の声"), listOf(Named(null, r.text("Artist"))))
        assertTrue(matches(s, "kimi no"))
        assertTrue(matches(s, "kiminokoe"))
        assertTrue(matches(s, "君の"))
        assertFalse(matches(s, "tokyo"))
        // Typos, spaces and macrons.
        val y = Song("y", r.text("夜に駆ける"), listOf(Named(null, Text("YOASOBI", "YOASOBI"))))
        assertEquals(1, matchDistance(y, "yoru ni kakru"))
        assertEquals(2, matchDistance(y, "yoasbi yoru ni kakru"))
        assertTrue(matches(Song("t", r.text("東京"), emptyList()), "tokyo"))
        assertTrue(matches(Song("k", Text("Lemon", "Lemon"), listOf(Named(null, Text("米津玄師", "Kenshi Yonezu")))), "yonezy"))
        // Short queries must match exactly, so the list isn't flooded.
        assertFalse(matches(Song("l", Text("Live", "Live"), emptyList()), "love"))
    }

    @Test fun japaneseFromChineseSources() {
        // 一 typed for ー after katakana; a real 一 before a kanji stays; an unknown simplified kanji isn't left as is.
        assertEquals("Me o ubatte burū raito", r.machine("目を奪ってブル一ライト", sentence = true))
        assertEquals("Me o ubatte burū raito", r.machine("目を奪ってブルーライト", sentence = true))
        assertEquals("カード一枚", Romanizer.longVowelMarks("カード一枚"))
        assertFalse(Romanizer.hasNonLatin(r.machine("作词 : R-指定")))
        assertEquals("Terashitette moonlight", r.machine("照らしてってmoonlight", sentence = true))
        assertEquals("Burū raito", r.parts(listOf("ブル", "一", "ライト")).joinToString("").trim())
    }
}
