package dev.pipilot.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pipilot_settings")

data class ConnectionSettings(
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val authType: String = "password", // password | key
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val piCommand: String = "pi --mode rpc",
    val workDir: String = "",
) {
    val isValid: Boolean get() = host.isNotBlank() && user.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private val HOST = stringPreferencesKey("host")
    private val PORT = stringPreferencesKey("port")
    private val USER = stringPreferencesKey("user")
    private val AUTH_TYPE = stringPreferencesKey("auth_type")
    private val PASSWORD = stringPreferencesKey("password")
    private val PRIVATE_KEY = stringPreferencesKey("private_key")
    private val KEY_PASSPHRASE = stringPreferencesKey("key_passphrase")
    private val PI_COMMAND = stringPreferencesKey("pi_command")
    private val WORK_DIR = stringPreferencesKey("work_dir")

    val settings: Flow<ConnectionSettings> = context.dataStore.data.map { p ->
        ConnectionSettings(
            host = p[HOST] ?: "",
            port = p[PORT] ?: "22",
            user = p[USER] ?: "",
            authType = p[AUTH_TYPE] ?: "password",
            password = p[PASSWORD] ?: "",
            privateKey = p[PRIVATE_KEY] ?: "",
            keyPassphrase = p[KEY_PASSPHRASE] ?: "",
            piCommand = p[PI_COMMAND] ?: "pi --mode rpc",
            workDir = p[WORK_DIR] ?: "",
        )
    }

    suspend fun save(s: ConnectionSettings) {
        context.dataStore.edit { p ->
            p[HOST] = s.host
            p[PORT] = s.port
            p[USER] = s.user
            p[AUTH_TYPE] = s.authType
            p[PASSWORD] = s.password
            p[PRIVATE_KEY] = s.privateKey
            p[KEY_PASSPHRASE] = s.keyPassphrase
            p[PI_COMMAND] = s.piCommand
            p[WORK_DIR] = s.workDir
        }
    }

    /** 多主机配置用的底层读写:profiles JSON + 当前选中名。 */
    internal val profilesPreferences: Flow<androidx.datastore.preferences.core.Preferences> =
        context.dataStore.data

    internal suspend fun editProfiles(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.dataStore.edit(block)
    }
}
