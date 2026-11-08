package com.droiddeck.launcher.gpu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class DriverArchivePolicyTest {
    @Test public void panvkNeedsItsPrivateBackendAndIsNotTurnip() {
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_droiddeck_mali.so"));
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so"));
        assertTrue(DriverArchivePolicy.requiresBackend("libvulkan_droiddeck_mali.so"));
        assertFalse(DriverArchivePolicy.requiresBackend("libvulkan_freedreno.so"));
        assertTrue(DriverArchivePolicy.isBackendLibrary("libpanvk_backend.so"));
        assertTrue(DriverArchivePolicy.isMaliRuntimeLibrary("libvulkan_droiddeck_mali.so"));
        assertTrue(DriverArchivePolicy.isMaliRuntimeLibrary("libvulkan_panfrost.so"));
        assertFalse(DriverArchivePolicy.isMaliRuntimeLibrary("libvulkan_freedreno.so"));
    }

    @Test public void archiveNamesMustBeSafeBasenames() {
        assertFalse(DriverArchivePolicy.isMainLibrary("../libvulkan_panfrost.so"));
        assertFalse(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so/extra"));
        assertFalse(DriverArchivePolicy.isBackendLibrary("../libpanvk_backend.so"));
        assertFalse(DriverArchivePolicy.isMaliRuntimeLibrary(null));
    }
}