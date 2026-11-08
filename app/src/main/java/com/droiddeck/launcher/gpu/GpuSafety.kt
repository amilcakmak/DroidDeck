package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.session.SessionPrefs

object GpuSafety {
    private const val TAG = "GpuSafety"

    const val AUTO = SessionPrefs.GPU_SAFETY_AUTO
    const val SOFTWARE = SessionPrefs.GPU_SAFETY_SOFTWARE
    const val MALI = SessionPrefs.GPU_SAFETY_MALI
    val values get() = listOf(AUTO, SOFTWARE, MALI)

    fun profile(context: Context): String = SessionPrefs.gpuSafety(context).takeIf { it in values } ?: AUTO

    fun setProfile(context: Context, value: String) {
        SessionPrefs.setGpuSafety(context, value.takeIf { it in values } ?: AUTO)
    }

    internal fun software(
        profile: String,
        mali: Boolean,
        panvkSupported: Boolean = true,
        panvkInstalled: Boolean = true,
    ): Boolean = when (profile) {
        SOFTWARE -> true
        MALI -> mali && !(panvkSupported && panvkInstalled)
        else -> mali
    }

    internal fun profileForDriver(maliDriver: Boolean, softwareDriver: Boolean): String = when {
        maliDriver -> MALI
        softwareDriver -> SOFTWARE
        else -> AUTO
    }

    @JvmStatic
    fun software(context: Context): Boolean {
        val profile = profile(context)
        val mali = DeviceSupport.mali()
        val supportsPanvk = Build.VERSION.SDK_INT >= 35
        val panvkInstalled = profile == MALI && mali && supportsPanvk &&
            LinuxVulkanDriverManager(context).isMaliRuntimeDriver(SessionPrefs.linuxDriver(context))
        return software(profile, mali, supportsPanvk, panvkInstalled)
    }

    fun softwareDriverId(context: Context): String? {
        val manager = LinuxVulkanDriverManager(context)
        return manager.enumerateInstalledDrivers().firstOrNull(manager::isSoftwareDriver)
    }

    @JvmStatic
    fun linuxDriver(context: Context): String {
        val stored = SessionPrefs.linuxDriver(context)
        if (!software(context)) return stored
        val id = softwareDriverId(context)
        if (id == null) {
            Log.w(TAG, "software mode has no imported lavapipe driver")
            return ""
        }
        if (stored.isNotEmpty() && stored != id) Log.i(TAG, "software mode uses $id instead of $stored")
        return id
    }
}