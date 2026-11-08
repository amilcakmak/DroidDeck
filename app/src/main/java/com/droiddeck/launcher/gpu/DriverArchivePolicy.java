package com.droiddeck.launcher.gpu;

final class DriverArchivePolicy {
    static final String BACKEND_NAME = "libpanvk_backend.so";

    private DriverArchivePolicy() {}

    static boolean isMainLibrary(String name) {
        return name != null && name.matches("libvulkan_[A-Za-z0-9][A-Za-z0-9_.-]*\\.so");
    }

    static boolean isBackendLibrary(String name) {
        return BACKEND_NAME.equals(name);
    }

    static boolean requiresBackend(String sourceLibraryName) {
        return "libvulkan_droiddeck_mali.so".equals(sourceLibraryName);
    }

    static boolean isMaliRuntimeLibrary(String name) {
        return requiresBackend(name) || (isMainLibrary(name) && name.startsWith("libvulkan_panfrost"));
    }
}
