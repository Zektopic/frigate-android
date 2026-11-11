package com.zektopic.frigate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zektopic.frigate.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Snapshot of the user's notification preferences, consumed by NvrService. */
data class NotificationSettings(
    val notificationsEnabled: Boolean = true,
    val notifyOnMotion: Boolean = true,
    val mutedCameraIds: Set<String> = emptySet()
) {
    /** True if an alert should be posted for [cameraId]. */
    fun shouldNotify(cameraId: String): Boolean =
        notificationsEnabled && notifyOnMotion && cameraId !in mutedCameraIds
}

/** Per-device display preferences; the web UI keeps the same ones in the browser. */
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Label every live tile with its camera's name. On by default: a touch screen has no hover. */
    val alwaysShowCameraNames: Boolean = true,
    /** "grid" or "list"; null follows the web UI's default for the screen (list on phones). */
    val liveLayout: String? = null,
    val use24HourTime: Boolean = true
)

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "frigate_app_settings")

/**
 * Backing store for app-level settings that live outside the camera YAML.
 * Reads/writes are backed by Jetpack DataStore; the service reads the flow and
 * caches the latest snapshot, so notification gating is genuinely enforced.
 */
class AppPreferences(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_ON_MOTION = booleanPreferencesKey("notify_on_motion")
        val MUTED_CAMERAS = stringSetPreferencesKey("muted_cameras")
        val THEME_MODE = stringPreferencesKey("ui_theme_mode")
        val SHOW_CAMERA_NAMES = booleanPreferencesKey("ui_show_camera_names")
        val LIVE_LAYOUT = stringPreferencesKey("ui_live_layout")
        val TIME_24H = booleanPreferencesKey("ui_time_24h")
    }

    val uiSettings: Flow<UiSettings> = context.appDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UiSettings(
                themeMode = ThemeMode.fromKey(prefs[Keys.THEME_MODE]),
                alwaysShowCameraNames = prefs[Keys.SHOW_CAMERA_NAMES] ?: true,
                liveLayout = prefs[Keys.LIVE_LAYOUT],
                use24HourTime = prefs[Keys.TIME_24H] ?: true
            )
        }

    suspend fun setUiSettings(settings: UiSettings) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = settings.themeMode.key
            prefs[Keys.SHOW_CAMERA_NAMES] = settings.alwaysShowCameraNames
            if (settings.liveLayout != null) prefs[Keys.LIVE_LAYOUT] = settings.liveLayout
            else prefs.remove(Keys.LIVE_LAYOUT)
            prefs[Keys.TIME_24H] = settings.use24HourTime
        }
    }

    val notificationSettings: Flow<NotificationSettings> = context.appDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            NotificationSettings(
                notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
                notifyOnMotion = prefs[Keys.NOTIFY_ON_MOTION] ?: true,
                mutedCameraIds = prefs[Keys.MUTED_CAMERAS] ?: emptySet()
            )
        }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setNotifyOnMotion(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.NOTIFY_ON_MOTION] = enabled }
    }

    suspend fun setCameraMuted(cameraId: String, muted: Boolean) {
        context.appDataStore.edit { prefs ->
            val current = prefs[Keys.MUTED_CAMERAS] ?: emptySet()
            prefs[Keys.MUTED_CAMERAS] = if (muted) current + cameraId else current - cameraId
        }
    }
}
