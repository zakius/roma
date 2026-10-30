package com.zakius.roma

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

// ./gradlew :desktop:icuCheck: the released ICU4J (slimmed, desktop/build.gradle.kts icuSlim, and shrunk by ProGuard)
// transliterates as the full jar does:
// every assigned code point, by script in runs of 64, through each transliterator roma uses, compared by digest.
fun main(args: Array<String>) {
    val ids = listOf("Han-Latin", "Traditional-Simplified", "Greek-Latin", "Greek-Latin/UNGEGN", "Cyrillic-Latin", "mn-mn_Latn/MNS", "Any-Latin")
    val text = java.util.TreeMap<Character.UnicodeScript, StringBuilder>()
    for (c in 0..0x3FFFF) {
        if (!Character.isDefined(c) || Character.isISOControl(c) || Character.getType(c) in setOf(Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt())) continue
        val sb = text.getOrPut(Character.UnicodeScript.of(c)) { StringBuilder() }
        sb.appendCodePoint(c)
        if (sb.codePointCount(0, sb.length) % 64 == 0) sb.append(' ')
    }
    // Each jar in a class loader of its own, so neither sees the other's data.
    fun digests(jar: String): List<String> = URLClassLoader(arrayOf(File(jar).toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
        val t = loader.loadClass("com.ibm.icu.text.Transliterator")
        val get = t.getMethod("getInstance", String::class.java)
        val run = t.getMethod("transliterate", String::class.java)
        ids.map { id ->
            val tr = get.invoke(null, id)
            val md = MessageDigest.getInstance("SHA-256")
            for (sb in text.values) md.update((run.invoke(tr, sb.toString()) as String).toByteArray())
            md.digest().toHexString().take(16)
        }
    }
    val full = digests(args[0])
    val slim = digests(args[1])
    ids.indices.forEach { println("${ids[it]}: full ${full[it]}, slim ${slim[it]}" + if (full[it] == slim[it]) "" else "  DIFFERENT") }
    check(full == slim) { "the slim ICU4J transliterates differently" }
}
