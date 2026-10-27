package com.worksap.nlp.sudachi.dictionary

import com.worksap.nlp.dartsclone.DoubleArray
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.RomaLattice
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.WordId
import java.nio.ByteBuffer
import java.nio.CharBuffer

/*
 * roma's dictionary (:dict-tool, docs/design.md "Slim dictionary"): Sudachi's format, with each word's 32-byte entry
 * cut to the 12 bytes that split mode C reads and roma's own strings. It lives in Sudachi's package for
 * WordInfoList's and WordIdTable's package-private constructors. Word ids are Sudachi's (4 × the entry's index), so
 * the trie and word id table are Sudachi's own blocks.
 *
 * RomaEntries, 12 bytes per word id / 4: a long of
 *   bits 0-13   index into RomaParams
 *   bits 14-29  cost
 *   bits 30-61  headword (a string reference)
 * then an int, the reading (a string reference).
 * RomaParams: (left id, right id, part of speech) as three shorts, shared by the words that have them.
 * RomaForms: the words whose dictionary form is another word. An int, the number of groups of 64 entries, an int of
 *   padding, a long per group with a bit per such word, an int per group counting them in the groups before, then
 *   the dictionary form's word id / 4 of each, in order. The other words are their own dictionary form.
 * Strings: katakana-only ones in RomaKana, a byte per character (the character - U+30A0); the others in RomaText as
 *   UTF-16. A reference is the pool (bit 31, 1 for RomaKana), the length (bits 23-30) and the offset (bits 0-22).
 * RomaMatrix: the connection matrix, read by RomaLattice.
 * The normalized form is the word itself; splits, synonyms and user data are empty; the index form's length is 0
 * (only split modes A and B read it). Sudachi's Entries and Strings blocks are empty and its matrix is 0 x 0: an
 * older roma fails on this dictionary instead of misreading it.
 */
class RomaDictionary(bytes: ByteBuffer) : BinaryDictionary(bytes) {
    private val lexicon = RomaLexicon(bytes, dictionaryHeader)
    private val matrix = dictionaryHeader.slice(bytes, "RomaMatrix")

    init {
        check(dictionaryHeader.isSystemDictionary) { "invalid system dictionary" }
    }

    override fun getLexicon(): DoubleArrayLexicon = lexicon

    /** A tokenizer with [config] on this dictionary, its lattice reading roma's matrix (RomaLattice). */
    fun tokenizer(config: Config): Tokenizer = RomaLattice.install(Dictionary.load(config.systemDictionary(this)).tokenizer(), matrix)
}

private class RomaLexicon(bytes: ByteBuffer, header: Description, private val entries: RomaEntries = RomaEntries(bytes, header)) :
    DoubleArrayLexicon(
        header,
        WordIdTable(header.slice(bytes, Block.WORD_POINTERS)),
        null,
        entries,
        DoubleArray().apply { val a = header.slice(bytes, Block.TRIE_INDEX).asIntBuffer(); setArray(a, a.limit()) },
        CompactedStrings(CharBuffer.allocate(0)),
    ) {
    override fun parameters(wordId: Int): Long = entries.parameters(wordId)

    override fun string(dic: Int, stringPtr: Int): String = entries.string(stringPtr)
}

private class RomaEntries(bytes: ByteBuffer, header: Description) : WordInfoList(null) {
    private val entries = header.slice(bytes, "RomaEntries")
    private val params = header.slice(bytes, "RomaParams")
    private val forms = header.slice(bytes, "RomaForms")
    private val kana = header.slice(bytes, "RomaKana")
    private val text = header.slice(bytes, "RomaText").asCharBuffer()
    private val groups = forms.getInt(0)

    private fun at(wordId: Int) = (WordId.word(wordId) shr 2) * 12

    /** Packed as Sudachi's WordParameters reads them: left, right, cost, part of speech. */
    fun parameters(wordId: Int): Long {
        val w = entries.getLong(at(wordId))
        val p = (w and 0x3fff).toInt() * 6
        return (params.getShort(p).toLong() and 0xffff) or
            (params.getShort(p + 2).toLong() and 0xffff shl 16) or
            (w ushr 14 and 0xffff shl 32) or
            (params.getShort(p + 4).toLong() and 0xffff shl 48)
    }

    private fun dictionaryForm(wordId: Int): Int {
        val k = WordId.word(wordId) shr 2
        val bits = forms.getLong(8 + (k ushr 6) * 8)
        val bit = 1L shl (k and 63)
        if (bits and bit == 0L) return WordId.word(wordId)
        val rank = forms.getInt(8 + groups * 8 + (k ushr 6) * 4) + java.lang.Long.bitCount(bits and (bit - 1))
        return forms.getInt(8 + groups * 12 + rank * 4) shl 2
    }

    override fun getWordInfo(wordId: Int): WordInfo {
        val e = at(wordId)
        val w = entries.getLong(e)
        val none = Ints.EMPTY_ARRAY
        return WordInfo(
            0, params.getShort((w and 0x3fff).toInt() * 6 + 4), (w ushr 30).toInt(), entries.getInt(e + 8),
            WordId.word(wordId), dictionaryForm(wordId), none, none, none, none, none, "",
        )
    }

    override fun headwordPtr(wordId: Int): Int = (entries.getLong(at(wordId)) ushr 30).toInt()

    override fun readingPtr(wordId: Int): Int = entries.getInt(at(wordId) + 8)

    fun string(ref: Int): String {
        val length = ref ushr 23 and 0xff
        val offset = ref and 0x7fffff
        if (ref >= 0) return text.subSequence(offset, offset + length).toString()
        return String(CharArray(length) { (0x30a0 + (kana.get(offset + it).toInt() and 0xff)).toChar() })
    }
}
