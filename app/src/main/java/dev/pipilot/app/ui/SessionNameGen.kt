package dev.pipilot.app.ui

import java.util.Random

/**
 * Fallback display name for the active session when the user never set one.
 *
 * pi's `set_session_name` only names the *current* session, so this is deliberately scoped to it:
 * there is no way to name the other entries of the session list without switching to them.
 */
object SessionNameGen {

    /**
     * Lowercase + digits minus the visually ambiguous ones (l/1, o/0), so a name read off one screen
     * can be typed on another without guessing. 32 chars -> 32^5 ≈ 33.6M combinations.
     */
    const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    const val DEFAULT_LENGTH = 5

    /** [random] is injectable so tests can pin the output instead of matching a random string. */
    fun generate(length: Int = DEFAULT_LENGTH, random: Random = Random()): String {
        require(length > 0) { "length must be positive, got $length" }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}