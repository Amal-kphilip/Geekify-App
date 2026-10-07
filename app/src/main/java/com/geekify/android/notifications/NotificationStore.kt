package com.geekify.android.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the user chose in Settings. Tips default on (rare, one per version); engagement is opt-in. */
data class NotificationPrefs(
    val updates: Boolean = true,
    val tips: Boolean = true,
    val engagement: Boolean = false
)

/** Persistent bookkeeping that makes notifications idempotent and rate-limited across restarts. */
data class NotificationState(
    val lastCheckedAt: Long = 0L,
    val latestVersionCode: Int = 0,
    val latestVersionName: String? = null,
    val latestReleaseUrl: String? = null,
    val updateShownForVersion: Int = 0,
    val dismissedVersion: Int = 0,
    /** Version code for which the "what's new" tip was last handled (0 = never seen, i.e. fresh install). */
    val tipHandledForVersion: Int = 0,
    val lastOpenedAt: Long = 0L,
    /** Every non-media notification we posted (last 14 days). */
    val allSentAt: List<Long> = emptyList(),
    /** Only tips/engagement (last 14 days) - these have the weekly cap. */
    val softSentAt: List<Long> = emptyList(),
    /** Prevents repeatedly prompting for POST_NOTIFICATIONS after the first request. */
    val permissionRequested: Boolean = false
)

/** Reuses the app's existing DataStore (the same one AudioSettings and QueueManager use). */
@Singleton
class NotificationStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private object K {
        val updates = booleanPreferencesKey("notif_updates")
        val tips = booleanPreferencesKey("notif_tips")
        val engagement = booleanPreferencesKey("notif_engagement")
        val lastChecked = longPreferencesKey("notif_last_checked_at")
        val latestCode = intPreferencesKey("notif_latest_version_code")
        val latestName = stringPreferencesKey("notif_latest_version_name")
        val latestUrl = stringPreferencesKey("notif_latest_release_url")
        val shownFor = intPreferencesKey("notif_update_shown_for")
        val dismissed = intPreferencesKey("notif_dismissed_version")
        val tipFor = intPreferencesKey("notif_tip_handled_for")
        val lastOpened = longPreferencesKey("notif_last_opened_at")
        val allSent = stringPreferencesKey("notif_all_sent_at")
        val softSent = stringPreferencesKey("notif_soft_sent_at")
        val permissionRequested = booleanPreferencesKey("notif_permission_requested")
    }

    private val safeData: Flow<Preferences> = dataStore.data.catch { emit(emptyPreferences()) }

    val prefs: Flow<NotificationPrefs> = safeData.map(::decodePrefs).distinctUntilChanged()

    suspend fun prefsNow(): NotificationPrefs = prefs.first()
    suspend fun stateNow(): NotificationState = decodeState(safeData.first())

    suspend fun setUpdates(on: Boolean) = edit { it[K.updates] = on }
    suspend fun setTips(on: Boolean) = edit { it[K.tips] = on }
    suspend fun setEngagement(on: Boolean) = edit { it[K.engagement] = on }
    suspend fun markPermissionRequested() = edit { it[K.permissionRequested] = true }

    suspend fun updateState(transform: (NotificationState) -> NotificationState) = edit { p ->
        val next = transform(decodeState(p))
        p[K.lastChecked] = next.lastCheckedAt
        p[K.latestCode] = next.latestVersionCode
        next.latestVersionName?.let { p[K.latestName] = it } ?: p.remove(K.latestName)
        next.latestReleaseUrl?.let { p[K.latestUrl] = it } ?: p.remove(K.latestUrl)
        p[K.shownFor] = next.updateShownForVersion
        p[K.dismissed] = next.dismissedVersion
        p[K.tipFor] = next.tipHandledForVersion
        p[K.lastOpened] = next.lastOpenedAt
        p[K.allSent] = next.allSentAt.joinToString(",")
        p[K.softSent] = next.softSentAt.joinToString(",")
        p[K.permissionRequested] = next.permissionRequested
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(io) { dataStore.edit { block(it) } }
    }

    private fun decodePrefs(p: Preferences) = NotificationPrefs(
        updates = p[K.updates] ?: true,
        tips = p[K.tips] ?: true,
        engagement = p[K.engagement] ?: false
    )

    private fun decodeState(p: Preferences) = NotificationState(
        lastCheckedAt = p[K.lastChecked] ?: 0L,
        latestVersionCode = p[K.latestCode] ?: 0,
        latestVersionName = p[K.latestName],
        latestReleaseUrl = p[K.latestUrl],
        updateShownForVersion = p[K.shownFor] ?: 0,
        dismissedVersion = p[K.dismissed] ?: 0,
        tipHandledForVersion = p[K.tipFor] ?: 0,
        lastOpenedAt = p[K.lastOpened] ?: 0L,
        allSentAt = p[K.allSent].toLongList(),
        softSentAt = p[K.softSent].toLongList(),
        permissionRequested = p[K.permissionRequested] ?: false
    )

    private fun String?.toLongList(): List<Long> =
        this?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
}

/** Adds [at] to a send log and forgets entries older than 14 days. */
fun List<Long>.logged(at: Long): List<Long> = (this + at).filter { at - it < 14 * NotificationPolicy.DAY_MS }
