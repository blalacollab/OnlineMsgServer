package com.onlinemsg.client.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

private val Context.dataStore by preferencesDataStore(name = "oms_preferences")

data class UserPreferences(
    val displayName: String,
    val serverUrls: List<String>,
    val currentServerUrl: String,
    val showSystemMessages: Boolean,
    val directMode: Boolean,
    val shouldAutoReconnect: Boolean,
    val themeId: String = "blue",
    val useDynamicColor: Boolean = true,
    val language: String = "zh" // 默认中文
)

class UserPreferencesRepository(
    private val context: Context,
    private val json: Json
) {
    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val storedDisplayName = prefs[KEY_DISPLAY_NAME].orEmpty().trim()
        val displayName = storedDisplayName.takeIf { it.isNotBlank() } ?: "guest-${System.currentTimeMillis().toString().takeLast(6)}"

        val serverUrls = decodeServerUrls(prefs[KEY_SERVER_URLS])
        val currentServer = ServerUrlFormatter.normalize(prefs[KEY_CURRENT_SERVER_URL].orEmpty())
            .takeIf { it.isNotBlank() }
            ?: serverUrls.firstOrNull()
            ?: ServerUrlFormatter.defaultServerUrl

        UserPreferences(
            displayName = displayName.take(64),
            serverUrls = if (serverUrls.isEmpty()) listOf(ServerUrlFormatter.defaultServerUrl) else serverUrls,
            currentServerUrl = currentServer,
            showSystemMessages = prefs[KEY_SHOW_SYSTEM_MESSAGES] ?: false,
            directMode = prefs[KEY_DIRECT_MODE] ?: false,
            shouldAutoReconnect = prefs[KEY_SHOULD_AUTO_RECONNECT] ?: false,
            themeId = prefs[KEY_THEME_ID] ?: "blue",
            useDynamicColor = prefs[KEY_USE_DYNAMIC_COLOR] ?: true,
            language = prefs[KEY_LANGUAGE] ?: "zh"
        )
    }

    suspend fun setThemeId(themeId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_ID] = themeId
        }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language
        }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USE_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISPLAY_NAME] = name.trim().take(64)
        }
    }

    suspend fun setCurrentServerUrl(rawUrl: String) {
        val normalized = ServerUrlFormatter.normalize(rawUrl)
        if (normalized.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[KEY_CURRENT_SERVER_URL] = normalized
            val currentList = decodeServerUrls(prefs[KEY_SERVER_URLS])
            val nextList = ServerUrlFormatter.append(currentList, normalized)
            prefs[KEY_SERVER_URLS] = json.encodeToString(nextList)
        }
    }

    suspend fun saveCurrentServerUrl(rawUrl: String) {
        val normalized = ServerUrlFormatter.normalize(rawUrl)
        if (normalized.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentList = decodeServerUrls(prefs[KEY_SERVER_URLS])
            val nextList = ServerUrlFormatter.append(currentList, normalized)
            prefs[KEY_SERVER_URLS] = json.encodeToString(nextList)
            prefs[KEY_CURRENT_SERVER_URL] = normalized
        }
    }

    suspend fun removeCurrentServerUrl(rawUrl: String) {
        val normalized = ServerUrlFormatter.normalize(rawUrl)
        context.dataStore.edit { prefs ->
            val currentList = decodeServerUrls(prefs[KEY_SERVER_URLS])
            val filtered = currentList.filterNot { it == normalized }
            val nextList = if (filtered.isEmpty()) {
                listOf(ServerUrlFormatter.defaultServerUrl)
            } else {
                filtered
            }
            prefs[KEY_SERVER_URLS] = json.encodeToString(nextList)
            prefs[KEY_CURRENT_SERVER_URL] = nextList.first()
        }
    }

    suspend fun setShowSystemMessages(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SHOW_SYSTEM_MESSAGES] = show
        }
    }

    suspend fun setDirectMode(isDirect: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DIRECT_MODE] = isDirect
        }
    }

    suspend fun setShouldAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SHOULD_AUTO_RECONNECT] = enabled
        }
    }

    private fun decodeServerUrls(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return listOf(ServerUrlFormatter.defaultServerUrl)
        return runCatching {
            val element = json.parseToJsonElement(raw)
            element.jsonArray.mapNotNull { item ->
                runCatching { json.decodeFromJsonElement<String>(item) }.getOrNull()
            }
        }.getOrElse { emptyList() }
            .let(ServerUrlFormatter::dedupe)
            .ifEmpty { listOf(ServerUrlFormatter.defaultServerUrl) }
    }

    private companion object {
        val KEY_DISPLAY_NAME: Preferences.Key<String> = stringPreferencesKey("display_name")
        val KEY_SERVER_URLS: Preferences.Key<String> = stringPreferencesKey("server_urls")
        val KEY_CURRENT_SERVER_URL: Preferences.Key<String> = stringPreferencesKey("current_server_url")
        val KEY_SHOW_SYSTEM_MESSAGES: Preferences.Key<Boolean> = booleanPreferencesKey("show_system_messages")
        val KEY_DIRECT_MODE: Preferences.Key<Boolean> = booleanPreferencesKey("direct_mode")
        val KEY_SHOULD_AUTO_RECONNECT: Preferences.Key<Boolean> = booleanPreferencesKey("should_auto_reconnect")
        val KEY_THEME_ID: Preferences.Key<String> = stringPreferencesKey("theme_id")
        val KEY_USE_DYNAMIC_COLOR: Preferences.Key<Boolean> = booleanPreferencesKey("use_dynamic_color")
        val KEY_LANGUAGE: Preferences.Key<String> = stringPreferencesKey("language")
    }
}
