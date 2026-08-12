package com.replypal.ultimate.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.replyPalDataStore by preferencesDataStore(name = "replypal_preferences")

data class UserPreferences(
    val preferredTone: String = "Casual",
    val responseLength: String = "Short",
    val emojiLevel: String = "Normal",
    val language: String = "Auto",
    val floatingEnabled: Boolean = false,
    val bubbleSizeDp: Int = 56,
    val bubbleX: Int = 0,
    val bubbleY: Int = 260,
    val apiBaseUrl: String = ""
)

class SettingsManager(private val context: Context) {
    private object Keys {
        val tone = stringPreferencesKey("tone")
        val length = stringPreferencesKey("length")
        val emoji = stringPreferencesKey("emoji")
        val language = stringPreferencesKey("language")
        val floating = booleanPreferencesKey("floating")
        val bubbleSize = intPreferencesKey("bubble_size")
        val bubbleX = intPreferencesKey("bubble_x")
        val bubbleY = intPreferencesKey("bubble_y")
        val apiBaseUrl = stringPreferencesKey("api_base_url")
    }

    val preferences: Flow<UserPreferences> = context.replyPalDataStore.data.map { pref ->
        UserPreferences(
            preferredTone = pref[Keys.tone] ?: "Casual",
            responseLength = pref[Keys.length] ?: "Short",
            emojiLevel = pref[Keys.emoji] ?: "Normal",
            language = pref[Keys.language] ?: "Auto",
            floatingEnabled = pref[Keys.floating] ?: false,
            bubbleSizeDp = pref[Keys.bubbleSize] ?: 56,
            bubbleX = pref[Keys.bubbleX] ?: 0,
            bubbleY = pref[Keys.bubbleY] ?: 260,
            apiBaseUrl = pref[Keys.apiBaseUrl] ?: ""
        )
    }

    suspend fun update(transform: (MutablePreferencesEditor) -> Unit) {
        context.replyPalDataStore.edit { preferences -> transform(MutablePreferencesEditor(preferences)) }
    }

    suspend fun setFloating(enabled: Boolean) = update { it[Keys.floating] = enabled }
    suspend fun setBubblePosition(x: Int, y: Int) = update { it[Keys.bubbleX] = x; it[Keys.bubbleY] = y }
    suspend fun setBubbleSize(size: Int) = update { it[Keys.bubbleSize] = size }
    suspend fun setApiBaseUrl(url: String) = update { it[Keys.apiBaseUrl] = url.trim().removeSuffix("/") }
    suspend fun setTone(value: String) = update { it[Keys.tone] = value }
    suspend fun setLength(value: String) = update { it[Keys.length] = value }
    suspend fun setEmoji(value: String) = update { it[Keys.emoji] = value }
    suspend fun setLanguage(value: String) = update { it[Keys.language] = value }
}

class MutablePreferencesEditor internal constructor(private val preferences: androidx.datastore.preferences.core.MutablePreferences) {
    operator fun set(key: Preferences.Key<String>, value: String) { preferences[key] = value }
    operator fun set(key: Preferences.Key<Boolean>, value: Boolean) { preferences[key] = value }
    operator fun set(key: Preferences.Key<Int>, value: Int) { preferences[key] = value }
}
