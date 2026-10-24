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
        assertEquals("yoru ni kakeru", r.machine("夜に駆ける"))
        assertEquals("kimi no koe", r.machine("君の声"))
        assertEquals("tōkyō", r.machine("東京"))
        assertEquals("eiga", r.machine("映画"))
        assertEquals("watashi wa", r.machine("私は"))
        assertEquals("kōhī", r.machine("コーヒー"))
        assertEquals("matcha", r.kana("マッチャ"))
        assertEquals("kon'ya", r.kana("コンヤ"))
        assertEquals("fan", r.kana("ファン"))
    }

    @Test fun noMacrons() {
        val plain = Romanizer(jvmIcu, RomanizeSettings(japaneseMacrons = false))
        assertEquals("tokyo", plain.machine("東京"))
    }

    @Test fun hanDecision() {
        // Kana in the string or a sibling means Japanese; otherwise the default language decides.
        assertTrue(r.isJapanese("君の声", emptyList()))
        assertTrue(r.isJapanese("紅蓮華", listOf("LiSA", "紅蓮華 (ぐれんげ)")))
        val zh = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        assertFalse(zh.isJapanese("晴天", listOf("周杰伦")))
        assertTrue(zh.isJapanese("晴天", listOf("あいみょん")))
        assertEquals("qíng tiān", zh.machine("晴天"))
        assertEquals("qing tian", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).machine("晴天"))
    }

    @Test fun korean() {
        assertEquals("saranghae", r.machine("사랑해"))
        assertEquals("hangugeo", r.machine("한국어"))
        assertEquals("gamsahamnida", r.machine("감사합니다"))
        assertEquals("silla", r.machine("신라"))
        assertEquals("joahae", r.machine("좋아해"))
        assertEquals("BTS bomnal", r.machine("BTS 봄날"))
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
        assertEquals("yoru ni kakeru", latin.joinToString("").trim())
        assertEquals(listOf("ni", "hao"), Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).parts(listOf("你", "好")))
    }

    @Test fun localSearch() {
        val s = Song("x", r.text("君の声"), listOf(Named(null, r.text("Artist"))))
        assertTrue(matches(s, "kimi no"))
        assertTrue(matches(s, "kiminokoe"))
        assertTrue(matches(s, "君の"))
        assertFalse(matches(s, "tokyo"))
    }
}
