import com.worksap.nlp.sudachi.dictionary.Block
import com.worksap.nlp.sudachi.dictionary.CompactedStrings
import com.worksap.nlp.sudachi.dictionary.Description
import com.worksap.nlp.sudachi.dictionary.DictionaryPrinter
import com.worksap.nlp.sudachi.dictionary.WordInfo
import com.worksap.nlp.sudachi.dictionary.WordParameters
import com.worksap.nlp.sudachi.dictionary.build.DicBuilder
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

/*
 * SudachiDict core is a dictionary for search engines; roma only needs a word's reading, part of speech and
 * dictionary form, in split mode C. This rebuilds it with Sudachi's own builder, minus what roma never reads, and
 * checks nothing else changes (docs/design.md, "Slim dictionary"):
 *
 * - Postal addresses go: compound place names (they have split data) of 5+ characters that contain an
 *   administrative unit (都道府県市区町村郡), such as 京都府京都市上京区菊屋町. Short or famous ones (富士山, 東京都) stay.
 * - Split data (A, B, word structure), synonym groups and normalized forms are cleared: they serve split modes A and
 *   B and search. Dictionary forms stay (the word card).
 * - Words that never win go: another word of the same text costs less in every context.
 * - The connection cost matrix keeps the ids in use, one copy of each distinct row and column. Ids are renumbered,
 *   so the unknown-word definitions and the config's ids are too; the class of id 0 stays 0 (sentence start and end).
 * - Word entries and strings are written in roma's format (compact, read by :core's RomaDictionary.kt).
 *
 * Usage: Slim <system_core.dic> <out dir> <version>. Writes system_core.dic, roma-sudachi.json and roma-unk.def.
 */
fun main(args: Array<String>) {
    val (source, outDir) = args.take(2).map(::File)
    val version = args[2]
    outDir.mkdirs()
    val work = File(outDir, "work").apply { mkdirs() }

    // ---- The matrix: m[a][b], a the slow index (left ids), b the fast one (right ids) ----
    val (n, matrix) = FileChannel.open(source.toPath(), READ).use { ch ->
        val all = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
        // duplicate() forgets the byte order.
        val le = { all.duplicate().order(ByteOrder.LITTLE_ENDIAN) }
        val block = Description.load(le()).slice(le(), "ConnMatrix").order(ByteOrder.LITTLE_ENDIAN)
        val rows = block.getShort(0).toInt()
        val cols = block.getShort(2).toInt()
        check(rows == cols) { "matrix $rows x $cols" }
        rows to Array(rows) { a -> ShortArray(cols) { b -> block.getShort(4 + (a * cols + b) * 2) } }
    }

    // ---- The lexicon, from Sudachi's own export, minus addresses ----
    val export = File(work, "lexicon-full.csv")
    val stdout = System.out
    PrintStream(export.outputStream().buffered(), false, "UTF-8").use { System.setOut(it); DictionaryPrinter.main(arrayOf(source.path)) }
    System.setOut(stdout)
    val admin = Regex("[都道府県市区町村郡]")
    val (header, rows) = export.bufferedReader().use { input -> parseCsv(input.readLine()) to input.lineSequence().map(::parseCsv).toList() }
    export.delete()
    val ix = header.withIndex().associate { it.value to it.index }
    fun col(name: String) = ix[name] ?: error("no $name column")
    val leftId = col("LEFT_ID"); val rightId = col("RIGHT_ID"); val costCol = col("COST"); val pos3 = col("POS3"); val pos4 = col("POS4")
    val splitA = col("SPLIT_A"); val structure = col("WORD_STRUCTURE"); val headword = col("HEADWORD"); val dictionaryForm = col("DICTIONARY_FORM")
    val keyCols = listOf("POS1", "POS2", "POS3", "POS4", "POS5", "POS6", "READING_FORM").map(::col)
    val cleared = listOf("NORMALIZED_FORM", "SPLIT_A", "SPLIT_B", "SPLIT_C", "WORD_STRUCTURE", "SYNONYM_GROUPS").map(::col)
    val words = rows.filterNot { row ->
        val compound = row[splitA].isNotEmpty() || row[structure].isNotEmpty()
        compound && row[pos3] == "地名" && row[pos4] == "一般" && row[0].length >= 5 && admin.containsMatchIn(row[0])
    }
    val addresses = rows.size - words.size

    // ---- Ids the unknown-word definitions and the config use ----
    val loader = DicBuilder::class.java.classLoader
    val unk = loader.getResourceAsStream("unk.def")!!.bufferedReader().readLines()
    var config = loader.getResourceAsStream("sudachi.json")!!.bufferedReader().readText()
        .replace("\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\" }", "\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\", \"unkDef\" : \"roma-unk.def\" }")
    check("roma-unk.def" in config) { "sudachi.json changed: MeCabOovProviderPlugin entry not found" }
    val configIds = Regex("\"(leftId|rightId)\"\\s*:\\s*(\\d+)")
    // Id 0 is the sentence start and end.
    val oovLeft = sortedSetOf(0); val oovRight = sortedSetOf(0)
    for (line in unk) line.split(',').takeIf { it.size > 3 }?.let { oovLeft += it[1].toInt(); oovRight += it[2].toInt() }
    for (m in configIds.findAll(config)) (if (m.groupValues[1] == "leftId") oovLeft else oovRight) += m.groupValues[2].toInt()
    fun used(words: List<List<String>>, ids: Int, oov: Set<Int>) =
        (words.map { it[ids].toInt() }.filter { it >= 0 } + oov).toSortedSet().toIntArray()

    // ---- Words that never win ----
    // A word is never on the best path when another word of the same text costs less in every context: its cost plus
    // the most its connections can cost more on either side (over the ids in use). Both start and end at the same
    // place, so the unknown-word rules, which only ask whether a word starts there, see no change.
    val wordLeft = used(words, leftId, oovLeft); val wordRight = used(words, rightId, oovRight)
    val gaps = HashMap<Long, Int>()
    fun leftGap(a: Int, b: Int) = if (a == b) 0 else gaps.getOrPut(a.toLong() shl 16 or b.toLong()) { wordRight.maxOf { p -> matrix[a][p] - matrix[b][p] } }
    fun rightGap(a: Int, b: Int) = if (a == b) 0 else gaps.getOrPut(1L shl 40 or (a.toLong() shl 16) or b.toLong()) { wordLeft.maxOf { s -> matrix[s][a] - matrix[s][b] } }
    // Dictionary forms refer to words by headword, part of speech and reading: those stay.
    fun key(row: List<String>) = (listOf(row[headword].ifEmpty { row[0] }) + keyCols.map { row[it] }).joinToString(",")
    val referenced = words.mapNotNullTo(HashSet()) { row -> row[dictionaryForm].takeIf { it.isNotEmpty() }?.let { parseCsv(it).take(8).joinToString(",") } }
    val beaten = HashSet<List<String>>()
    for (same in words.filter { it[leftId].toInt() >= 0 }.groupBy { it[0] }.values) for (w in same) {
        val (l, r, c) = listOf(leftId, rightId, costCol).map { w[it].toInt() }
        if (same.any { o -> o !== w && o[costCol].toInt() + leftGap(o[leftId].toInt(), l) + rightGap(o[rightId].toInt(), r) < c } && key(w) !in referenced) beaten += w
    }
    val kept = words.filter { it !in beaten }

    // ---- The matrix over the ids still in use: one copy of each distinct row and column ----
    val usedLeft = used(kept, leftId, oovLeft); val usedRight = used(kept, rightId, oovRight)
    val rowClass = classes(n, usedLeft) { a -> ShortArray(usedRight.size) { matrix[a][usedRight[it]] } }
    val colClass = classes(n, usedRight) { b -> ShortArray(usedLeft.size) { matrix[usedLeft[it]][b] } }
    val rowOf = IntArray(rowClass.count).also { r -> usedLeft.forEach { a -> r[rowClass.of[a]] = a } }
    val colOf = IntArray(colClass.count).also { c -> usedRight.forEach { b -> c[colClass.of[b]] = b } }
    val matrixFile = File(work, "matrix.def")
    // Square: Sudachi's builder checks a word's left id against the size its lookups use for right ids.
    val size = maxOf(rowClass.count, colClass.count)
    matrixFile.bufferedWriter().use { w ->
        w.write("$size $size\n")
        for (a in 0 until size) for (b in 0 until size) {
            val cost = if (a < rowClass.count && b < colClass.count) matrix[rowOf[a]][colOf[b]] else 0
            w.write("$b $a $cost\n")
        }
    }
    fun left(id: Int) = if (id < 0) id else rowClass.of[id].also { check(it >= 0) { "left id $id has no row" } }
    fun right(id: Int) = if (id < 0) id else colClass.of[id].also { check(it >= 0) { "right id $id has no column" } }

    val lexicon = File(work, "lexicon.csv")
    lexicon.bufferedWriter().use { out ->
        out.write(header.joinToString(",") + "\n")
        for (word in kept) {
            val row = word.toMutableList()
            cleared.forEach { row[it] = "" }
            row[leftId] = left(row[leftId].toInt()).toString()
            row[rightId] = right(row[rightId].toInt()).toString()
            out.write(row.joinToString(",", transform = ::csvField) + "\n")
        }
    }
    println("Slim: ${kept.size} entries, $addresses addresses and ${beaten.size} words that never win dropped, matrix ${n}x$n -> ${size}x$size")

    // ---- Unknown words and the config, with the new ids ----
    File(outDir, "roma-unk.def").bufferedWriter().use { w ->
        for (line in unk) {
            val c = line.split(',').toMutableList()
            if (c.size > 3) { c[1] = left(c[1].toInt()).toString(); c[2] = right(c[2].toInt()).toString() }
            w.write(c.joinToString(",") + "\n")
        }
    }
    config = configIds.replace(config) { m ->
        val id = m.groupValues[2].toInt()
        "\"${m.groupValues[1]}\" : ${if (m.groupValues[1] == "leftId") left(id) else right(id)}"
    }
    File(outDir, "roma-sudachi.json").writeText(config)

    // ---- Build, then compact the entries ----
    val built = File(work, "system_core.dic")
    FileChannel.open(built.toPath(), CREATE, TRUNCATE_EXISTING, WRITE, READ).use { ch ->
        DicBuilder.system().matrix(matrixFile.toPath()).signature("roma-slim-$version").lexicon(lexicon.toPath())
            .comment("SudachiDict core $version, slimmed by roma").creationTime(Instant.EPOCH).build(ch)
    }
    val out = File(outDir, "system_core.dic")
    compact(built, out)
    work.deleteRecursively()
    println("Slim: ${source.length()} -> ${out.length()} bytes")
}

/**
 * Sudachi's 32-byte word entries, its strings and its matrix in roma's format: 12-byte entries, katakana a byte per
 * character, the matrix in shared pieces. The layout is in :core's RomaDictionary.kt and RomaLattice.java, which
 * read it. Entries, Strings and ConnMatrix are left empty; every other block is copied as it is.
 */
private fun compact(source: File, out: File) = FileChannel.open(source.toPath(), READ).use { ch ->
    val all = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
    val description = Description.load(all.duplicate().order(ByteOrder.LITTLE_ENDIAN))
    val old = description.slice(all, Block.ENTRIES)
    val oldStrings = description.slice(all, Block.STRINGS)
    val strings = CompactedStrings(oldStrings.asCharBuffer())
    val n = old.limit() / 32
    check(old.limit() == n * 32) { "Entries: ${old.limit()} bytes, not 32 per word" }
    val packed = LongArray(n) { old.getLong(32 * it) }
    val words = Array(n) { WordInfo.read(old, 32 * it) }
    val headwords = Array(n) { strings.string(words[it].headword) }
    val readings = Array(n) { strings.string(words[it].readingForm) }
    for ((k, w) in words.withIndex()) {
        val empty = listOf(w.aunitSplit, w.bunitSplit, w.cunitSplit, w.wordStructure, w.synonymGroupIds).all { it.isEmpty() } && w.userData.isEmpty()
        check(empty && w.normalizedForm == 4 * k) { "entry $k: split, synonym, user data or normalized form left" }
        check(WordParameters.cost(packed[k]) != Short.MIN_VALUE) { "entry $k: runtime cost" }
        check(w.dictionaryForm % 4 == 0) { "entry $k: dictionary form ${w.dictionaryForm}" }
    }

    // Strings
    val kana = Pool(); val text = Pool()
    fun isKana(s: String) = s.isNotEmpty() && s.all { it in '\u30a0'..'\u30ff' }
    val distinct = (headwords + readings).toSet()
    kana.layout(distinct.filter(::isKana)); text.layout(distinct.filterNot(::isKana))
    fun ref(s: String): Int {
        val (pool, flag) = if (isKana(s)) kana to (1 shl 31) else text to 0
        val offset = pool.offset(s)
        check(s.length < 256 && offset < 1 shl 23) { "string too long or pool too big: $s" }
        return flag or (s.length shl 23) or offset
    }

    // Entries and dictionary forms
    val entries = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
    val params = LinkedHashMap<Long, Int>() // left, right, part of speech -> index
    val groups = (n + 63) / 64
    val bits = LongArray(groups)
    val targets = ArrayList<Int>()
    val before = IntArray(groups)
    for ((k, w) in words.withIndex()) {
        if (k % 64 == 0) before[k / 64] = targets.size
        if (w.dictionaryForm != 4 * k) { bits[k / 64] = bits[k / 64] or (1L shl (k % 64)); targets += w.dictionaryForm / 4 }
        val param = params.getOrPut(packed[k] and 0xffffffffL or (w.posId.toLong() and 0xffff shl 32)) { params.size }
        check(param < 1 shl 14) { "more than 16384 (left, right, part of speech)" }
        val cost = WordParameters.cost(packed[k]).toLong() and 0xffff
        entries.putLong(param.toLong() or (cost shl 14) or (ref(headwords[k]).toLong() and 0xffffffffL shl 30)).putInt(ref(readings[k]))
    }
    val table = ByteBuffer.allocate(params.size * 6).order(ByteOrder.LITTLE_ENDIAN)
    for (p in params.keys) table.putShort(p.toShort()).putShort((p shr 16).toShort()).putShort((p shr 32).toShort())
    val forms = ByteBuffer.allocate(8 + groups * 12 + targets.size * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(groups).putInt(0)
    bits.forEach { forms.putLong(it) }; before.forEach { forms.putInt(it) }; targets.forEach { forms.putInt(it) }
    val kanaBytes = ByteBuffer.wrap(ByteArray(kana.text.length) { (kana.text[it] - '\u30a0').toByte() })
    val textChars = ByteBuffer.allocate(text.text.length * 2).order(ByteOrder.LITTLE_ENDIAN).apply { text.text.forEach { putChar(it) } }

    val matrix = matrix(description.slice(all, Block.CONNECTION_MATRIX))

    FileChannel.open(out.toPath(), CREATE, TRUNCATE_EXISTING, WRITE).use { w ->
        val blocks = ArrayList<Description.BlockInfo>()
        w.position(4096) // the description goes first
        fun put(name: String, data: ByteBuffer) {
            w.position((w.position() + 63) / 64 * 64)
            val start = w.position()
            blocks.add(Description.BlockInfo(name, start, data.remaining().toLong()))
            while (data.hasRemaining()) w.write(data)
        }
        // Sudachi's grammar needs a matrix block to load: an empty one (0 x 0).
        val replaced = mapOf(Block.ENTRIES to ByteBuffer.allocate(0), Block.STRINGS to ByteBuffer.allocate(0), Block.CONNECTION_MATRIX to ByteBuffer.allocate(4))
        for (b in description.blocks) put(b.name, replaced[b.name] ?: description.slice(all, b.name))
        put("RomaMatrix", matrix)
        put("RomaEntries", entries.flip())
        put("RomaParams", table.flip())
        put("RomaForms", forms.flip())
        put("RomaKana", kanaBytes)
        put("RomaText", textChars.flip())
        description.blocks = blocks
        description.save(w)
    }
    println("Slim: entries and strings ${old.limit() + oldStrings.limit()} -> ${n * 12 + table.limit() + forms.limit()} + ${kanaBytes.limit() + textChars.limit()} bytes, ${params.size} (left, right, part of speech), ${targets.size} other dictionary forms")
}

/**
 * Sudachi's connection matrix as roma's (RomaLattice.java in :core reads it): each row cut into pieces of 16 costs,
 * each piece its lowest cost plus a pattern of 16 unsigned shorts, and equal patterns stored once. Ints: the
 * number of left ids (a row's length), of right ids (rows) and of patterns; then per row and piece the pattern's
 * index (int) and the lowest cost (short), then the patterns. A row is the left id of the word after, a column the
 * right id of the word before, as in Sudachi.
 */
private fun matrix(block: ByteBuffer): ByteBuffer {
    val left = block.getShort(0).toInt()
    val right = block.getShort(2).toInt()
    val pieces = (left + 15) / 16
    val patterns = LinkedHashMap<Line, Int>()
    val index = IntArray(right * pieces)
    val lowest = ShortArray(right * pieces)
    for (r in 0 until right) for (p in 0 until pieces) {
        val costs = IntArray(16) { i -> val b = p * 16 + i; if (b < left) block.getShort(4 + (r * left + b) * 2).toInt() else 0 }
        val low = costs.min()
        index[r * pieces + p] = patterns.getOrPut(Line(ShortArray(16) { (costs[it] - low).toShort() })) { patterns.size }
        lowest[r * pieces + p] = low.toShort()
    }
    val out = ByteBuffer.allocate(12 + index.size * 6 + patterns.size * 32).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(left).putInt(right).putInt(patterns.size)
    index.forEach { out.putInt(it) }
    lowest.forEach { out.putShort(it) }
    for (p in patterns.keys) p.v.forEach { out.putShort(it) }
    println("Slim: matrix ${block.limit()} -> ${out.limit()} bytes, ${patterns.size} patterns")
    return out.flip()
}

/** Strings laid out like Sudachi's StringStorage: longest first; one inside a string laid out before points into it. */
private class Pool {
    val text = StringBuilder()
    private val at = HashMap<String, Int>()

    fun layout(strings: Collection<String>) {
        val needed = strings.toHashSet()
        for (s in strings.sortedWith(compareByDescending<String> { it.length }.thenBy { it })) {
            if (s in at) continue
            val start = text.length
            text.append(s)
            for (i in s.indices) for (j in i + 1..s.length) {
                val sub = s.substring(i, j)
                if (sub in needed) at.putIfAbsent(sub, start + i)
            }
        }
        at.putIfAbsent("", 0)
    }

    fun offset(s: String) = at.getValue(s)
}

/** Equal rows (or columns) of the ids in use share a class; the class of id 0 is 0. Ids not in use have none (-1). */
private class Classes(val of: IntArray, val count: Int)

private class Line(val v: ShortArray) {
    override fun equals(other: Any?) = other is Line && v.contentEquals(other.v)
    override fun hashCode() = v.contentHashCode()
}

private fun classes(n: Int, ids: IntArray, line: (Int) -> ShortArray): Classes {
    check(ids.first() == 0)
    val seen = HashMap<Line, Int>()
    val of = IntArray(n) { -1 }
    for (i in ids) of[i] = seen.getOrPut(Line(line(i))) { seen.size }
    return Classes(of, seen.size)
}

private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun parseCsv(line: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out.add(cur.toString()); cur.setLength(0) }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString())
    return out
}
