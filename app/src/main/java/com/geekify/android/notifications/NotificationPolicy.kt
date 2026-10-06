package com.geekify.android.notifications

/**
 * The single place that decides whether Geekify may post a NON-media notification right now.
 * Pure Kotlin (no Android classes) so every anti-spam rule is unit-tested on the JVM.
 * The media playback notification never goes through here and is never counted.
 */
object NotificationPolicy {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS
    const val WEEK_MS = 7 * DAY_MS

    /** Global rule: at most one non-media notification (update, tip or engagement) in any 24 h window. */
    const val MIN_GAP_MS = DAY_MS
    /** Tips + engagement together: at most 2 in any rolling 7 days. Update notices are one-per-version and not counted here. */
    const val MAX_SOFT_PER_WEEK = 2
    /** No "come back" style message if the app was opened in the last 3 days. */
    const val ENGAGEMENT_QUIET_AFTER_OPEN_MS = 3 * DAY_MS
    /** No tip within a day of the user having the app open. */
    const val TIP_QUIET_AFTER_OPEN_MS = DAY_MS

    enum class Kind { UPDATE, FEATURE, ENGAGEMENT }

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    data class Input(
        val kind: Kind,
        val now: Long,
        val prefs: NotificationPrefs,
        val state: NotificationState,
        val installedVersionCode: Int,
        val appInForeground: Boolean,
        /** False when the OS-level permission or the channel is switched off: we never try to bypass that. */
        val systemAllowed: Boolean
    )

    /** An update the user should still hear about: newer than installed, not yet announced, not dismissed. */
    fun isUpdatePending(prefs: NotificationPrefs, state: NotificationState, installedVersionCode: Int): Boolean =
        prefs.updates &&
            state.latestVersionCode > installedVersionCode &&
            state.updateShownForVersion < state.latestVersionCode &&
            state.dismissedVersion < state.latestVersionCode

    fun decide(i: Input): Decision {
        val s = i.state
        if (!i.systemAllowed) return Decision.Deny("notifications disabled in system settings")
        if (i.appInForeground) return Decision.Deny("user is in the app")

        when (i.kind) {
            Kind.UPDATE -> {
                if (!i.prefs.updates) return Decision.Deny("update notifications turned off")
                if (s.latestVersionCode <= i.installedVersionCode) return Decision.Deny("already up to date")
                if (s.updateShownForVersion >= s.latestVersionCode) return Decision.Deny("already notified for this version")
                if (s.dismissedVersion >= s.latestVersionCode) return Decision.Deny("user dismissed this version")
            }
            Kind.FEATURE -> {
                if (!i.prefs.tips) return Decision.Deny("tips turned off")
                if (isUpdatePending(i.prefs, s, i.installedVersionCode)) return Decision.Deny("update notification has priority")
                if (i.now - s.lastOpenedAt < TIP_QUIET_AFTER_OPEN_MS) return Decision.Deny("app opened recently")
            }
            Kind.ENGAGEMENT -> {
                if (!i.prefs.engagement) return Decision.Deny("engagement notifications turned off")
                if (isUpdatePending(i.prefs, s, i.installedVersionCode)) return Decision.Deny("update notification has priority")
                if (i.now - s.lastOpenedAt < ENGAGEMENT_QUIET_AFTER_OPEN_MS) return Decision.Deny("app opened recently")
            }
        }

        val lastAny = s.allSentAt.maxOrNull() ?: 0L
        if (i.now - lastAny < MIN_GAP_MS) return Decision.Deny("a notification was already sent in the last 24 h")

        if (i.kind != Kind.UPDATE) {
            val softThisWeek = s.softSentAt.count { i.now - it < WEEK_MS }
            if (softThisWeek >= MAX_SOFT_PER_WEEK) return Decision.Deny("weekly limit reached")
        }
        return Decision.Allow
    }
}
