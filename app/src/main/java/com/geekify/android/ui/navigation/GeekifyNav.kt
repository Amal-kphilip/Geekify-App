package com.geekify.android.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Liked : Screen("liked")
    object Playlist : Screen("playlist/{id}/{name}") {
        fun createRoute(id: String, name: String) = "playlist/$id/$name"
    }
    object Artist : Screen("artist/{id}") {
        fun createRoute(id: String) = "artist/$id"
    }
    object Collection : Screen("collection/{id}/{kind}") {
        fun createRoute(id: String, kind: String) = "collection/$id/$kind"
    }
    object Health : Screen("health")
    object Recents : Screen("recents")
    object Settings : Screen("settings")
    object EditProfile : Screen("edit_profile")
    object AudioSettings : Screen("audio_settings")
    object AppIconSettings : Screen("app_icon_settings")
    object ListenTogether : Screen("listen_together")
    object AddAccount : Screen("add_account")
}
