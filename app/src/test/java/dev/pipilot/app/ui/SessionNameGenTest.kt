package dev.pipilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SessionNameGenTest {

    @Test
    fun `default length is 5`() {
        assertEquals(5, SessionNameGen.generate().length)
    }

    @Test
    fun `honours explicit length`() {
        for (n in 1..12) {
            assertEquals(n, SessionNameGen.generate(n).length)
        }
    }

    @Test
    fun `only emits characters from the alphabet`() {
        repeat(500) {
            val name = SessionNameGen.generate()
            assertTrue("unexpected char in '$name'", name.all { c -> c in SessionNameGen.ALPHABET })
        }
    }

    @Test
    fun `avoids visually ambiguous characters`() {
        val ambiguous = setOf('l', 'o', '0', '1')
        repeat(500) {
            val name = SessionNameGen.generate()
            assertTrue("ambiguous char in '$name'", name.none { it in ambiguous })
        }
    }

    @Test
    fun `is reproducible with a seeded random`() {
        assertEquals(
            SessionNameGen.generate(random = Random(42)),
            SessionNameGen.generate(random = Random(42)),
        )
    }

    @Test
    fun `a seeded random produces the exact expected string`() {
        // Guards against an off-by-one in the alphabet index range.
        assertEquals("zbxbj", SessionNameGen.generate(random = Random(42)))
    }

    @Test
    fun `repeated generation varies`() {
        val names = (1..50).map { SessionNameGen.generate() }.toSet()
        assertTrue("expected varied names, got $names", names.size > 40)
    }
}