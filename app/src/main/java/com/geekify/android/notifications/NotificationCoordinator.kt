package com.geekify.android.notifications

import com.geekify.android.BuildConfig
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.player.PlayerController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of "should Geekify say anything right now?". Called by the periodic worker and follow-up kicks.
 * Order = priority: update first, then the what's-new tip, then personalized library/listening engagement,
 * then rotating feature tips. At most ONE notification is posted per pass.
 */
@Singleton
class NotificationCoordinator @Inject constructor(
    private val store: NotificationStore,
    private val checker: UpdateChecker,
    private val notifier: GeekifyNotifier,
    private val history: HistoryRepository,
    private val library: LibraryRepository,
    private val playerController: PlayerController
) {
    private val installed = BuildConfig.VERSION_CODE

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
        )
    )

    suspend fun runOnce(now: Long = System.currentTimeMillis(), appInForeground: Boolean = AppForeground.isVisible) {
        // 1. Refresh the cached release info (rate limited inside) and drop an update notice that is now obsolete.
        var state = checker.refreshIfDue(now)
        reconcile(state)

        val prefs = store.prefsNow()

        // 2. Update notification.
        if (allowed(NotificationPolicy.Kind.UPDATE, now, prefs, state, appInForeground, NotificationChannels.UPDATES)) {
            val name = state.latestVersionName
            val url = state.latestReleaseUrl
            if (name != null && url != null && notifier.postUpdate(ReleaseInfo(name, state.latestVersionCode, url))) {
                store.updateState {
                    it.copy(updateShownForVersion = state.latestVersionCode, allSentAt = it.allSentAt.logged(now))
                }
                return
            }
        }

        // First run: give the existing Tips notification path one real, one-time notification.
        // It is only posted after the app is backgrounded and only when Tips are enabled.
        state = store.stateNow()
        val firstTipPending =
            state.tipHandledForVersion == 0 ||
                (state.tipHandledForVersion == installed && state.allSentAt.isEmpty())
        if (firstTipPending &&
            allowed(NotificationPolicy.Kind.FEATURE, now, prefs, state, appInForeground, NotificationChannels.TIPS)
        ) {
            if (notifier.postTip(
                    "Welcome to Geekify",
                    "Your music is ready. Tap to open Geekify.",
                    route = null,
                    notificationId = GeekifyNotifier.TIP_NOTIFICATION_ID
                )
            ) {
                store.updateState {
                    it.copy(
                        tipHandledForVersion = installed,
                        allSentAt = it.allSentAt.logged(now),
                        softSentAt = it.softSentAt.logged(now)
                    )
                }
                return
            }
        } else if (firstTipPending && !prefs.tips) {
            store.updateState { it.copy(tipHandledForVersion = installed) }
            state = store.stateNow()
        }

        // 3. "What's new" once after the app was updated.
        if (state.tipHandledForVersion in 1 until installed &&
            allowed(NotificationPolicy.Kind.FEATURE, now, prefs, state, appInForeground, NotificationChannels.TIPS)
        ) {
            if (notifier.postTip(
                    "Geekify updated to v${BuildConfig.VERSION_NAME}",
                    "See what's new in your music experience.",
                    route = null,
                    notificationId = GeekifyNotifier.TIP_NOTIFICATION_ID
                )
            ) {
                store.updateState {
                    it.copy(tipHandledForVersion = installed, allSentAt = it.allSentAt.logged(now), softSentAt = it.softSentAt.logged(now))
                }
                return
            }
        } else if (state.tipHandledForVersion in 1 until installed && !prefs.tips) {
            // Tips are off: mark the version handled so it is not announced later if the user turns tips back on.
            store.updateState { it.copy(tipHandledForVersion = installed) }
            state = store.stateNow()
        }

        // 4. Locally personalized listening reminder. Uses on-device history/play counts first,
        // falls back to liked/playlist tracks, and finally a discovery prompt if the library is brand new.
        if (allowed(NotificationPolicy.Kind.ENGAGEMENT, now, prefs, state, appInForeground, NotificationChannels.TIPS)) {
            val recent = history.allTimed().map { it.first }
            val previousTrackId = state.lastEngagementTrackId
            val playCounts = history.topPlayed(limit = 40).associateBy { it.videoId }

            val candidatePool = if (recent.isNotEmpty()) {
                recent
            } else {
                (library.likedOnce() + library.playlistsOnce().flatMap { it.tracks }).distinctBy { it.videoId }
            }

            if (candidatePool.isNotEmpty()) {
                val selected = candidatePool.firstOrNull { track ->
                    track.videoId != previousTrackId && (playCounts[track.videoId]?.plays ?: 0) >= 3
                } ?: candidatePool.firstOrNull { track -> track.videoId != previousTrackId }
                    ?: candidatePool.first()

                val plays = playCounts[selected.videoId]?.plays ?: if (recent.isEmpty()) 3 else 1
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
                            lastEngagementTrackId = message.trackId,
                            allSentAt = it.allSentAt.logged(now),
                            softSentAt = it.softSentAt.logged(now)
                        )
                    }
                    return
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
                            lastEngagementTrackId = "discovery_prompt",
                            allSentAt = it.allSentAt.logged(now),
                            softSentAt = it.softSentAt.logged(now)
                        )
                    }
                    return
                }
            }
        }

        // 5. Periodic rotating feature tip when Tips are enabled and no other notification was sent.
        if (allowed(NotificationPolicy.Kind.FEATURE, now, prefs, state, appInForeground, NotificationChannels.TIPS)) {
            val prevId = state.lastEngagementTrackId
            val nextTip = featureTips.firstOrNull { it.id != prevId } ?: featureTips.first()
            if (notifier.postTip(
                    nextTip.title,
                    nextTip.body,
                    route = nextTip.route,
                    notificationId = GeekifyNotifier.TIP_NOTIFICATION_ID
                )
            ) {
                store.updateState {
                    it.copy(
                        tipHandledForVersion = installed,
                        lastEngagementTrackId = nextTip.id,
                        allSentAt = it.allSentAt.logged(now),
                        softSentAt = it.softSentAt.logged(now)
                    )
                }
            }
        }
    }

    /**
     * Handles a verified stable-release announcement delivered by FCM.
     */
    suspend fun handlePushedRelease(release: ReleaseInfo, now: Long = System.currentTimeMillis()) {
        if (release.versionCode <= installed) {
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
                    updateShownForVersion = maxOf(it.updateShownForVersion, release.versionCode),
                    allSentAt = it.allSentAt.logged(now)
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

    /** Called when the app starts: if the user has installed the announced version, its notification must go away. */
    suspend fun reconcile() = reconcile(store.stateNow())

    private fun reconcile(state: NotificationState) {
        if (state.updateShownForVersion in 1..installed) notifier.cancelUpdate()
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
