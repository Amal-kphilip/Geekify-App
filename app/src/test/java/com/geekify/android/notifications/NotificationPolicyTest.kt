package com.geekify.android.notifications

import com.geekify.android.notifications.NotificationPolicy.Decision
import com.geekify.android.notifications.NotificationPolicy.DAY_MS
import com.geekify.android.notifications.NotificationPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private val now = 100 * DAY_MS
    private val installed = 10104
    private val allOn = NotificationPrefs(updates = true, tips = true, engagement = true)
    private val updateState = NotificationState(latestVersionCode = 10105, lastOpenedAt = now - 10 * DAY_MS)

    private fun decide(kind: Kind, state: NotificationState, prefs: NotificationPrefs = allOn,
                       fg: Boolean = false, sys: Boolean = true, inst: Int = installed) =
        NotificationPolicy.decide(NotificationPolicy.Input(kind, now, prefs, state, inst, fg, sys))

    @Test fun newUpdateIsAllowedOnce() {
        assertEquals(Decision.Allow, decide(Kind.UPDATE, updateState))
        val shown = updateState.copy(updateShownForVersion = 10105)
        assertTrue(decide(Kind.UPDATE, shown) is Decision.Deny)           // same update checked again
    }
    @Test fun dismissedUpdateDoesNotRepeat() =
        assertTrue(decide(Kind.UPDATE, updateState.copy(dismissedVersion = 10105)) is Decision.Deny)
    @Test fun alreadyUpdatedNeverNotifies() =
        assertTrue(decide(Kind.UPDATE, updateState, inst = 10105) is Decision.Deny)
    @Test fun oneNonMediaNotificationPerDay() {
        val sent = updateState.copy(latestVersionCode = 0, allSentAt = listOf(now - 3 * 3_600_000L))
        assertTrue(decide(Kind.ENGAGEMENT, sent) is Decision.Deny)
        assertEquals(Decision.Allow, decide(Kind.ENGAGEMENT, sent.copy(allSentAt = listOf(now - 25 * 3_600_000L))))
    }
    @Test fun pendingUpdateBeatsEngagementAndTips() {
        assertTrue(decide(Kind.ENGAGEMENT, updateState) is Decision.Deny)
        assertTrue(decide(Kind.FEATURE, updateState) is Decision.Deny)
    }
    @Test fun recentlyOpenedSuppressesEngagement() {
        val s = NotificationState(lastOpenedAt = now - DAY_MS)
        assertTrue(decide(Kind.ENGAGEMENT, s) is Decision.Deny)
    }
    @Test fun nothingWhileUserIsInTheApp() = assertTrue(decide(Kind.UPDATE, updateState, fg = true) is Decision.Deny)
    @Test fun systemOrPreferenceOffMeansNothing() {
        assertTrue(decide(Kind.UPDATE, updateState, sys = false) is Decision.Deny)
        assertTrue(decide(Kind.UPDATE, updateState, prefs = allOn.copy(updates = false)) is Decision.Deny)
        assertTrue(decide(Kind.ENGAGEMENT, NotificationState(), prefs = allOn.copy(engagement = false)) is Decision.Deny)
        assertTrue(decide(Kind.FEATURE, NotificationState(), prefs = allOn.copy(tips = false)) is Decision.Deny)
    }
    @Test fun weeklyCapStopsSoftNotifications() {
        val s = NotificationState(allSentAt = listOf(now - 2 * DAY_MS, now - 4 * DAY_MS), softSentAt = listOf(now - 2 * DAY_MS, now - 4 * DAY_MS))
        assertTrue(decide(Kind.ENGAGEMENT, s) is Decision.Deny)
    }
}
