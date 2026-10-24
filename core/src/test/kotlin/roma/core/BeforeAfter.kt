package roma.core
fun main() {
    val r = Romanizer(Icu { id, t -> com.ibm.icu.text.Transliterator.getInstance(id).transliterate(t) })
    val t0 = System.nanoTime(); r.machine("夜"); println("first: ${(System.nanoTime() - t0) / 1_000_000} ms")
    for (l in java.io.File("/tmp/balines.txt").readLines()) {
        val (k, s) = l.split('|')
        println("$s\t${r.machine(s, sentence = k == "S")}")
    }
}
