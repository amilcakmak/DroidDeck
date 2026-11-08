package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class LinuxVulkanDriver {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String ENV = "BL_VK_DRIVER";

    public static final String HELP_TEXT =
            "The Vulkan driver Steam, games and desktop apps use inside the Linux runtime. Import a Linux/glibc "
            + "driver compatible with this GPU (Turnip for Adreno or PanVK for supported Mali). Android display "
            + "rendering still uses the device driver. Changes take effect at the next session start.";

    private LinuxVulkanDriver() {}

    public static List<String> optionValues(Context context) {
        ArrayList<String> values = new ArrayList<>();
        values.add("");
        values.addAll(new LinuxVulkanDriverManager(context).enumerateInstalledDrivers());
        return values;
    }

    public static String optionLabel(Context context, String value) {
        if (value == null || value.isEmpty()) return "Runtime default (built into the runtime)";
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (!m.isInstalled(value)) return value + " (imported, missing - uses the runtime default)";
        String ver = m.getDriverVersion(value);
        return m.getDriverName(value) + (ver.isEmpty() ? "" : " " + ver) + " (imported)";
    }

    public static String resolveIcdPath(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        String icd = m.getIcdPath(value);
        if (icd == null) {
            Log.w(TAG, "imported Linux driver \"" + value + "\" is missing or incomplete; using the runtime's own driver");
            return null;
        }
        if (m.isMaliRuntimeDriver(value) && !com.droiddeck.launcher.core.DeviceSupport.INSTANCE.mali()) {
            Log.w(TAG, "refusing PanVK driver \"" + value + "\" on non-Mali hardware");
            return null;
        }
        Log.i(TAG, "Linux session draws with imported driver " + value + " (" + icd + ")");
        return icd;
    }
}
