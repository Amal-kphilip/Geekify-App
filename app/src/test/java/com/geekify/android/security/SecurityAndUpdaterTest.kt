package com.geekify.android.security

import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.player.extraction.InnerTubePlaybackExtractor
import com.geekify.android.update.AppUpdateManager
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAndUpdaterTest {

    @Test
    fun `remote player config URL is pinned to a commit SHA and validates JSON`() {
        assertFalse(InnerTubePlaybackExtractor.PLAYER_CONFIG_URL.contains("/master/"))
        assertTrue(
            InnerTubePlaybackExtractor.PLAYER_CONFIG_URL.contains(
                InnerTubePlaybackExtractor.PINNED_PLAYER_CONFIG_COMMIT
            )
        )

        assertTrue(InnerTubePlaybackExtractor.isValidPlayerConfigJson("{\"version\":1,\"players\":[]}"))
        assertTrue(InnerTubePlaybackExtractor.isValidPlayerConfigJson("[{\"id\":\"abc\"}]"))
        assertFalse(InnerTubePlaybackExtractor.isValidPlayerConfigJson(""))
        assertFalse(InnerTubePlaybackExtractor.isValidPlayerConfigJson("<html>404 Not Found</html>"))
        assertFalse(InnerTubePlaybackExtractor.isValidPlayerConfigJson("{}"))
        assertFalse(InnerTubePlaybackExtractor.isValidPlayerConfigJson("not-json"))
    }

    @Test
    fun `apk updater extracts and verifies SHA-256 checksums`() {
        val sampleDigest = "a3f5c8e90123456789abcdef0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            sampleDigest,
            AppUpdateManager.extractSha256Hex("sha256:$sampleDigest")
        )
        assertEquals(
            sampleDigest,
            AppUpdateManager.extractSha256Hex("$sampleDigest  app-release.apk\n")
        )
        assertTrue(AppUpdateManager.verifyChecksum(sampleDigest, "SHA256:${sampleDigest.uppercase()}"))
        assertFalse(
            AppUpdateManager.verifyChecksum(
                sampleDigest,
                "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )
    }

    @Test
    fun `google id token nonce verification accepts matching nonce and rejects mismatched nonce`() {
        val rawNonce = AuthRepository.generateRawNonce()
        val hashedNonce = AuthRepository.sha256Hex(rawNonce)

        fun buildFakeJwt(nonceClaim: String): String {
            val header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".toByteArray(Charsets.UTF_8))
            val payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"123\",\"nonce\":\"$nonceClaim\"}".toByteArray(Charsets.UTF_8))
            return "$header.$payload.sig"
        }

        assertTrue(
            AuthRepository.verifyIdTokenNonce(
                idToken = buildFakeJwt(hashedNonce),
                hashedNonce = hashedNonce,
                rawNonce = rawNonce
            )
        )
        assertFalse(
            AuthRepository.verifyIdTokenNonce(
                idToken = buildFakeJwt("attacker-replayed-nonce"),
                hashedNonce = hashedNonce,
                rawNonce = rawNonce
            )
        )
    }
}
