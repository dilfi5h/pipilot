package dev.pipilot.app.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 命名主机配置。多主机 = 命名配置列表 + 当前选中名。 */
data class HostProfile(
    val name: String,
    val settings: ConnectionSettings,
)

data class HostProfilesState(
    val profiles: List<HostProfile> = emptyList(),
    val activeName: String? = null,
) {
    val active: ConnectionSettings
        get() = profiles.firstOrNull { it.name == activeName }?.settings
            ?: profiles.firstOrNull()?.settings
            ?: ConnectionSettings()
    val activeProfileName: String?
        get() = profiles.firstOrNull { it.name == activeName }?.name
            ?: profiles.firstOrNull()?.name
}

private val profilesJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val PROFILE_LIST_KEY = stringPreferencesKey("host_profiles_json")
private val ACTIVE_PROFILE_KEY = stringPreferencesKey("host_profile_active")

private fun quote(s: String): String =
    profilesJson.encodeToString(serializer<String>(), s)

private fun parseProfiles(raw: String?): List<HostProfile> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = profilesJson.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(key: String, default: String = ""): String =
                (o[key] as? JsonPrimitive)?.contentOrNull ?: default
            HostProfile(
                name = str("name").ifBlank { return@mapNotNull null },
                settings = ConnectionSettings(
                    host = str("host"),
                    port = str("port", "22"),
                    user = str("user"),
                    authType = str("authType", "password"),
                    password = str("password"),
                    privateKey = str("privateKey"),
                    keyPassphrase = str("keyPassphrase"),
                    piCommand = str("piCommand", "pi --mode rpc"),
                    workDir = str("workDir"),
                ),
            )
        }
    }.getOrElse { emptyList() }
}

private fun serializeProfiles(profiles: List<HostProfile>): String {
    val arr = profiles.map { p ->
        buildString {
            append("{")
            append("\"name\":${quote(p.name)},")
            val s = p.settings
            append("\"host\":${quote(s.host)},")
            append("\"port\":${quote(s.port)},")
            append("\"user\":${quote(s.user)},")
            append("\"authType\":${quote(s.authType)},")
            append("\"password\":${quote(s.password)},")
            append("\"privateKey\":${quote(s.privateKey)},")
            append("\"keyPassphrase\":${quote(s.keyPassphrase)},")
            append("\"piCommand\":${quote(s.piCommand)},")
            append("\"workDir\":${quote(s.workDir)}")
            append("}")
        }
    }
    return "[${arr.joinToString(",")}]"
}

/** 主机配置仓库:首次使用时把老单配置迁移为 default。 */
class HostProfileStore(private val store: SettingsStore) {

    val state: Flow<HostProfilesState> = store.profilesPreferences.map { p ->
        var profiles = parseProfiles(p[PROFILE_LIST_KEY])
        if (profiles.isEmpty()) {
            // 老用户迁移:DataStore 里已有单配置 → default,避免升级丢配置
            val legacy = runCatching {
                runBlocking { store.settings.first() }
            }.getOrNull() ?: ConnectionSettings()
            if (legacy.host.isNotBlank() || legacy.user.isNotBlank() || legacy.privateKey.isNotBlank()) {
                profiles = listOf(HostProfile("default", legacy))
            }
        }
        HostProfilesState(profiles, p[ACTIVE_PROFILE_KEY])
    }

    suspend fun saveProfile(name: String, settings: ConnectionSettings) {
        val cur = state.first()
        val profileName = name.trim().ifBlank { "default" }
        // 身份 = host:port:user:同一台机器改名 = 原地重命名,不产生重复条目
        val identity = profileIdentity(settings)
        val updated = cur.profiles
            .filter { profileIdentity(it.settings) != identity || it.name == profileName }
            .map { if (it.name == profileName) HostProfile(profileName, settings) else it }
            .let { list ->
                if (list.any { it.name == profileName }) list
                else list + HostProfile(profileName, settings)
            }
        store.editProfiles { p ->
            p[PROFILE_LIST_KEY] = serializeProfiles(updated)
            p[ACTIVE_PROFILE_KEY] = profileName
        }
    }

    private fun profileIdentity(s: ConnectionSettings): String =
        "${s.host.trim()}:${s.port.trim()}:${s.user.trim()}"

    suspend fun setActive(name: String) {
        val cur = state.first()
        if (cur.profiles.any { it.name == name }) {
            store.editProfiles { p -> p[ACTIVE_PROFILE_KEY] = name }
        }
    }

    suspend fun deleteProfile(name: String) {
        val cur = state.first()
        val remaining = cur.profiles.filter { it.name != name }
        store.editProfiles { p ->
            p[PROFILE_LIST_KEY] = serializeProfiles(remaining)
            val nextActive = if (cur.activeName == name) remaining.firstOrNull()?.name else cur.activeName
            if (nextActive != null) p[ACTIVE_PROFILE_KEY] = nextActive else p.remove(ACTIVE_PROFILE_KEY)
        }
    }

    /** JSON 序列化 sanity check:有 profiles 时必须能 round-trip,否则说明手写序列化坏了。 */
    fun selfCheck(profiles: List<HostProfile>): Boolean {
        if (profiles.isEmpty()) return true
        val back = parseProfiles(serializeProfiles(profiles))
        if (back.size != profiles.size) return false
        return profiles.zip(back).all { (a, b) -> a == b }
    }
}
