package com.geekify.android.notifications

import com.geekify.android.BuildConfig
import com.geekify.android.data.local.HistoryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of "should Geekify say anything right now?". Called by the periodic worker (about twice a day).
 * Order = priority: update first, then the what's-new tip, then (opt-in) library engagement.
 * At most ONE notification is posted per pass.
 */
@Singleton
class NotificationCoordinator @Inject constructor(
    private val store: NotificationStore,
    private val checker: UpdateChecker,
    private val notifier: GeekifyNotifier,
    private val history: HistoryRepository
) {
    private val installed = BuildConfig.VERSION_CODE

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
                    route = null
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
            if (notifier.postTip("Geekify updated to v${BuildConfig.VERSION_NAME}", "See what's new in your music experience.", route = null)) {
                store.updateState {
                    it.copy(tipHandledForVersion = installed, allSentAt = it.allSentAt.logged(now), softSentAt = it.softSentAt.logged(now))
                }
                return
            }
        } else if (state.tipHandledForVersion in 1 until installed && !prefs.tips) {
            // Tips are off: mark the version handled so it is not announced later if the user turns tips back on.
            store.updateState { it.copy(tipHandledForVersion = installed) }
        }

        // 4. Opt-in library nudge, only if there is genuinely something to continue.
        if (allowed(NotificationPolicy.Kind.ENGAGEMENT, now, prefs, state, appInForeground, NotificationChannels.TIPS)) {
            val last = history.recent.first().firstOrNull() ?: return
            if (notifier.postTip(
                    "Rediscover your music",
                    "Pick up where you left off with \"${last.title}\" and your recent songs.",
                    route = "recents"
                )
            ) {
                store.updateState { it.copy(allSentAt = it.allSentAt.logged(now), softSentAt = it.softSentAt.logged(now)) }
            }
        }
    }

    /**
     * Handles a verified stable-release announcement delivered by FCM.
     *
     * Unlike the periodic fallback, this path does not call GitHub before notifying: high-priority
     * FCM delivery is intended to show the user-visible alert immediately. The tapped notification
     * then launches MainScreen, which performs the authoritative update check against GitHub.
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

    /** Called when the app starts: if the user has installed the announced version, its notification must go away. */
    suspend fun reconcile() = reconcile(store.stateNow())

    private fun reconcile(state: NotificationState) {
        if (state.updateShownForVersion in 1..installed) notifier.cancelUpdate()
    }

    private fun allowed(
        kind: NotificationPolicy.Kind, now: Long, prefs: NotificationPrefs, state: NotificationState,
        foreground: Boolean, channel: String
    ): Boolean = NotificationPolicy.decide(
        NotificationPolicy.Input(kind, now, prefs, state, installed, foreground, notifier.canPost(channel))
    ) == NotificationPolicy.Decision.Allow
}
