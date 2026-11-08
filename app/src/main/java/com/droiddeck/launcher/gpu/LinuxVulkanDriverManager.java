package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LinuxVulkanDriverManager {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String DIR_NAME = "linux_vulkan_drivers";
    public static final String LIB_NAME = "libvulkan_freedreno.so";
    public static final String ICD_NAME = "icd.json";
    public static final String META_NAME = "meta.json";
    private static final long MAX_ARCHIVE_BYTES = DriverArchivePolicy.MAX_ARCHIVE_BYTES;
    private static final long MAX_LIBRARY_BYTES = DriverArchivePolicy.MAX_LIBRARY_BYTES;
    private static final int MAX_META_BYTES = DriverArchivePolicy.MAX_META_BYTES;

    private final Context context;
    private final File rootDir;

    public LinuxVulkanDriverManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    public File getDriverDir(String id) {
        return new File(rootDir, id);
    }

    public boolean isInstalled(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return false;
        File dir = getDriverDir(id);
        if (!new File(dir, LIB_NAME).isFile() || !new File(dir, ICD_NAME).isFile()) return false;
        JSONObject meta = readMeta(id);
        if (meta == null) return true;
        if (DriverArchivePolicy.requiresBackend(meta.optString("sourceLibraryName", ""))
                && !new File(dir, DriverArchivePolicy.BACKEND_NAME).isFile()) return false;
        org.json.JSONArray libs = meta.optJSONArray("runtimeLibraries");
        for (int i = 0; libs != null && i < libs.length(); i++) {
            String name = libs.optString(i, "");
            if (!DriverArchivePolicy.isRuntimeLibrary(name) || !new File(dir, name).isFile()) return false;
        }
        return true;
    }

    public String getIcdPath(String id) {
        return isInstalled(id) ? new File(getDriverDir(id), ICD_NAME).getAbsolutePath() : null;
    }

    public List<String> enumerateInstalledDrivers() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) if (d.isDirectory() && isInstalled(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    private JSONObject readMeta(String id) {
        try {
            File meta = new File(getDriverDir(id), META_NAME);
            if (!meta.isFile()) return null;
            return new JSONObject(FileUtils.readString(meta));
        } catch (Exception e) {
            return null;
        }
    }

    public String getDriverName(String id) {
        JSONObject m = readMeta(id);
        String name = m != null ? m.optString("name", "") : "";
        return name.isEmpty() ? id : name;
    }

    public boolean isMaliRuntimeDriver(String id) {
        JSONObject meta = readMeta(id);
        return isInstalled(id) && meta != null
                && DriverArchivePolicy.isMaliRuntimeLibrary(meta.optString("sourceLibraryName", ""));
    }

    public boolean isSoftwareDriver(String id) {
        if (!isInstalled(id)) return false;
        JSONObject meta = readMeta(id);
        return meta != null && DriverArchivePolicy.isSoftwareLibrary(meta.optString("sourceLibraryName", ""));
    }

    public String getDriverVersion(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("driverVersion", "") : "";
    }

    public String getMinGlibc(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("minGlibc", "") : "";
    }

    public void removeDriver(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return;
        Log.d(TAG, "removing imported Linux Vulkan driver " + id);
        FileUtils.delete(getDriverDir(id));
    }

    public String installDriver(Uri zipUri, String displayName) throws IOException {
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            String soName = null;
            JSONObject zipMeta = null;
            boolean backendSeen = false;
            java.util.Set<String> runtimeSeen = new java.util.LinkedHashSet<>();
            boolean metaSeen = false;
            long unpacked = 0;
            InputStream source = context.getContentResolver().openInputStream(zipUri);
            if (source == null) throw new IOException("cannot open " + zipUri);
            try (InputStream is = source; ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = new File(entry.getName()).getName();
                    long bytes;
                    if (DriverArchivePolicy.isMainLibrary(base)) {
                        if (soName != null) throw new IllegalArgumentException("driver zip must contain exactly one Vulkan library");
                        bytes = copyBounded(zis, new File(tmpDir, LIB_NAME), MAX_LIBRARY_BYTES);
                        soName = base;
                    } else if (DriverArchivePolicy.isBackendLibrary(base)) {
                        if (backendSeen) throw new IllegalArgumentException("duplicate PanVK backend in driver zip");
                        backendSeen = true;
                        bytes = copyBounded(zis, new File(tmpDir, DriverArchivePolicy.BACKEND_NAME), MAX_LIBRARY_BYTES);
                    } else if (DriverArchivePolicy.isRuntimeLibrary(base)) {
                        if (!runtimeSeen.add(base)) throw new IllegalArgumentException("duplicate runtime library in driver zip: " + base);
                        bytes = copyBounded(zis, new File(tmpDir, base),
                                Math.min(DriverArchivePolicy.MAX_RUNTIME_LIBRARY_BYTES, MAX_ARCHIVE_BYTES - unpacked));
                    } else if (META_NAME.equals(base)) {
                        if (metaSeen) throw new IllegalArgumentException("duplicate driver metadata in zip");
                        metaSeen = true;
                        byte[] data = readAllBounded(zis, MAX_META_BYTES);
                        bytes = data.length;
                        try {
                            zipMeta = new JSONObject(new String(data, StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            Log.w(TAG, "meta.json unreadable, ignoring: " + e.getMessage());
                        }
                    } else {
                        bytes = drainBounded(zis, MAX_ARCHIVE_BYTES - unpacked);
                    }
                    unpacked += bytes;
                    if (unpacked > MAX_ARCHIVE_BYTES) throw new IOException("driver zip exceeds unpacked size limit");
                }
            }
            String id = adopt(tmpDir, soName, zipMeta, displayName);
            keep = true;
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    File newStagingDir() throws IOException {
        File tmpDir = new File(rootDir, ".tmp-" + System.nanoTime());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        return tmpDir;
    }

    String adopt(File tmpDir, String soName, JSONObject zipMeta, String displayName) throws IOException {
        try {
            if (soName == null) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_no_library));
            }
            if (!DriverArchivePolicy.isMainLibrary(soName)) throw new IllegalArgumentException("invalid Vulkan library name");
            File so = new File(tmpDir, LIB_NAME);
            if (!isAarch64Elf(so)) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_not_aarch64, soName));
            }
            if (!containsAscii(so, "libc.so.6")) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_not_glibc, soName));
            }
            File backend = new File(tmpDir, DriverArchivePolicy.BACKEND_NAME);
            if (DriverArchivePolicy.requiresBackend(soName) && !backend.isFile())
                throw new IllegalArgumentException("PanVK archive is missing its required backend library");
            if (backend.isFile() && (!isAarch64Elf(backend) || !containsAscii(backend, "libc.so.6")))
                throw new IllegalArgumentException("PanVK backend must be AArch64 Linux/glibc");
            if (backend.isFile() && !DriverArchivePolicy.requiresBackend(soName))
                throw new IllegalArgumentException("PanVK backend does not match this Vulkan library");
            java.util.List<String> runtimeLibs = new java.util.ArrayList<>();
            File[] staged = tmpDir.listFiles();
            if (staged != null) {
                java.util.Arrays.sort(staged);
                for (File f : staged) {
                    if (!DriverArchivePolicy.isRuntimeLibrary(f.getName())) continue;
                    if (!isAarch64Elf(f) || !containsAscii(f, "libc.so.6"))
                        throw new IllegalArgumentException(f.getName() + " must be AArch64 Linux/glibc, not Android/Bionic");
                    runtimeLibs.add(f.getName());
                }
            }
            String kind = zipMeta != null ? zipMeta.optString("kind", "") : "";
            if (!kind.isEmpty() && !"linux-vulkan-icd".equals(kind)) {
                Log.w(TAG, "meta.json says kind=" + kind + ", importing anyway (the binary is glibc)");
            }

            String name = zipMeta != null ? zipMeta.optString("name", "") : "";
            String version = zipMeta != null ? zipMeta.optString("driverVersion", "") : "";
            String minGlibc = zipMeta != null ? zipMeta.optString("minGlibc", "") : "";
            if (name.isEmpty()) {
                name = displayName != null ? displayName : "linux-driver";
                if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String id = uniqueId(sanitizeId(name));
            File dir = getDriverDir(id);

            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject icdBody = new JSONObject();
            icdBody.put("library_path", new File(dir, LIB_NAME).getAbsolutePath());
            String apiVersion = DriverArchivePolicy.apiVersion(zipMeta != null ? zipMeta.optString("vulkanApiVersion", "") : "");
            icdBody.put("api_version", apiVersion);
            icd.put("ICD", icdBody);
            if (!FileUtils.writeString(new File(tmpDir, ICD_NAME), icd.toString(2))) throw new IOException("cannot write icd.json");

            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("name", name);
            meta.put("driverVersion", version);
            meta.put("libc", "glibc");
            meta.put("minGlibc", minGlibc);
            meta.put("sourceLibraryName", soName);
            meta.put("vulkanApiVersion", apiVersion);
            meta.put("gpuVendor", DriverArchivePolicy.isSoftwareLibrary(soName) ? "software" : "gpu");
            meta.put("runtimeLibraries", new org.json.JSONArray(runtimeLibs));
            meta.put("importedAt", System.currentTimeMillis());
            if (!FileUtils.writeString(new File(tmpDir, META_NAME), meta.toString(2))) throw new IOException("cannot write meta.json");

            if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
            Log.i(TAG, "imported Linux Vulkan driver " + id + " (" + soName + ", minGlibc=" + minGlibc + ") -> " + dir);
            return id;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (getDriverDir(id).exists()) id = base + "-" + (n++);
        return id;
    }

    static String sanitizeId(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "linux-driver" : s;
    }

    static boolean isAarch64Elf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 20) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            if (h[4] != 2 || h[5] != 1) return false;
            int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
            return machine == 0xB7;
        } catch (IOException e) {
            return false;
        }
    }

    private static long copyBounded(InputStream in, File out, long limit) throws IOException {
        long total = 0;
        try (java.io.OutputStream sink = Files.newOutputStream(out.toPath())) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("driver library exceeds size limit");
                sink.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static byte[] readAllBounded(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() > limit - read) throw new IOException("driver metadata exceeds size limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static long drainBounded(InputStream in, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[32768];
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("driver zip exceeds unpacked size limit");
        }
        return total;
    }

    static boolean containsAscii(File f, String needle) {
        byte[] nb = needle.getBytes(StandardCharsets.US_ASCII);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int len = carry + r;
                for (int i = 0; i + nb.length <= len; i++) {
                    int j = 0;
                    while (j < nb.length && buf[i + j] == nb[j]) j++;
                    if (j == nb.length) return true;
                }
                carry = Math.min(nb.length - 1, len);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
