package com.geekify.android.notifications

/** True while a Geekify activity is visible. Set by MainActivity; read by the notification policy. */
object AppForeground {
    @Volatile var isVisible: Boolean = false
}
