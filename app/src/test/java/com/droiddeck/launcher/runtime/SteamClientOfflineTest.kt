package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamClientOfflineTest {
    @Test fun acceptsClientFilesButRejectsAccountAndGameData() {
        assertEquals("steamrtarm64/steam", SteamClientOffline.safePath("steamrtarm64/steam"))
        assertEquals("steamrtarm64/config/steam.vdf", SteamClientOffline.safePath("steamrtarm64/config/steam.vdf"))
        assertNull(SteamClientOffline.safePath("../../root/.ssh/authorized_keys"))
        assertNull(SteamClientOffline.safePath("userdata/123/config/localconfig.vdf"))
        assertNull(SteamClientOffline.safePath("steamapps/common/Game/game.exe"))
        assertNull(SteamClientOffline.safePath("steamrtarm64/config/loginusers.vdf"))
        assertNull(SteamClientOffline.safePath("linuxarm64/steam.pipe"))
        assertNull(SteamClientOffline.safePath("package/ssfn1234567890123456789"))
        assertEquals("linuxarm64/libsteam.so", SteamClientOffline.safeLink("linuxarm64/libsteam.so", "libsteam.so.1"))
        assertNull(SteamClientOffline.safeLink("linuxarm64/libsteam.so", "../../steamapps/account.vdf"))
    }
}
