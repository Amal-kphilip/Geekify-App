package com.geekify.android.update

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.BuildConfig
import com.geekify.android.settings.AudioQualityPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdateState {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    METERED_WARNING,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALLING,
    DOWNLOAD_FAILED,
    VERIFY_FAILED,
    INSTALL_FAILED,
    INSTALL_CANCELLED,
    COMPLETED
}

@Immutable
data class UpdateUiState(
    val isDialogVisible: Boolean = false,
    val state: UpdateState = UpdateState.CHECKING,
    val installedVersionName: String = BuildConfig.VERSION_NAME,
    val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    val availableUpdate: AvailableUpdate? = null,
    val recentChangelog: List<ReleaseChangelogEntry> = emptyList(),
    val expandedChangelogVersions: Set<String> = emptySet(),
    val downloadProgress: DownloadProgressSnapshot = DownloadProgressSnapshot(
        downloadedBytes = 0L,
        totalBytes = null
    ),
    val verifiedApkFile: File? = null,
    val verifiedSha256Hex: String? = null,
    val awaitingUnknownSourcesPermission: Boolean = false,
    val installerLaunchedAtMs: Long = 0L,
    val completedUpdateInfo: CompletedUpdateInfo? = null,
    val errorTitle: String? = null,
    val errorMessage: String? = null,
    val autoInstallAfterVerify: Boolean = true
) {
    val targetVersionName: String
        get() = availableUpdate?.versionName
            ?: completedUpdateInfo?.newVersionName
            ?: installedVersionName

    val progressFraction: Float
        get() = downloadProgress.progressFraction ?: 0f

    val isIndeterminateDownload: Boolean
        get() = state == UpdateState.DOWNLOADING && (downloadProgress.totalBytes == null || downloadProgress.totalBytes <= 0L)
}

/**
 * Application-scoped coordinator that owns the update state and active download job so
 * rotating the screen or temporarily dismissing the update dialog never orphans or restarts
 * an in-progress APK download.
 */
@Singleton
class UpdateSessionCoordinator @Inject constructor(
    private val updateManager: AppUpdateManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var startupCheckPerformed = false

    fun performStartupCheckIfNeeded() {
        if (startupCheckPerformed) return
        startupCheckPerformed = true

        // 1. Check if the user just finished installing an update from a previous session
        val completed = updateManager.consumeCompletedUpdateIfMatched(
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE
        )
        if (completed != null) {
            scope.launch {
                val recent = updateManager.fetchRecentChangelog(BuildConfig.VERSION_NAME)
                _uiState.update { current ->
                    current.copy(
                        isDialogVisible = true,
                        state = UpdateState.COMPLETED,
                        completedUpdateInfo = completed,
                        recentChangelog = recent,
                        expandedChangelogVersions = setOf(completed.newVersionName)
                    )
                }
            }
            return
        }

        // 2. Otherwise check GitHub silently in the background
        checkForUpdates(userInitiated = false)
    }

    fun checkForUpdates(userInitiated: Boolean) {
        // If a download or verification is already running, simply surface the dialog!
        val currentState = _uiState.value
        if (currentState.state == UpdateState.DOWNLOADING || currentState.state == UpdateState.VERIFYING) {
            if (userInitiated) {
                _uiState.update { it.copy(isDialogVisible = true) }
            }
            return
        }

        if (checkJob?.isActive == true) {
            if (userInitiated) {
                _uiState.update { it.copy(isDialogVisible = true) }
            }
            return
        }

        if (userInitiated) {
            updateManager.clearDismissedVersion()
            _uiState.update {
                it.copy(
                    isDialogVisible = true,
                    state = UpdateState.CHECKING,
                    errorTitle = null,
                    errorMessage = null
                )
            }
        }

        checkJob = scope.launch {
            if (!updateManager.isNetworkAvailable()) {
                if (userInitiated) {
                    _uiState.update {
                        it.copy(
                            isDialogVisible = true,
                            state = UpdateState.DOWNLOAD_FAILED,
                            errorTitle = "No internet connection",
                            errorMessage = "Connect to Wi-Fi or mobile data and try checking for updates again."
                        )
                    }
                }
                return@launch
            }

            val update = runCatching {
                updateManager.checkForUpdate(
                    currentVersion = BuildConfig.VERSION_NAME,
                    ignoreDismissed = userInitiated
                )
            }.getOrNull()

            if (update != null) {
                val existingApk = updateManager.getExistingVerifiedApk(update)
                val initialExpanded = setOf(update.versionName)
                _uiState.update { current ->
                    current.copy(
                        isDialogVisible = true,
                        state = if (existingApk != null) UpdateState.READY_TO_INSTALL else UpdateState.UPDATE_AVAILABLE,
                        availableUpdate = update,
                        recentChangelog = update.changelogTimeline,
                        expandedChangelogVersions = initialExpanded,
                        verifiedApkFile = existingApk,
                        verifiedSha256Hex = if (existingApk != null) update.expectedSha256 else null,
                        downloadProgress = if (existingApk != null) {
                            DownloadProgressSnapshot(
                                downloadedBytes = existingApk.length(),
                                totalBytes = existingApk.length()
                            )
                        } else {
                            DownloadProgressSnapshot(downloadedBytes = 0L, totalBytes = update.apkSizeBytes)
                        },
                        errorTitle = null,
                        errorMessage = null
                    )
                }
            } else if (userInitiated) {
                val timeline = updateManager.fetchRecentChangelog(BuildConfig.VERSION_NAME)
                val firstExpanded = timeline.firstOrNull()?.versionName?.let { setOf(it) }.orEmpty()
                _uiState.update { current ->
                    current.copy(
                        isDialogVisible = true,
                        state = UpdateState.UP_TO_DATE,
                        availableUpdate = null,
                        recentChangelog = timeline,
                        expandedChangelogVersions = firstExpanded,
                        errorTitle = null,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun toggleChangelogReleaseExpanded(versionName: String) {
        _uiState.update { current ->
            val next = current.expandedChangelogVersions.toMutableSet()
            if (!next.add(versionName)) {
                next.remove(versionName)
            }
            current.copy(expandedChangelogVersions = next)
        }
    }

    fun startDownloadFlow(
        bypassMeteredWarning: Boolean = false,
        audioQualityPreference: AudioQualityPreference = AudioQualityPreference.AUTO
    ) {
        val current = _uiState.value
        val update = current.availableUpdate ?: return
        if (downloadJob?.isActive == true) return

        // If a verified APK is already staged on disk, jump straight to installation
        val existingVerified = current.verifiedApkFile
        if (existingVerified != null && existingVerified.exists() && existingVerified.length() > 0L) {
            proceedToInstall(existingVerified)
            return
        }

        if (!updateManager.isNetworkAvailable()) {
            _uiState.update {
                it.copy(
                    state = UpdateState.DOWNLOAD_FAILED,
                    errorTitle = "Network unavailable",
                    errorMessage = "Please check your internet connection and retry the update download."
                )
            }
            return
        }

        // Warn when on a metered network if Data Saver is active or APK is > 20 MB and not yet confirmed
        val isMetered = updateManager.isActiveNetworkMetered()
        val shouldWarnMetered = isMetered && !bypassMeteredWarning && (
            audioQualityPreference == AudioQualityPreference.LOW ||
                (update.apkSizeBytes ?: 0L) >= 15L * 1024L * 1024L
            )
        if (shouldWarnMetered) {
            _uiState.update {
                it.copy(
                    state = UpdateState.METERED_WARNING,
                    errorTitle = null,
                    errorMessage = null
                )
            }
            return
        }

        downloadJob = scope.launch {
            _uiState.update {
                it.copy(
                    state = UpdateState.DOWNLOADING,
                    errorTitle = null,
                    errorMessage = null,
                    downloadProgress = DownloadProgressSnapshot(
                        downloadedBytes = it.downloadProgress.downloadedBytes,
                        totalBytes = update.apkSizeBytes
                    )
                )
            }

            try {
                // Check if already verified on disk first
                val preVerified = updateManager.getExistingVerifiedApk(update)
                val downloadedFile = preVerified ?: updateManager.downloadApk(update) { snapshot ->
                    _uiState.update { state ->
                        // Ensure monotonic progress when totalBytes is known
                        val prevBytes = state.downloadProgress.downloadedBytes
                        val nextBytes = if (snapshot.isResumed || snapshot.downloadedBytes >= prevBytes) {
                            snapshot.downloadedBytes
                        } else {
                            maxOf(prevBytes, snapshot.downloadedBytes)
                        }
                        state.copy(
                            state = UpdateState.DOWNLOADING,
                            downloadProgress = snapshot.copy(downloadedBytes = nextBytes)
                        )
                    }
                }

                // Transition explicitly to VERIFYING stage so the user sees integrity & signature checks
                _uiState.update {
                    it.copy(
                        state = UpdateState.VERIFYING,
                        downloadProgress = DownloadProgressSnapshot(
                            downloadedBytes = downloadedFile.length(),
                            totalBytes = downloadedFile.length(),
                            bytesPerSecond = 0L,
                            etaSeconds = 0L
                        )
                    )
                }

                // Brief minimum dwell time (420ms) so the VERIFYING transition is visually smooth, not a 1-frame flash
                val verifyStartMs = System.currentTimeMillis()
                val verification = updateManager.verifyDownloadedApk(
                    apkFile = downloadedFile,
                    expectedSha256 = update.expectedSha256,
                    expectedSizeBytes = update.apkSizeBytes
                )
                val elapsedMs = System.currentTimeMillis() - verifyStartMs
                if (elapsedMs < 420L) {
                    delay(420L - elapsedMs)
                }

                when (verification) {
                    is ApkVerificationResult.Verified -> {
                        _uiState.update {
                            it.copy(
                                state = UpdateState.READY_TO_INSTALL,
                                verifiedApkFile = verification.apkFile,
                                verifiedSha256Hex = verification.sha256Hex,
                                errorTitle = null,
                                errorMessage = null
                            )
                        }
                        if (_uiState.value.autoInstallAfterVerify && _uiState.value.isDialogVisible) {
                            delay(260L)
                            proceedToInstall(verification.apkFile)
                        }
                    }
                    is ApkVerificationResult.Failed -> {
                        _uiState.update {
                            it.copy(
                                state = UpdateState.VERIFY_FAILED,
                                verifiedApkFile = null,
                                verifiedSha256Hex = null,
                                errorTitle = "Package verification failed",
                                errorMessage = verification.reason
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        state = UpdateState.DOWNLOAD_FAILED,
                        errorTitle = "Download interrupted",
                        errorMessage = friendlyNetworkErrorMessage(error)
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { current ->
            if (current.availableUpdate != null) {
                current.copy(
                    state = UpdateState.UPDATE_AVAILABLE,
                    errorTitle = null,
                    errorMessage = null
                )
            } else {
                current.copy(isDialogVisible = false)
            }
        }
    }

    fun proceedToInstall(apkOverride: File? = null) {
        val current = _uiState.value
        val update = current.availableUpdate
        val apk = apkOverride ?: current.verifiedApkFile
        if (apk == null || !apk.exists()) {
            _uiState.update {
                it.copy(
                    state = UpdateState.DOWNLOAD_FAILED,
                    verifiedApkFile = null,
                    errorTitle = "Update file missing",
                    errorMessage = "The downloaded APK was removed from cache. Tap Retry to download it again."
                )
            }
            return
        }

        val targetVersionName = update?.versionName ?: current.targetVersionName
        val targetVersionCode = update?.versionCode ?: ReleaseParserVersionCodeFallback(targetVersionName)

        when (
            val launchResult = updateManager.launchInstaller(
                apk = apk,
                targetVersionName = targetVersionName,
                targetVersionCode = targetVersionCode,
                currentVersionName = BuildConfig.VERSION_NAME
            )
        ) {
            is InstallLaunchResult.LaunchedInstaller -> {
                _uiState.update {
                    it.copy(
                        state = UpdateState.INSTALLING,
                        awaitingUnknownSourcesPermission = false,
                        installerLaunchedAtMs = System.currentTimeMillis(),
                        errorTitle = null,
                        errorMessage = null
                    )
                }
            }
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                _uiState.update {
                    it.copy(
                        state = UpdateState.INSTALLING,
                        awaitingUnknownSourcesPermission = true,
                        installerLaunchedAtMs = 0L,
                        errorTitle = null,
                        errorMessage = null
                    )
                }
            }
            is InstallLaunchResult.Failed -> {
                _uiState.update {
                    it.copy(
                        state = UpdateState.INSTALL_FAILED,
                        awaitingUnknownSourcesPermission = false,
                        errorTitle = "Installer could not start",
                        errorMessage = launchResult.reason
                    )
                }
            }
        }
    }

    /**
     * Called when Geekify's MainActivity returns to RESUMED state.
     * Reconciles:
     * 1. Return from Settings -> "Allow from this source" permission screen
     * 2. Return from Android system PackageInstaller (either completed or user cancelled)
     */
    fun onAppResumed() {
        val current = _uiState.value

        // Check if an update actually completed (e.g. if process wasn't killed or version now matches)
        val completed = updateManager.consumeCompletedUpdateIfMatched(
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE
        )
        if (completed != null) {
            _uiState.update {
                it.copy(
                    state = UpdateState.COMPLETED,
                    completedUpdateInfo = completed,
                    awaitingUnknownSourcesPermission = false,
                    installerLaunchedAtMs = 0L
                )
            }
            return
        }

        if (current.state != UpdateState.INSTALLING) return

        if (current.awaitingUnknownSourcesPermission) {
            if (updateManager.canRequestPackageInstalls()) {
                val apk = current.verifiedApkFile
                if (apk != null && apk.exists()) {
                    proceedToInstall(apk)
                } else {
                    _uiState.update {
                        it.copy(
                            state = UpdateState.READY_TO_INSTALL,
                            awaitingUnknownSourcesPermission = false
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        state = UpdateState.INSTALL_CANCELLED,
                        awaitingUnknownSourcesPermission = false,
                        errorTitle = "Install permission required",
                        errorMessage = "Geekify needs permission to install update packages. Tap Install Update to grant permission."
                    )
                }
            }
            return
        }

        // If the system installer was launched at least 800ms ago and the user returned to Geekify
        // still running the old version, the user dismissed/cancelled the system package installer prompt.
        val elapsedSinceLaunch = System.currentTimeMillis() - current.installerLaunchedAtMs
        if (current.installerLaunchedAtMs > 0L && elapsedSinceLaunch >= 800L) {
            updateManager.clearPendingInstall()
            _uiState.update {
                it.copy(
                    state = UpdateState.INSTALL_CANCELLED,
                    installerLaunchedAtMs = 0L,
                    errorTitle = "Installation was not completed",
                    errorMessage = "The update package is still verified and saved. Tap Install Update whenever you're ready."
                )
            }
        }
    }

    fun dismissDialog(rememberSkipForVersion: Boolean = false) {
        val current = _uiState.value
        if (rememberSkipForVersion &&
            (current.state == UpdateState.UPDATE_AVAILABLE || current.state == UpdateState.METERED_WARNING)
        ) {
            current.availableUpdate?.versionName?.let(updateManager::dismissVersion)
        }
        _uiState.update { it.copy(isDialogVisible = false) }
    }

    fun showDialogIfAvailable() {
        val current = _uiState.value
        if (current.availableUpdate != null || current.state == UpdateState.DOWNLOADING) {
            _uiState.update { it.copy(isDialogVisible = true) }
        } else {
            checkForUpdates(userInitiated = true)
        }
    }

    private fun friendlyNetworkErrorMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("timeout", ignoreCase = true) ->
                "The connection timed out while downloading the update. Your partial progress was saved—tap Retry to resume."
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHostException", ignoreCase = true) ->
                "Could not reach GitHub Releases. Check your network connection and tap Retry."
            raw.contains("ENOSPC", ignoreCase = true) || raw.contains("No space left", ignoreCase = true) ->
                "Not enough free storage space to download the update package."
            raw.isNotBlank() -> raw
            else -> "A network error interrupted the download. Tap Retry to resume."
        }
    }

    private fun ReleaseParserVersionCodeFallback(versionName: String): Int =
        com.geekify.android.notifications.ReleaseParser.versionCodeFor(versionName) ?: 0
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val coordinator: UpdateSessionCoordinator
) : ViewModel() {

    val uiState: StateFlow<UpdateUiState> = coordinator.uiState

    fun performStartupCheckIfNeeded() {
        coordinator.performStartupCheckIfNeeded()
    }

    fun checkForUpdates(userInitiated: Boolean = true) {
        coordinator.checkForUpdates(userInitiated = userInitiated)
    }

    fun startDownloadFlow(
        bypassMeteredWarning: Boolean = false,
        audioQualityPreference: AudioQualityPreference = AudioQualityPreference.AUTO
    ) {
        coordinator.startDownloadFlow(
            bypassMeteredWarning = bypassMeteredWarning,
            audioQualityPreference = audioQualityPreference
        )
    }

    fun cancelDownload() {
        coordinator.cancelDownload()
    }

    fun proceedToInstall() {
        coordinator.proceedToInstall()
    }

    fun toggleChangelogReleaseExpanded(versionName: String) {
        coordinator.toggleChangelogReleaseExpanded(versionName)
    }

    fun onAppResumed() {
        coordinator.onAppResumed()
    }

    fun dismissDialog(rememberSkipForVersion: Boolean = false) {
        coordinator.dismissDialog(rememberSkipForVersion = rememberSkipForVersion)
    }

    fun reopenMeteredToOverview() {
        viewModelScope.launch {
            if (uiState.value.state == UpdateState.METERED_WARNING) {
                coordinator.cancelDownload()
            }
        }
    }
}
