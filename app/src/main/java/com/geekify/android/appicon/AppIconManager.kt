package com.geekify.android.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Manages Geekify's selectable launcher icons through activity-alias components.
 * The normal MainActivity remains the actual app activity; aliases are only used
 * as launcher entries so the icon can be changed without affecting app behavior.
 */
object AppIconManager {
    const val DEFAULT = "default"
    const val RETRO = "retro"
    const val BLUE_ORANGE = "blue_orange"
    const val DARK = "dark"
    const val PREMIUM = "premium"
    const val MONOCHROME = "monochrome"

    private const val PREFS_NAME = "geekify_app_icon"
    private const val KEY_SELECTED = "selected_icon"
    private const val DEFAULT_ALIAS = "com.geekify.android.launcher.IconDefault"

    data class IconOption(
        val id: String,
        val label: String,
        val previewRes: Int,
        val aliasClass: String
    )

    // Keep previews separate from launcher resources. The previews are small enough
    // to display safely in Compose without decoding multiple 1024px images at once.
    val options: List<IconOption> = listOf(
        IconOption(DEFAULT, "Default", com.geekify.android.R.drawable.ic_geekify_default_preview, DEFAULT_ALIAS),
        IconOption(RETRO, "Retro", com.geekify.android.R.drawable.ic_geekify_retro_preview, "com.geekify.android.launcher.IconRetro"),
        IconOption(BLUE_ORANGE, "Blue & Orange", com.geekify.android.R.drawable.ic_geekify_blue_orange_preview, "com.geekify.android.launcher.IconBlueOrange"),
        IconOption(DARK, "Dark", com.geekify.android.R.drawable.ic_geekify_dark_preview, "com.geekify.android.launcher.IconDark"),
        IconOption(PREMIUM, "Premium", com.geekify.android.R.drawable.ic_geekify_premium_preview, "com.geekify.android.launcher.IconPremium"),
        IconOption(MONOCHROME, "Monochrome", com.geekify.android.R.drawable.ic_geekify_monochrome_preview, "com.geekify.android.launcher.IconMonochrome"),
    )

    fun currentIcon(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, DEFAULT)
            ?.takeIf { id -> options.any { it.id == id } }
            ?: DEFAULT
    }

    /**
     * Changes the launcher icon. The app itself keeps running because the launcher
     * aliases are toggled with DONT_KILL_APP.
     */
    fun setIcon(context: Context, iconId: String) {
        val selected = options.firstOrNull { it.id == iconId } ?: return
        val packageManager = context.packageManager

        try {
            // Enable the new alias before disabling the old one, so there is never a
            // period where Geekify has no launcher entry.
            setEnabled(packageManager, context, selected.aliasClass, true)

            options.asSequence()
                .filter { it.aliasClass != selected.aliasClass }
                .forEach { option ->
                    setEnabled(packageManager, context, option.aliasClass, false)
                }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED, selected.id)
                .apply()
        } catch (_: SecurityException) {
            // Do not crash the app if a device/launcher/OS rejects a component-state
            // change. Keep the previous selection instead.
        } catch (_: IllegalArgumentException) {
            // Defensive fallback for an OEM package manager rejecting an alias.
        }
    }

    /** Restores Default when a previously selected launcher icon no longer exists. */
    fun ensureValidSelection(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selected = prefs.getString(KEY_SELECTED, DEFAULT)
        if (selected == null || options.none { it.id == selected }) {
            setIcon(context, DEFAULT)
        }
    }

    private fun setEnabled(
        packageManager: PackageManager,
        context: Context,
        className: String,
        enabled: Boolean
    ) {
        packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, className),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
