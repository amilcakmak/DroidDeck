package com.droiddeck.launcher.gpu;

import static org.junit.Assert.assertEquals;
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

    @Test public void softwareDriverAndItsKnownRuntimeLibrariesAreAllowed() {
        assertTrue(DriverArchivePolicy.isSoftwareLibrary("libvulkan_lvp.so"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libLLVM.so.19.1"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libedit.so.2"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libxml2.so.16"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libuntrusted.so.1"));
        assertFalse(DriverArchivePolicy.isSoftwareLibrary("libvulkan_panfrost.so"));
    }

    @Test public void archiveNamesMustBeSafeBasenames() {
        assertFalse(DriverArchivePolicy.isMainLibrary("../libvulkan_panfrost.so"));
        assertFalse(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so/extra"));
        assertFalse(DriverArchivePolicy.isBackendLibrary("../libpanvk_backend.so"));
        assertFalse(DriverArchivePolicy.isMaliRuntimeLibrary(null));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("../libLLVM.so.19.1"));
    }

    @Test public void icdVersionMustBeAValidSupportedVulkanVersion() {
        assertEquals("1.4.363", DriverArchivePolicy.apiVersion("1.4.363"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.5.0"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.9999"));
    }
}