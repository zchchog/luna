package tv.luna.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests run on the JVM's regex engine; the device runs ICU, which is stricter and throws
 * PatternSyntaxException (at class init) for things the JVM accepts. Guard the source patterns.
 */
class AndroidRegexCompatTest {
    private val literals = File("src/main/java").walk().filter { it.extension == "kt" }
        .flatMap { f -> f.readLines().withIndex().flatMap { (i, l) ->
            Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(l).map { "${f.name}:${i + 1}" to it.groupValues[1] }.toList()
        } }.toList()

    @Test
    fun noUnicodeCharacterClassFlag() {
        val flag = Regex("""\(\?[a-zA-Z]*U""")
        assertEquals(emptyList<String>(), literals.filter { flag.containsMatchIn(it.second) }.map { it.first })
    }

    @Test
    fun noUnescapedClosingBracketOrBraceOutsideClass() {
        assertEquals(emptyList<String>(), literals.filter { bareCloser(it.second) }.map { it.first })
    }

    @Test
    fun guardDetectsBareClosers() {
        assertEquals(true, bareCloser("""\{[^}]*}"""))
        assertEquals(true, bareCloser("""\[VIP]"""))
        assertEquals(false, bareCloser("""\[VIP\]|a{2,3}|[^\]]"""))
    }

    /** ICU rejects a literal `]` or `}` that isn't escaped (outside a character class, not closing `{n,m}`). */
    private fun bareCloser(p: String): Boolean {
        var i = 0
        var depth = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                c == '[' -> { depth++; if (p.getOrNull(i + 1) == '^') i++ }
                c == ']' && depth > 0 -> depth--
                depth == 0 && c == '{' -> {
                    val q = Regex("""\{\d+(,\d*)?}""").matchAt(p, i) ?: return true
                    i = q.range.last
                }
                depth == 0 && (c == ']' || c == '}') -> return true
            }
            i++
        }
        return false
    }
}
