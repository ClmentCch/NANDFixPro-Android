package com.nandfixpro.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.nandfixpro.android.nand.ChannelSource
import com.nandfixpro.android.nand.FileSource
import com.nandfixpro.android.nand.MultiSource
import com.nandfixpro.android.nand.NandFileKind
import com.nandfixpro.android.nand.NandFileDetector
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.nand.RandomAccessSource
import com.nandfixpro.android.nand.SplitNandDetector
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * All file access goes through this class:
 *  - Storage Access Framework documents are opened as seekable read-only channels
 *    so multi-gigabyte NAND dumps are never copied into memory;
 *  - backups and exports are written to the app's own external files directory,
 *    which requires no storage permission and can be deleted by the user.
 */
class StorageRepository(private val context: Context) {

    class SourceHandle(
        val source: RandomAccessSource,
        val displayName: String,
        val uri: Uri?,
        private val cleanup: () -> Unit = {},
    ) : Closeable {
        override fun close() {
            runCatching { source.close() }
            runCatching(cleanup)
        }
    }

    data class SafEntry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    ) {
        val kind: NandFileKind = if (isDirectory) NandFileKind.UNKNOWN else NandFileDetector.detect(name, size, null).kind
        val relevant: Boolean
            get() = isDirectory || name.lowercase().let {
                it == "boot0" || it == "boot1" || it == "prod.keys" || it.contains("rawnand") ||
                    it.endsWith(".bin") || it.endsWith(".keys") || it.matches(Regex("\\d{2,}")) ||
                    it.endsWith(".dec")
            }
    }

    data class SplitBundle(
        val handle: SourceHandle,
        val split: SplitNandDetector.SplitSet,
    )

    data class BackupManifest(
        val name: String,
        val sourceName: String,
        val size: Long,
        val sha256: String,
        val createdAt: Long,
        val kind: String,
        val note: String,
    )

    data class BackupArtifact(
        val file: File,
        val manifest: BackupManifest,
    )

    // ------------------------------------------------------------ SAF reading

    fun openDocument(uri: Uri): Result<SourceHandle> {
        val metadata = queryMetadata(uri)
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return Result.failure(IOException("Ouverture du document impossible"))
        val descriptorFd = descriptor
        val stream = FileInputStream(descriptorFd.fileDescriptor)
        val channel = stream.channel
        val channelSize = runCatching { channel.size() }.getOrDefault(-1L)
        val size = if (channelSize > 0) channelSize else metadata.second
        if (size <= 0) {
            runCatching { stream.close() }
            runCatching { descriptorFd.close() }
            return Result.failure(IOException("Taille du document inconnue ou nulle"))
        }
        val seekable = runCatching {
            channel.position(0)
            if (size > 1) channel.position(size - 1)
            channel.position(0)
            true
        }.getOrDefault(false)
        if (!seekable) {
            runCatching { stream.close() }
            runCatching { descriptorFd.close() }
            return Result.failure(
                IOException(
                    "Ce fournisseur de fichiers ne permet pas l'accès aléatoire requis pour lire une NAND. " +
                        "Copiez le fichier dans le stockage interne du téléphone puis réessayez.",
                ),
            )
        }
        val source = ChannelSource(channel, size, metadata.first)
        return Result.success(
            SourceHandle(source, metadata.first, uri) {
                runCatching { stream.close() }
                runCatching { descriptorFd.close() }
            },
        )
    }

    fun openLocalFile(file: File): Result<SourceHandle> = runCatching {
        SourceHandle(FileSource(file), file.name, Uri.fromFile(file))
    }

    fun takePersistablePermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun treeRoot(uri: Uri): DocumentFile? = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()

    fun listChildren(directory: DocumentFile): List<SafEntry> {
        val children = runCatching { directory.listFiles() }.getOrElse { emptyArray() }
        return children.map { document ->
            SafEntry(
                uri = document.uri,
                name = document.name ?: "(sans nom)",
                isDirectory = document.isDirectory,
                size = document.length(),
                lastModified = document.lastModified(),
            )
        }.sortedWith(
            compareByDescending<SafEntry> { it.isDirectory }
                .thenByDescending { it.relevant }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    /**
     * Opens a complete split set found in [directory] around [selectedName] and
     * assembles the parts into a single read-only source.
     */
    fun openSplitSet(directory: DocumentFile, selectedName: String): Result<SplitBundle> {
        val children = listChildren(directory)
        val fileRefs = children.filter { !it.isDirectory }
            .map { SplitNandDetector.FileRef(it.name, it.size) }
        val detection = SplitNandDetector.detect(selectedName, fileRefs)
        val set = when (detection) {
            is SplitNandDetector.Result.Success -> detection.set
            is SplitNandDetector.Result.Failure -> return Result.failure(IOException(detection.reason))
        }
        val sources = mutableListOf<RandomAccessSource>()
        try {
            set.partNames.forEach { partName ->
                val entry = children.firstOrNull { it.name.equals(partName, ignoreCase = true) }
                    ?: throw IOException("Partie introuvable : $partName")
                val opened = openDocument(entry.uri).getOrElse { throw it }
                sources += opened.source
            }
        } catch (error: Exception) {
            sources.forEach { runCatching { it.close() } }
            return Result.failure(IOException("Assemblage du jeu découpé impossible: ${error.message}"))
        }
        val multi = MultiSource(sources, directory.name ?: "jeu découpé")
        return Result.success(
            SplitBundle(
                handle = SourceHandle(multi, "${set.partNames.size} parties · ${directory.name}", null),
                split = set,
            ),
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "document"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    // --------------------------------------------------------------- backups

    fun backupsDirectory(): File {
        val root = context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun usableSpace(): Long = backupsDirectory().usableSpace

    fun listBackups(): List<BackupArtifact> {
        val directory = backupsDirectory()
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".json") } ?: emptyList()
        return files.mapNotNull { file ->
            val manifestFile = File(directory, "${file.name}.json")
            val manifest = runCatching { readManifest(manifestFile) }.getOrNull()
            if (manifest == null) {
                BackupArtifact(
                    file = file,
                    manifest = BackupManifest(
                        name = file.name,
                        sourceName = file.name,
                        size = file.length(),
                        sha256 = "—",
                        createdAt = file.lastModified(),
                        kind = "inconnu",
                        note = "Manifeste absent : copie non vérifiable automatiquement.",
                    ),
                )
            } else {
                BackupArtifact(file, manifest)
            }
        }.sortedByDescending { it.manifest.createdAt }
    }

    fun createBackup(
        handle: SourceHandle,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result<BackupArtifact> {
        val source = handle.source
        val free = usableSpace()
        if (source.size + 16L * 1024 * 1024 > free) {
            return Result.failure(
                IOException(
                    "Espace insuffisant : ${NandSize.human(source.size)} requis, " +
                        "${NandSize.human(free)} disponibles dans le stockage de l'application.",
                ),
            )
        }
        if (source.size > MAX_SINGLE_BACKUP_BYTES) {
            return Result.failure(
                IOException(
                    "Copie refusée : ${NandSize.human(source.size)} dépasse la limite de " +
                        "${NandSize.human(MAX_SINGLE_BACKUP_BYTES)} d'une sauvegarde locale. " +
                        "Un dump eMMC complet dépasse l'espace d'un téléphone ; vérifiez-le avec " +
                        "l'analyseur et conservez la copie maîtresse sur un ordinateur.",
                ),
            )
        }

        val targetName = sanitize(handle.displayName)
        val target = uniqueFile(targetName)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_CHUNK)
                while (copied < source.size) {
                    if (isCancelled()) throw CancellationException("Sauvegarde annulée")
                    val remaining = source.size - copied
                    val length = minOf(remaining, buffer.size.toLong()).toInt()
                    val chunk = source.read(copied, length)
                    output.write(chunk)
                    digest.update(chunk)
                    copied += chunk.size
                    onProgress(copied.toFloat() / source.size, "Copie ${NandSize.human(copied)} / ${NandSize.human(source.size)}")
                }
                output.flush()
                output.fd.sync()
            }
            if (copied != source.size) throw IOException("Copie incomplète ($copied/${source.size} octets)")
        } catch (error: Exception) {
            runCatching { target.delete() }
            return Result.failure(
                if (error is CancellationException) IOException("Sauvegarde annulée par l'utilisateur")
                else IOException("Sauvegarde échouée : ${error.message}"),
            )
        }

        val hash = digest.digest().toHex()
        val derivedName = target.name.substringAfterLast('_', target.name)
        val kind = NandFileDetector.detect(handle.displayName, source.size, null).kind.label
        val manifest = BackupManifest(
            name = target.name,
            sourceName = handle.displayName,
            size = copied,
            sha256 = hash,
            createdAt = System.currentTimeMillis(),
            kind = kind,
            note = "Copie locale vérifiée. Le périphérique source n'a jamais été modifié.",
        )
        runCatching { writeManifest(target, manifest) }
        onProgress(1f, "Sauvegarde terminée · SHA-256 ${hash.take(16)}…")
        return Result.success(BackupArtifact(target, manifest))
    }

    fun verifyBackup(
        artifact: BackupArtifact,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result<String> {
        if (!artifact.file.exists()) return Result.failure(IOException("Fichier de sauvegarde introuvable"))
        if (artifact.manifest.sha256 == "—") {
            return Result.failure(IOException("Aucun hash enregistré : cette copie ne peut pas être vérifiée."))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        try {
            FileInputStream(artifact.file).use { input ->
                val buffer = ByteArray(COPY_CHUNK)
                while (true) {
                    if (isCancelled()) return Result.failure(IOException("Vérification annulée"))
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                    read += count
                    onProgress(read.toFloat() / artifact.file.length(), "Hachage ${NandSize.human(read)}")
                }
            }
        } catch (error: Exception) {
            return Result.failure(IOException("Vérification échouée : ${error.message}"))
        }
        val computed = digest.digest().toHex()
        return if (computed.equals(artifact.manifest.sha256, ignoreCase = true) && read == artifact.manifest.size) {
            Result.success("Sauvegarde intacte · SHA-256 ${computed.take(24)}…")
        } else {
            Result.failure(
                IOException(
                    "Sauvegarde altérée : hash calculé ${computed.take(24)}… " +
                        "au lieu de ${artifact.manifest.sha256.take(24)}…",
                ),
            )
        }
    }

    fun deleteBackup(artifact: BackupArtifact): Boolean {
        val manifest = File(artifact.file.parentFile, "${artifact.file.name}.json")
        val deleted = runCatching { artifact.file.delete() }.getOrDefault(false)
        runCatching { manifest.delete() }
        return deleted
    }

    fun saveBytesAsBackup(
        bytes: ByteArray,
        name: String,
        kind: String,
        note: String,
    ): Result<BackupArtifact> {
        val target = uniqueFile(sanitize(name))
        return runCatching {
            FileOutputStream(target).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val manifest = BackupManifest(
                name = target.name,
                sourceName = name,
                size = bytes.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
                createdAt = System.currentTimeMillis(),
                kind = kind,
                note = note,
            )
            writeManifest(target, manifest)
            BackupArtifact(target, manifest)
        }
    }

    fun saveText(name: String, content: String): Result<File> = runCatching {
        val target = uniqueFile(sanitize(name))
        target.writeText(content)
        target
    }

    fun writeToUri(uri: Uri, bytes: ByteArray): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Écriture impossible sur la destination choisie")
        Unit
    }

    fun computeHash(
        handle: SourceHandle,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result<String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        val buffer = ByteArray(COPY_CHUNK)
        while (read < handle.source.size) {
            if (isCancelled()) return Result.failure(IOException("Hachage annulé"))
            val length = minOf(handle.source.size - read, buffer.size.toLong()).toInt()
            val chunk = handle.source.read(read, length)
            digest.update(chunk)
            read += chunk.size
            onProgress(read.toFloat() / handle.source.size, "SHA-256 ${NandSize.human(read)}")
        }
        return Result.success(digest.digest().toHex())
    }

    // --------------------------------------------------------------- helpers

    private fun uniqueFile(baseName: String): File {
        val directory = backupsDirectory()
        var candidate = File(directory, baseName)
        if (!candidate.exists()) return candidate
        val extension = baseName.substringAfterLast('.', "")
        val stem = if (extension.isEmpty()) baseName else baseName.removeSuffix(".$extension")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        candidate = File(directory, "${stem}_$stamp$suffix")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "${stem}_$stamp-$counter$suffix")
            counter++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.take(96).ifBlank { "sauvegarde.bin" }
    }

    private fun manifestFileFor(dump: File): File = File(dump.parentFile, "${dump.name}.json")

    private fun writeManifest(dump: File, manifest: BackupManifest) {
        val json = JSONObject().apply {
            put("formatVersion", 1)
            put("name", manifest.name)
            put("sourceName", manifest.sourceName)
            put("size", manifest.size)
            put("sha256", manifest.sha256)
            put("createdAt", manifest.createdAt)
            put("kind", manifest.kind)
            put("note", manifest.note)
            put("parts", JSONArray())
        }
        manifestFileFor(dump).writeText(json.toString(2))
    }

    private fun readManifest(file: File): BackupManifest {
        val json = JSONObject(file.readText())
        return BackupManifest(
            name = json.optString("name", file.name.removeSuffix(".json")),
            sourceName = json.optString("sourceName"),
            size = json.optLong("size"),
            sha256 = json.optString("sha256"),
            createdAt = json.optLong("createdAt"),
            kind = json.optString("kind"),
            note = json.optString("note"),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private class CancellationException(message: String) : IOException(message)

    companion object {
        private const val COPY_CHUNK = 1024 * 1024
        private const val MAX_SINGLE_BACKUP_BYTES = 8L * 1024 * 1024 * 1024
    }
}
