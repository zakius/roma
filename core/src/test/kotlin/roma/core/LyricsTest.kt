package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsTest {
    @Test fun lrc() {
        val lines = parseLrc("[ar:YOASOBI]\n[00:12.50]沈むように\n[00:15.00][01:15.00]溶けてゆくように\n")
        assertEquals(listOf(12_500L, 15_000L, 75_000L), lines.map { it.startMs })
        assertEquals("溶けてゆくように", lines[2].text)
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test fun enhancedLrc() {
        val lines = parseLrc("[00:01.00]<00:01.00>君<00:01.50>の<00:02.00>声<00:03.00>\n[00:04.00]<00:04.00>next")
        val w = lines[0].words
        assertEquals(listOf("君", "の", "声"), w.map { it.text })
        assertEquals(listOf(1000L to 1500L, 1500L to 2000L, 2000L to 3000L), w.map { it.startMs to it.endMs })
        assertEquals("君の声", lines[0].text)
        assertEquals(5000L, lines[1].words[0].endMs) // last word of the last line: one second
    }

    @Test fun krcAndYrc() {
        val krc = parseKrc("[id:1]\n[1000,2000]<0,500,0>君<500,500,0>の<1000,1000,0>声")
        assertEquals(listOf(1000L, 1500L, 2000L), krc[0].words.map { it.startMs })
        assertEquals("君の声", krc[0].text)
        val yrc = parseYrc("{\"t\":0}\n[1000,2000](1000,500,0)你(1500,500,0)好")
        assertEquals(listOf("你", "好"), yrc[0].words.map { it.text })
        assertEquals(2000L, yrc[0].words[1].endMs)
    }

    @Test fun translationMerge() {
        val l = withTranslation(parseLrc("[00:01.00]君の声"), "[00:01.00]your voice")
        assertEquals("your voice", l[0].translation)
    }

    @Test fun romanizedWordsMatchTimings() {
        val r = Romanizer(jvmIcu)
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]<00:01.00>君<00:01.50>の<00:02.00>声<00:03.00>"), true), emptyList(), "x")
        assertEquals(3, l.lines[0].wordLatin.size)
        assertEquals("Kimi no koe", l.lines[0].latin)
    }

    @Test fun japaneseDecidedPerSong() {
        // A kanji-only line in a song whose other lines have kana is Japanese, even with a Chinese default.
        val r = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]夜に駆ける\n[00:02.00]花火\n[00:03.00]Hello"), true), listOf("Artist"), "x")
        assertEquals(listOf("Yoru ni kakeru", "Hanabi", "Hello"), l.lines.map { it.latin })
        val zh = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]花火"), true), listOf("Artist"), "x")
        assertEquals("Huā huǒ", zh.lines[0].latin)
    }

    @Test fun translationLanguageFromScript() {
        assertEquals("zh", translationLanguage(Lyrics("t", withTranslation(parseLrc("[00:01.00]君の声"), "[00:01.00]你的声音"), true)))
        assertEquals("en", translationLanguage(Lyrics("t", withTranslation(parseLrc("[00:01.00]君の声"), "[00:01.00]your voice"), true)))
        assertEquals(null, translationLanguage(Lyrics("t", parseLrc("[00:01.00]君の声"), true)))
    }
}

class TitleMatchTest {
    @Test fun titles() {
        assertTrue(sameTitle("夜に駆ける", "夜に駆ける (Racing into the Night)"))
        assertTrue(sameTitle("IDOL", "Idol"))
        kotlin.test.assertFalse(sameTitle("夜に駆ける", "Into The Night"))
    }
}
