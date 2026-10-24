package roma.core
import com.worksap.nlp.sudachi.*
import com.worksap.nlp.sudachi.dictionary.BinaryDictionary
fun main(args: Array<String>) {
    val d = DictionaryFactory().create(Config.defaultConfig().systemDictionary(BinaryDictionary.loadSystem(MMap.map(System.getProperty("roma.sudachi")))))
    val tok = d.create()
    val lines = java.io.File("/tmp/sudprobe.txt").readLines().filter { it.isNotBlank() }
    for (s in lines)
        println(s + "\n  " + tok.tokenize(Tokenizer.SplitMode.C, s).joinToString(" | ") { "${it.surface()}/${it.readingForm()}/${it.partOfSpeech().take(2).joinToString(",")}/${it.partOfSpeech()[4]}/${it.partOfSpeech()[5]}/${it.dictionaryForm()}" })
}
