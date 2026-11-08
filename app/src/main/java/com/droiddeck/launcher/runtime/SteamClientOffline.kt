package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.core.ArchivePaths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SteamClientOffline {
    private const val MAX_EXTRACTED_BYTES = 20L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private val privateRoots = setOf(
        "steamapps", "userdata", "logs", "dumps", "depotcache", "appcache", "shadercache",
        "compatibilitytools.d", "droiddeck-download",
    )

    fun installed(context: Context): Boolean {
        val steam = com.droiddeck.launcher.session.SessionPrefs.steamRoot(context, "arm64")
        return File(steam, "steamrtarm64/steam").isFile && File(steam, "package/droiddeck-installed").isFile
    }

    internal fun safePath(name: String): String? {
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\')) return null
        val path = runCatching { java.nio.file.Paths.get(name).normalize() }.getOrNull() ?: return null
        if (path.isAbsolute || path.startsWith("..") || path.toString().isEmpty()) return null
        val parts = path.map { it.toString() }
        if (parts.first() !in setOf("steamrtarm64", "linuxarm64", "linux64", "linux32")) return null
        if (parts.any { it in privateRoots || it.startsWith("ssfn") } ||
            parts.last() in setOf("loginusers.vdf", "localconfig.vdf", "steam.pid", "steam.pipe")) return null
        if (parts.last().endsWith(".log", true) || parts.last().endsWith(".dmp", true) || parts.last() == ".crash") return null
        return parts.joinToString("/")
    }

    internal fun safeLink(name: String, target: String): String? {
        val path = safePath(name) ?: return null
        if (target.isEmpty() || target.startsWith('/') || target.contains('\\')) return null
        val parent = java.nio.file.Paths.get(path).parent ?: java.nio.file.Paths.get("")
        val resolved = parent.resolve(target).normalize()
        if (resolved.isAbsolute || resolved.startsWith("..") || resolved.any { it.toString() in privateRoots }) return null
        return path
    }

    fun install(context: Context, archive: File, payload: OfflineBundle.Payload): String? {
        if (!OfflineBundle.verify(archive, payload)) return "Steam payload size or checksum does not match the manifest"
        if (installed(context)) return null
        val root = LinuxRuntime.rootDir(context)
        val steamRoot = com.droiddeck.launcher.session.SessionPrefs.steamRoot(context, "arm64")
        val staging = File(root, ".droiddeck-steam-importing")
        if (staging.exists() && !staging.deleteRecursively()) return "Could not clear the interrupted Steam import"
        if (!staging.mkdirs()) return "Could not create the Steam import directory"
        try {
            TarArchiveInputStream(BufferedInputStream(FileInputStream(archive))).use { tar ->
                var total = 0L
                var count = 0
                val links = ArrayList<Pair<File, String>>()
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) throw IOException("Steam archive contains too many files")
                    val name = safePath(entry.name) ?: throw IOException("Steam archive contains an unsafe or private path")
                    val file = ArchivePaths.inside(staging, name) ?: throw IOException("Steam archive path escapes its destination")
                    if (entry.isDirectory) {
                        if (!file.mkdirs() && !file.isDirectory) throw IOException("Could not create a Steam directory")
                    } else if (entry.isSymbolicLink) {
                        if (safeLink(name, entry.linkName) == null) throw IOException("Steam archive contains an unsafe symbolic link")
                        links += file to entry.linkName
                    } else if (entry.isFile) {
                        if (entry.size < 0 || total + entry.size > MAX_EXTRACTED_BYTES) throw IOException("Steam archive expands beyond the allowed size")
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (written < entry.size) {
                                val bytes = tar.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - written).toInt())
                                if (bytes < 0) throw IOException("Steam archive ended unexpectedly")
                                total += bytes
                                written += bytes
                                output.write(buffer, 0, bytes)
                            }
                        }
                        if ((entry.mode and 0b001001001) != 0) file.setExecutable(true, false)
                    } else {
                        throw IOException("Steam archive contains an unsupported file type")
                    }
                }
                links.forEach { (file, target) ->
                    file.parentFile?.mkdirs()
                    Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
                }
            }
            val steam = File(staging, "steamrtarm64/steam")
            if (!steam.isFile || !File(staging, "linuxarm64").isDirectory)
                throw IOException("Steam archive is missing its ARM64 client")
            steam.setExecutable(true, false)
            val paths = Files.walk(staging.toPath()).use { stream ->
                stream.filter { it != staging.toPath() }.collect(java.util.stream.Collectors.toList())
            }
                .sortedBy { it.nameCount }
            for (sourcePath in paths) {
                val source = sourcePath.toFile()
                val relative = staging.toPath().relativize(source.toPath()).toString()
                val target = ArchivePaths.inside(steamRoot, relative) ?: throw IOException("Steam archive path escapes its destination")
                if (Files.isDirectory(sourcePath)) {
                    if (!target.mkdirs() && !target.isDirectory) throw IOException("Could not create a Steam directory")
                } else {
                    target.parentFile?.mkdirs()
                    val part = File(target.parentFile, ".${target.name}.droiddeck-part")
                    Files.deleteIfExists(part.toPath())
                    if (Files.isSymbolicLink(sourcePath)) {
                        Files.createSymbolicLink(part.toPath(), Files.readSymbolicLink(sourcePath))
                    } else {
                        Files.copy(sourcePath, part.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (source.canExecute()) part.setExecutable(true, false)
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            }
            createSteamLinks(root)
            val marker = File(steamRoot, "package/droiddeck-installed")
            marker.parentFile?.mkdirs()
            marker.writeText(payload.version.ifBlank { "offline" })
            return null
        } catch (e: Exception) {
            return e.message ?: "Could not install the Steam client from the offline bundle"
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun createSteamLinks(root: File) {
        val home = File(root, "root")
        val steam = "/root/.local/share/Steam"
        val links = mapOf(
            "root" to steam,
            "steam" to steam,
            "bin64" to "$steam/steamrtarm64",
            "binarm64" to "$steam/steamrtarm64",
            "sdk64" to "$steam/linux64",
            "sdkarm64" to "$steam/linuxarm64",
            "sdk32" to "$steam/linux32",
        )
        val dir = File(home, ".steam")
        if (!dir.exists()) dir.mkdirs()
        for ((name, target) in links) {
            val path = File(dir, name).toPath()
            try {
                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path).toString() == target) continue
                    Files.delete(path)
                } else if (Files.exists(path)) continue
                Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
            } catch (_: Exception) {
            }
        }
        val steamRoot = File(root, "root/.local/share/Steam")
        val compat = File(steamRoot, "steamrtarm32").toPath()
        try {
            if (!Files.exists(compat)) Files.createSymbolicLink(compat, java.nio.file.Paths.get("steamrtarm64"))
        } catch (_: Exception) {
        }
    }
}
