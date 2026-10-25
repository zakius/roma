import com.worksap.nlp.sudachi.dictionary.Description
import com.worksap.nlp.sudachi.dictionary.DictionaryPrinter
import com.worksap.nlp.sudachi.dictionary.build.DicBuilder
import java.io.File
import java.io.PrintStream
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
 * - The connection cost matrix keeps one copy of each distinct row and column. Ids are renumbered, so the
 *   unknown-word definitions and the config's ids are too; the class of id 0 stays 0 (sentence start and end).
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
    val rowClass = classes(n) { a -> matrix[a] }
    val colClass = classes(n) { b -> ShortArray(n) { a -> matrix[a][b] } }
    val size = maxOf(rowClass.count, colClass.count)
    val rowOf = IntArray(rowClass.count).also { r -> rowClass.of.forEachIndexed { a, k -> r[k] = a } }
    val colOf = IntArray(colClass.count).also { c -> colClass.of.forEachIndexed { b, k -> c[k] = b } }
    val matrixFile = File(work, "matrix.def")
    matrixFile.bufferedWriter().use { w ->
        w.write("$size $size\n")
        for (a in 0 until size) for (b in 0 until size) {
            val cost = if (a < rowClass.count && b < colClass.count) matrix[rowOf[a]][colOf[b]] else 0
            w.write("$b $a $cost\n")
        }
    }
    fun left(id: Int) = if (id < 0) id else rowClass.of[id]
    fun right(id: Int) = if (id < 0) id else colClass.of[id]

    // ---- The lexicon, from Sudachi's own export ----
    val export = File(work, "lexicon-full.csv")
    val stdout = System.out
    PrintStream(export.outputStream().buffered(), false, "UTF-8").use { System.setOut(it); DictionaryPrinter.main(arrayOf(source.path)) }
    System.setOut(stdout)
    val admin = Regex("[都道府県市区町村郡]")
    val lexicon = File(work, "lexicon.csv")
    var kept = 0
    var dropped = 0
    export.bufferedReader().use { input ->
        lexicon.bufferedWriter().use { out ->
            val header = parseCsv(input.readLine())
            out.write(header.joinToString(",") + "\n")
            val ix = header.withIndex().associate { it.value to it.index }
            fun col(name: String) = ix[name] ?: error("no $name column")
            val leftId = col("LEFT_ID"); val rightId = col("RIGHT_ID"); val pos3 = col("POS3"); val pos4 = col("POS4")
            val splitA = col("SPLIT_A"); val structure = col("WORD_STRUCTURE")
            val cleared = listOf("NORMALIZED_FORM", "SPLIT_A", "SPLIT_B", "SPLIT_C", "WORD_STRUCTURE", "SYNONYM_GROUPS").map(::col)
            for (line in input.lineSequence()) {
                val row = parseCsv(line).toMutableList()
                val compound = row[splitA].isNotEmpty() || row[structure].isNotEmpty()
                if (compound && row[pos3] == "地名" && row[pos4] == "一般" && row[0].length >= 5 && admin.containsMatchIn(row[0])) { dropped++; continue }
                cleared.forEach { row[it] = "" }
                row[leftId] = left(row[leftId].toInt()).toString()
                row[rightId] = right(row[rightId].toInt()).toString()
                out.write(row.joinToString(",", transform = ::csvField) + "\n")
                kept++
            }
        }
    }
    println("Slim: $kept entries, $dropped addresses dropped, matrix ${n}x$n -> ${size}x$size")

    // ---- Unknown words and the config, with the new ids ----
    val loader = DicBuilder::class.java.classLoader
    File(outDir, "roma-unk.def").bufferedWriter().use { w ->
        loader.getResourceAsStream("unk.def")!!.bufferedReader().forEachLine { line ->
            val c = line.split(',').toMutableList()
            if (c.size > 3) { c[1] = left(c[1].toInt()).toString(); c[2] = right(c[2].toInt()).toString() }
            w.write(c.joinToString(",") + "\n")
        }
    }
    var config = loader.getResourceAsStream("sudachi.json")!!.bufferedReader().readText()
        .replace("\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\" }", "\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\", \"unkDef\" : \"roma-unk.def\" }")
    check("roma-unk.def" in config) { "sudachi.json changed: MeCabOovProviderPlugin entry not found" }
    config = Regex("\"(leftId|rightId)\"\\s*:\\s*(\\d+)").replace(config) { m ->
        val id = m.groupValues[2].toInt()
        "\"${m.groupValues[1]}\" : ${if (m.groupValues[1] == "leftId") left(id) else right(id)}"
    }
    File(outDir, "roma-sudachi.json").writeText(config)

    // ---- Build ----
    val out = File(outDir, "system_core.dic")
    FileChannel.open(out.toPath(), CREATE, TRUNCATE_EXISTING, WRITE, READ).use { ch ->
        DicBuilder.system().matrix(matrixFile.toPath()).signature("roma-slim-$version").lexicon(lexicon.toPath())
            .comment("SudachiDict core $version, slimmed by roma").creationTime(Instant.EPOCH).build(ch)
    }
    work.deleteRecursively()
    println("Slim: ${source.length()} -> ${out.length()} bytes")
}

/** Equal rows (or columns) share a class; the class of index 0 is 0. */
private class Classes(val of: IntArray, val count: Int)

private class Line(val v: ShortArray) {
    override fun equals(other: Any?) = other is Line && v.contentEquals(other.v)
    override fun hashCode() = v.contentHashCode()
}

private fun classes(n: Int, line: (Int) -> ShortArray): Classes {
    val seen = HashMap<Line, Int>()
    val of = IntArray(n) { i -> seen.getOrPut(Line(line(i))) { seen.size } }
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
