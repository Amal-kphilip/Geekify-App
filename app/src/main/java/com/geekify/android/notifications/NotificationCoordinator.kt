package com.geekify.android.notifications

import com.geekify.android.BuildConfig
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.player.PlayerController
import com.geekify.android.update.AppUpdateManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of "should Geekify say anything right now?". Called by the periodic worker and follow-up kicks.
 * Order = priority: update first, then alternating personalized library/listening engagement and
 * rotating feature tips. At most ONE notification is posted per pass.
 */
@Singleton
class NotificationCoordinator @Inject constructor(
    private val store: NotificationStore,
    private val checker: UpdateChecker,
    private val notifier: GeekifyNotifier,
    private val history: HistoryRepository,
    private val library: LibraryRepository,
    private val playerController: PlayerController,
    private val updateManager: AppUpdateManager
) {
    private val installed: Int = maxOf(
        BuildConfig.VERSION_CODE,
        ReleaseParser.versionCodeFor(BuildConfig.VERSION_NAME) ?: 0
    )

    private data class FeatureTip(val id: String, val title: String, val body: String, val route: String?)

    private val featureTips = listOf(
        FeatureTip(
            id = "tip_listen_together",
            title = "Listen Together with friends",
            body = "Create or join a live synchronized music room and control the queue together.",
            route = "listen_together"
        ),
        FeatureTip(
            id = "tip_listening_stats",
            title = "Check your Listening Statistics",
            body = "See your top tracks, favorite artists, and listening streaks in Geekify.",
            route = "listening_stats"
        ),
        FeatureTip(
            id = "tip_equalizer",
            title = "Tune your sound with the Equalizer",
            body = "Customize 5-band EQ, bass presets, playback speed, pitch, and volume normalization.",
            route = "audio_settings"
        ),
        FeatureTip(
            id = "tip_daily_picks",
            title = "Fresh Daily Picks are ready",
            body = "Jump into Geekify for personalized mixes and recommendations tailored to your taste.",
            route = "home"
        )
    )

    suspend fun runOnce(now: Long = System.currentTimeMillis(), appInForeground: Boolean = AppForeground.isVisible) {
        // 1. Refresh the cached release info (rate limited inside) and drop an update notice that is now obsolete.
        val refreshed = checker.refreshIfDue(now)
        reconcile(refreshed)
        var state = store.stateNow()
        val prefs = store.prefsNow()

        // 2. Update notification (never post if already handled/pending in AppUpdateManager or already installed).
        val handledInAppUpdater = state.latestVersionCode > installed &&
            updateManager.hasPendingOrDismissedUpdateFor(state.latestVersionCode, state.latestVersionName)
        if (handledInAppUpdater) {
            notifier.cancelUpdate()
            if (state.updateShownForVersion < state.latestVersionCode) {
                store.updateState {
                    it.copy(
                        updateShownForVersion = maxOf(it.updateShownForVersion, state.latestVersionCode),
                        dismissedVersion = maxOf(it.dismissedVersion, state.latestVersionCode)
                    )
                }
                state = store.stateNow()
            }
        } else if (allowed(NotificationPolicy.Kind.UPDATE, now, prefs, state, appInForeground, NotificationChannels.UPDATES)) {
            val name = state.latestVersionName
            val url = state.latestReleaseUrl
            if (name != null && url != null && notifier.postUpdate(ReleaseInfo(name, state.latestVersionCode, url))) {
                store.updateState {
                    it.copy(updateShownForVersion = maxOf(it.updateShownForVersion, state.latestVersionCode))
                }
                return
            }
        }

        // 3. Engaging notifications: alternate between personalized listening reminders and rotating feature tips
        // so both library/recent song nudges and feature discovery tips are delivered reliably.
        val canEngage = allowed(NotificationPolicy.Kind.ENGAGEMENT, now, prefs, state, appInForeground, NotificationChannels.TIPS)
        val canTip = allowed(NotificationPolicy.Kind.FEATURE, now, prefs, state, appInForeground, NotificationChannels.TIPS)

        val prevId = state.lastEngagementTrackId
        val lastWasEngagementTrack = prevId != null && !prevId.startsWith("tip_")

        if (lastWasEngagementTrack && canTip) {
            if (postNextFeatureTip(state, now)) return
        }

        if (canEngage) {
            if (postPersonalizedEngagement(state, now)) return
        }

        if (canTip) {
            postNextFeatureTip(state, now)
        }
    }

    private suspend fun postPersonalizedEngagement(state: NotificationState, now: Long): Boolean {
        val recent = history.allTimed().map { it.first }.distinctBy { it.videoId }
        val previousTrackId = state.lastEngagementTrackId
        val playCounts = history.topPlayed(limit = 40).associateBy { it.videoId }

        val candidatePool = if (recent.isNotEmpty()) {
            recent
        } else {
            (library.likedOnce() + library.playlistsOnce().flatMap { it.tracks }).distinctBy { it.videoId }
        }

        if (candidatePool.isNotEmpty()) {
            val repeatCandidates = candidatePool.filter { track ->
                (playCounts[track.videoId]?.plays ?: 0) >= EngagementMessageFactory.REPEAT_PLAY_THRESHOLD
            }
            val poolToRotate = repeatCandidates.ifEmpty { candidatePool }
            val prevIdx = poolToRotate.indexOfFirst { it.videoId == previousTrackId }
            val selected = if (prevIdx >= 0 && poolToRotate.size > 1) {
                poolToRotate[(prevIdx + 1) % poolToRotate.size]
            } else {
                poolToRotate.firstOrNull { track -> track.videoId != previousTrackId }
                    ?: poolToRotate.first()
            }

            val plays = playCounts[selected.videoId]?.plays
                ?: if (recent.isEmpty()) EngagementMessageFactory.REPEAT_PLAY_THRESHOLD else 1
            val message = EngagementMessageFactory.create(selected, plays)
            val targetRoute = if (recent.isNotEmpty()) "recents" else "library"
            if (notifier.postTip(
                    message.title,
                    message.body,
                    route = targetRoute,
                    notificationId = GeekifyNotifier.ENGAGEMENT_NOTIFICATION_ID
                )
            ) {
                store.updateState {
                    it.copy(
                        tipHandledForVersion = maxOf(it.tipHandledForVersion, installed),
                        lastEngagementTrackId = message.trackId,
                        allSentAt = it.allSentAt.logged(now),
                        softSentAt = it.softSentAt.logged(now)
                    )
                }
                return true
            }
        } else {
            if (notifier.postTip(
                    "Your music space is waiting",
                    "Jump back into Geekify to discover trending songs, mixes, and playlists.",
                    route = "home",
                    notificationId = GeekifyNotifier.ENGAGEMENT_NOTIFICATION_ID
                )
            ) {
                store.updateState {
                    it.copy(
                        tipHandledForVersion = maxOf(it.tipHandledForVersion, installed),
                        lastEngagementTrackId = "discovery_prompt",
                        allSentAt = it.allSentAt.logged(now),
                        softSentAt = it.softSentAt.logged(now)
                    )
                }
                return true
            }
        }
        return false
    }

    private suspend fun postNextFeatureTip(state: NotificationState, now: Long): Boolean {
        val prevId = state.lastEngagementTrackId
        val prevTipIdx = featureTips.indexOfFirst { it.id == prevId }
        val nextTip = if (prevTipIdx >= 0) {
            featureTips[(prevTipIdx + 1) % featureTips.size]
        } else {
            featureTips[(state.softSentAt.size / 2) % featureTips.size]
        }
        if (notifier.postTip(
                nextTip.title,
                nextTip.body,
                route = nextTip.route,
                notificationId = GeekifyNotifier.TIP_NOTIFICATION_ID
            )
        ) {
            store.updateState {
                it.copy(
                    tipHandledForVersion = maxOf(it.tipHandledForVersion, installed),
                    lastEngagementTrackId = nextTip.id,
                    allSentAt = it.allSentAt.logged(now),
                    softSentAt = it.softSentAt.logged(now)
                )
            }
            return true
        }
        return false
    }

    /**
     * Computes the delay in milliseconds until the next soft notification check should run.
     */
    suspend fun nextFollowupDelayMs(now: Long = System.currentTimeMillis()): Long? {
        val prefs = store.prefsNow()
        if (!prefs.engagement && !prefs.tips) return null
        val state = store.stateNow()
        val lastSoft = maxOf(state.allSentAt.maxOrNull() ?: 0L, state.softSentAt.maxOrNull() ?: 0L)
        val quietRemaining = if (state.lastOpenedAt > 0L) {
            (NotificationPolicy.ENGAGEMENT_QUIET_AFTER_OPEN_MS - (now - state.lastOpenedAt)).coerceAtLeast(0L)
        } else {
            0L
        }
        val gapRemaining = if (lastSoft > 0L) {
            (NotificationPolicy.MIN_GAP_MS - (now - lastSoft)).coerceAtLeast(0L)
        } else {
            0L
        }
        val waitMs = maxOf(quietRemaining, gapRemaining, NotificationPolicy.ENGAGEMENT_QUIET_AFTER_OPEN_MS)
        return waitMs + 60_000L
    }

    /**
     * Handles a verified stable-release announcement delivered by FCM.
     */
    suspend fun handlePushedRelease(release: ReleaseInfo, now: Long = System.currentTimeMillis()) {
        if (release.versionCode <= installed ||
            AppForeground.isVisible ||
            updateManager.hasPendingOrDismissedUpdateFor(release.versionCode, release.versionName)
        ) {
            reconcile()
            return
        }

        val prefs = store.prefsNow()
        if (!prefs.updates || !notifier.canPost(NotificationChannels.UPDATES)) return

        val before = store.stateNow()
        if (before.latestVersionCode > release.versionCode ||
            before.updateShownForVersion >= release.versionCode ||
            before.dismissedVersion >= release.versionCode
        ) return

        store.updateState { current ->
            if (current.latestVersionCode > release.versionCode) current
            else current.copy(
                lastCheckedAt = now,
                latestVersionCode = release.versionCode,
                latestVersionName = release.versionName,
                latestReleaseUrl = release.pageUrl
            )
        }

        val current = store.stateNow()
        if (current.latestVersionCode != release.versionCode ||
            current.updateShownForVersion >= release.versionCode ||
            current.dismissedVersion >= release.versionCode
        ) return

        if (notifier.postUpdate(release)) {
            store.updateState {
                it.copy(
                    updateShownForVersion = maxOf(it.updateShownForVersion, release.versionCode)
                )
            }
        }
    }

    /**
     * Handles a remote tip or engagement push delivered via FCM while respecting the user's switches.
     */
    suspend fun handlePushedEngagementOrTip(
        title: String,
        body: String,
        route: String?,
        isEngagement: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        val prefs = store.prefsNow()
        if (isEngagement && !prefs.engagement) return
        if (!isEngagement && !prefs.tips) return
        if (!notifier.canPost(NotificationChannels.TIPS)) return

        val notifId = if (isEngagement) GeekifyNotifier.ENGAGEMENT_NOTIFICATION_ID else GeekifyNotifier.TIP_NOTIFICATION_ID
        if (notifier.postTip(title, body, route, notificationId = notifId)) {
            store.updateState {
                it.copy(
                    allSentAt = it.allSentAt.logged(now),
                    softSentAt = it.softSentAt.logged(now)
                )
            }
        }
    }

    /**
     * Synchronizes FCM topic subscription state when Firebase rotates or refreshes the registration
     * token. Never logs or uploads raw tokens or personal listening data to Firebase.
     */
    suspend fun onFcmTokenRefreshed(
        token: String,
        syncTopicSubscription: (updatesEnabled: Boolean) -> Unit
    ): Boolean {
        if (token.isBlank()) return false
        val prefs = store.prefsNow()
        syncTopicSubscription(prefs.updates)
        return true
    }

    /** Called when the app starts or package is replaced: if the user has installed the announced version, its notification must go away. */
    suspend fun reconcile() = reconcile(store.stateNow())

    private suspend fun reconcile(state: NotificationState) {
        if (state.updateShownForVersion in 1..installed || state.latestVersionCode <= installed) {
            notifier.cancelUpdate()
        }
        if (state.tipHandledForVersion < installed ||
            state.updateShownForVersion < installed ||
            state.dismissedVersion < installed
        ) {
            val upgradedVersion = state.tipHandledForVersion < installed
            store.updateState { current ->
                current.copy(
                    updateShownForVersion = maxOf(current.updateShownForVersion, installed),
                    dismissedVersion = maxOf(current.dismissedVersion, installed),
                    tipHandledForVersion = maxOf(current.tipHandledForVersion, installed),
                    allSentAt = if (upgradedVersion) emptyList() else current.allSentAt,
                    softSentAt = if (upgradedVersion) emptyList() else current.softSentAt
                )
            }
        }
    }

    private fun allowed(
        kind: NotificationPolicy.Kind, now: Long, prefs: NotificationPrefs, state: NotificationState,
        foreground: Boolean, channel: String
    ): Boolean {
        val playback = playerController.state.value
        return NotificationPolicy.decide(
            NotificationPolicy.Input(
                kind = kind,
                now = now,
                prefs = prefs,
                state = state,
                installedVersionCode = installed,
                appInForeground = foreground,
                systemAllowed = notifier.canPost(channel),
                playbackActive = playback.isPlaying || playback.isBuffering
            )
        ) == NotificationPolicy.Decision.Allow
    }
}
