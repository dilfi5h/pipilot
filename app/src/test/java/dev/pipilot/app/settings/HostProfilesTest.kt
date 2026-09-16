package dev.pipilot.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfilesTest {
    @Test
    fun roundTripPreservesSecretsAndWorkDir() {
        val profiles = listOf(
            HostProfile(
                "home",
                ConnectionSettings(
                    host = "10.0.0.2",
                    port = "2222",
                    user = "dil",
                    authType = "key",
                    password = "",
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret\n",
                    keyPassphrase = "p\"ass",
                    piCommand = "pi --mode rpc",
                    workDir = "/tmp/proj",
                ),
            ),
            HostProfile("office", ConnectionSettings(host = "office.local", user = "me", password = "pw")),
        )
        val json = serializeProfiles(profiles)
        val back = parseProfiles(json)
        assertEquals(profiles, back)
        assertTrue(json.contains("\\n") || json.contains("secret"))
    }

    @Test
    fun parseBlankAndGarbage() {
        assertTrue(parseProfiles(null).isEmpty())
        assertTrue(parseProfiles("").isEmpty())
        assertTrue(parseProfiles("not-json").isEmpty())
    }
}
