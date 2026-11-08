package com.droiddeck.launcher.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineBundleTest {
    @Test fun validatesPayloadNamesSizesVersionsAndChecksums() {
        val digest = "a".repeat(64)
        val payload = OfflineBundle.validatePayload("runtime", "payload/runtime.tar.zst", 1, digest, "runtime-v1", "runtime.tar.zst")
        assertTrue(payload.sha256 == digest)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBundle.validatePayload("steam", "../steam.tar", 1, digest, "user-supplied", "steam.tar")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBundle.validatePayload("desktop", "payload/desktop.tar.zst", 1, "bad", "desktop-v1", "desktop.tar.zst")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBundle.validatePayload("proot", "payload/proot.tar.zst", 0, digest, "proot-v1", "proot.tar.zst")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBundle.validatePayload("directaudio", "payload/directaudio.tar.zst", 1, digest, "", "directaudio.tar.zst")
        }
    }

    @Test fun verifiesPayloadSizeAndSha256BeforeInstall() {
        val file = File.createTempFile("droiddeck-offline-", ".payload")
        try {
            file.writeText("verified")
            val sha = MessageDigest.getInstance("SHA-256").digest("verified".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val payload = OfflineBundle.Payload("payload/runtime.tar.zst", file.length(), sha, "runtime-v1")
            assertTrue(OfflineBundle.verify(file, payload))
            assertFalse(OfflineBundle.verify(file, payload.copy(sha256 = "0".repeat(64))))
            assertFalse(OfflineBundle.verify(file, payload.copy(size = file.length() + 1)))
        } finally {
            file.delete()
        }
    }
}
