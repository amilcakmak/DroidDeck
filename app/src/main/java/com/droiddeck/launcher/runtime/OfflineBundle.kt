package com.droiddeck.launcher.runtime

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object OfflineBundle {
    private const val NAME = "droiddeck-offline-bundle"
    private const val MANIFEST = "manifest.json"
    private const val COMPLETE = ".complete"
    private const val MAX_MANIFEST_BYTES = 1024 * 1024L
    private const val MAX_PAYLOAD_BYTES = 32L * 1024 * 1024 * 1024
    private const val MAX_BUNDLE_BYTES = 48L * 1024 * 1024 * 1024
    private val payloadNames = mapOf(
        "runtime" to "runtime.tar.zst",
        "desktop" to "desktop.tar.zst",
        "steam" to "steam.tar",
        "proot" to "proot.tar.zst",
        "directaudio" to "directaudio.tar.zst",
    )

    internal data class Payload(val path: String, val size: Long, val sha256: String, val version: String)
    internal data class Manifest(val payloads: Map<String, Payload>)
    internal data class Installed(val directory: File, val manifest: Manifest) {
        fun file(name: String): File = File(directory, payloadNames.getValue(name))
    }

    internal fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "Unsupported offline bundle schema" }
        val payloads = payloadNames.mapValues { (name, fileName) ->
            val entry = json.getJSONObject(name)
            validatePayload(name, entry.getString("path"), entry.getLong("size"),
                entry.getString("sha256"), entry.optString("version", ""), fileName)
        }
        require(payloads.values.sumOf { it.size } <= MAX_BUNDLE_BYTES) { "Offline bundle is too large" }
        return Manifest(payloads)
    }

    internal fun validatePayload(name: String, path: String, size: Long, sha256: String, version: String, fileName: String): Payload {
        require(path == "payload/$fileName") { "Invalid $name payload path" }
        require(size in 1..MAX_PAYLOAD_BYTES) { "Invalid $name payload size" }
        val digest = sha256.lowercase(java.util.Locale.ROOT)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid $name payload checksum" }
        if (name != "steam") require(version.isNotBlank()) { "Missing $name version" }
        return Payload(path, size, digest, version)
    }

    fun import(context: Context, source: File): String? {
        if (!source.isFile) return "Offline bundle file is missing"
        val parent = context.noBackupFilesDir
        val staging = File(parent, "$NAME.importing")
        val target = File(parent, NAME)
        val previous = File(parent, "$NAME.previous")
        recover(target, previous)
        if (staging.exists() && !staging.deleteRecursively()) return "Could not clear the incomplete bundle import"
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
                val first = zip.nextEntry ?: throw IOException("Offline bundle is empty")
                if (first.isDirectory || first.name != MANIFEST) throw IOException("manifest.json must be the first bundle entry")
                val manifestText = zip.readBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                zip.closeEntry()
                val manifest = parseManifest(manifestText)
                if (parent.usableSpace < manifest.payloads.values.sumOf { it.size })
                    throw IOException("Not enough free space to import this offline bundle")
                if (!staging.mkdirs()) throw IOException("Could not create the bundle import directory")
                File(staging, MANIFEST).writeText(manifestText)
                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) throw IOException("Unexpected directory in offline bundle")
                    val name = payloadNames.entries.firstOrNull { "payload/${it.value}" == entry.name }?.key
                        ?: throw IOException("Unexpected file in offline bundle: ${entry.name}")
                    if (!seen.add(name)) throw IOException("Duplicate $name payload")
                    val metadata = manifest.payloads.getValue(name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val out = File(staging, payloadNames.getValue(name))
                    var written = 0L
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > metadata.size) throw IOException("${name} payload exceeds its declared size")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    if (written != metadata.size || !digest.digest().hex().equals(metadata.sha256, true))
                        throw IOException("${name} payload size or checksum does not match the manifest")
                    zip.closeEntry()
                }
                if (seen != payloadNames.keys) throw IOException("Offline bundle is missing: ${(payloadNames.keys - seen).joinToString()}")
            }
            File(staging, COMPLETE).writeText("1")
            if (previous.exists() && !previous.deleteRecursively()) throw IOException("Could not clear the previous bundle backup")
            if (target.exists() && !target.renameTo(previous)) throw IOException("Could not replace the previous offline bundle")
            if (!staging.renameTo(target)) {
                if (previous.exists()) previous.renameTo(target)
                throw IOException("Could not finish importing the offline bundle")
            }
            previous.deleteRecursively()
            return null
        } catch (e: Exception) {
            staging.deleteRecursively()
            return e.message ?: "Could not import the offline bundle"
        }
    }

    fun current(context: Context): Installed? {
        val directory = File(context.noBackupFilesDir, NAME)
        recover(directory, File(context.noBackupFilesDir, "$NAME.previous"))
        if (File(directory, COMPLETE).readTextOrNull() != "1") return null
        val manifest = runCatching { parseManifest(File(directory, MANIFEST).readText()) }.getOrNull() ?: return null
        return manifest.payloads.all { (name, payload) ->
            val file = File(directory, payloadNames.getValue(name))
            file.isFile && file.length() == payload.size
        }.takeIf { it }?.let { Installed(directory, manifest) }
    }

    fun verify(file: File, payload: Payload): Boolean =
        file.isFile && file.length() == payload.size && digest(file).equals(payload.sha256, true)

    fun clear(context: Context) {
        File(context.noBackupFilesDir, NAME).deleteRecursively()
    }

    private fun recover(target: File, previous: File) {
        if (File(target, COMPLETE).readTextOrNull() != "1" && File(previous, COMPLETE).readTextOrNull() == "1") {
            target.deleteRecursively()
            if (!previous.renameTo(target)) android.util.Log.w("OfflineBundle", "could not restore the previous verified bundle")
        } else if (File(target, COMPLETE).readTextOrNull() == "1") {
            previous.deleteRecursively()
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun java.util.zip.ZipInputStream.readBounded(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Offline bundle manifest is too large")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
