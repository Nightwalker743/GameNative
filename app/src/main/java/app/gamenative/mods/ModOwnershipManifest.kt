package app.gamenative.mods

import app.gamenative.data.ModOverwriteManifest
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

@Serializable
enum class ModOwnershipState {
    ACTIVE,
    DISABLED,
    RECOVERY_REQUIRED,
}

@Serializable
enum class ModOwnedFileDisposition {
    CREATED,
    MERGED,
    OVERWROTE,
    BACKED_UP,
    SHARED,
    STALE_PRESERVED,
}

@Serializable
data class ModOwnedFile(
    val sourceRelativePath: String,
    val targetRoot: String,
    val targetRelativePath: String,
    val targetPath: String,
    val normalizedTargetKey: String,
    val mode: String,
    val installedHash: String,
    val installedSize: Long,
    val installedMtime: Long,
    val disposition: ModOwnedFileDisposition,
    val priority: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class ModOwnedOperation(
    val sourcePath: String,
    val targetPath: String,
    val normalizedTargetKey: String,
    val mode: String,
)

@Serializable
data class ModOwnershipManifest(
    val version: Int = 1,
    val installId: String,
    val appId: String,
    val profileId: String = "",
    val planDigest: String,
    val state: ModOwnershipState = ModOwnershipState.ACTIVE,
    val files: List<ModOwnedFile>,
    val operations: List<ModOwnedOperation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class ModOverlayContribution(
    val installId: String,
    val priority: Int,
    val file: ModOwnedFile,
)

data class ModOverlayTarget(
    val normalizedTargetKey: String,
    val contributors: List<ModOverlayContribution>,
    val winner: ModOverlayContribution,
    val identicalContents: Boolean,
    val hasCaseCollision: Boolean,
)

data class ModProfileOverlay(
    val targets: Map<String, ModOverlayTarget>,
    val conflicts: List<ModOverlayTarget>,
)

object ModProfileOverlayPlanner {
    fun build(
        manifests: List<ModOwnershipManifest>,
        enabledPriorities: Map<String, Int>,
    ): ModProfileOverlay {
        val targets = manifests.asSequence()
            .filter { it.state == ModOwnershipState.ACTIVE && it.installId in enabledPriorities }
            .flatMap { manifest ->
                manifest.files.asSequence()
                    .filter { it.active }
                    .map { file -> ModOverlayContribution(manifest.installId, enabledPriorities.getValue(manifest.installId), file) }
            }
            .groupBy { it.file.normalizedTargetKey }
            .mapValues { (key, contributions) ->
                val ordered = contributions.sortedWith(compareBy<ModOverlayContribution> { it.priority }.thenBy { it.installId })
                val paths = ordered.map { it.file.targetPath.replace('\\', '/') }.distinct()
                ModOverlayTarget(
                    normalizedTargetKey = key,
                    contributors = ordered,
                    winner = ordered.last(),
                    identicalContents = ordered.map { it.file.installedHash }.filter(String::isNotBlank).distinct().size <= 1,
                    hasCaseCollision = paths.map(String::lowercase).distinct().size < paths.size,
                )
            }
            .toSortedMap()
        return ModProfileOverlay(targets, targets.values.filter { it.contributors.size > 1 && !it.identicalContents })
    }
}

object ModOwnershipStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: File, installId: String): ModOwnershipManifest? =
        readFile(currentFile(root, installId)) ?: readFile(previousFile(root, installId))

    fun readAll(root: File): List<ModOwnershipManifest> =
        ownershipDir(root).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json.zst") && !it.name.endsWith(".previous.json.zst") }
            .mapNotNull(::readFile)

    fun writePending(root: File, manifest: ModOwnershipManifest) {
        val current = currentFile(root, manifest.installId)
        val previous = previousFile(root, manifest.installId)
        val temp = File(current.parentFile, "${current.name}.tmp")
        current.parentFile?.mkdirs()
        val payload = Zstd.compress(json.encodeToString(manifest).toByteArray(Charsets.UTF_8), 3)
        FileOutputStream(temp).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        if (current.isFile) {
            previous.delete()
            moveReplacing(current, previous)
        }
        moveReplacing(temp, current)
    }

    fun commit(root: File, installId: String) {
        previousFile(root, installId).delete()
    }

    fun delete(root: File, installId: String) {
        currentFile(root, installId).delete()
        previousFile(root, installId).delete()
        File(currentFile(root, installId).parentFile, "${currentFile(root, installId).name}.tmp").delete()
    }

    fun create(
        appId: String,
        plan: ModMaterializationPlan,
        overwriteManifests: List<ModOverwriteManifest>,
        profileId: String = "",
        priority: Int = 0,
        preservedStale: List<ModOwnedFile> = emptyList(),
    ): ModOwnershipManifest {
        val overwriteByTarget = overwriteManifests.associateBy { WindowsPathIdentity.absoluteKey(File(it.targetPath)) }
        val files = plan.files.map { planned ->
            val target = planned.target
            val overwrite = overwriteByTarget[planned.normalizedTargetKey]
            val installedHash = when {
                target.isFile -> sha256(target)
                planned.source.isFile -> sha256(planned.source)
                else -> ""
            }
            val disposition = when {
                overwrite?.backupPath?.isNotBlank() == true -> ModOwnedFileDisposition.BACKED_UP
                planned.targetExistedBefore && planned.targetHashBefore == installedHash -> ModOwnedFileDisposition.SHARED
                planned.mode.name == "OVERWRITE_COPY" && planned.targetExistedBefore -> ModOwnedFileDisposition.OVERWROTE
                planned.targetExistedBefore -> ModOwnedFileDisposition.MERGED
                else -> ModOwnedFileDisposition.CREATED
            }
            ModOwnedFile(
                sourceRelativePath = planned.sourceRelativePath,
                targetRoot = planned.targetRoot,
                targetRelativePath = planned.targetRelativePath,
                targetPath = target.absolutePath,
                normalizedTargetKey = planned.normalizedTargetKey,
                mode = planned.mode.name,
                installedHash = installedHash,
                installedSize = if (target.isFile) target.length() else planned.source.length(),
                installedMtime = if (target.exists()) target.lastModified() else planned.source.lastModified(),
                disposition = disposition,
                priority = priority,
            )
        }
        return ModOwnershipManifest(
            installId = plan.installId,
            appId = appId,
            profileId = profileId,
            planDigest = planDigest(plan),
            files = (files + preservedStale).distinctBy { it.normalizedTargetKey to it.active },
            operations = plan.operations.map { operation ->
                ModOwnedOperation(
                    sourcePath = operation.source.absolutePath,
                    targetPath = operation.target.absolutePath,
                    normalizedTargetKey = operation.normalizedTargetKey,
                    mode = operation.mode.name,
                )
            },
        )
    }

    private fun ownershipDir(root: File): File = File(root, "ownership")

    private fun currentFile(root: File, installId: String): File =
        File(ownershipDir(root), "${safeName(installId)}.json.zst")

    private fun previousFile(root: File, installId: String): File =
        File(ownershipDir(root), "${safeName(installId)}.previous.json.zst")

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readFile(file: File): ModOwnershipManifest? {
        if (!file.isFile) return null
        return runCatching {
            ZstdInputStream(FileInputStream(file)).bufferedReader().use { reader ->
                json.decodeFromString<ModOwnershipManifest>(reader.readText())
            }
        }.getOrNull()
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun planDigest(plan: ModMaterializationPlan): String {
        val canonical = plan.files.joinToString("\n") { file ->
            "${file.sourceRelativePath}|${file.targetRoot}|${file.targetRelativePath}|${file.normalizedTargetKey}|${file.mode}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class ModOwnershipCleanupResult(
    val removed: Int,
    val restored: Int,
    val preserved: List<ModOwnedFile>,
) {
    val skippedPaths: List<String> get() = preserved.map { it.targetPath }.distinct()
}

object ModOwnershipReconciler {
    suspend fun removeOwnedFiles(
        manifest: ModOwnershipManifest,
        overwriteManifests: List<ModOverwriteManifest>,
        targetKeys: Set<String> = manifest.files.filter { it.active }.mapTo(mutableSetOf()) { it.normalizedTargetKey },
        restoreBackups: Boolean = true,
    ): ModOwnershipCleanupResult {
        val selected = manifest.files.filter { it.active && it.normalizedTargetKey in targetKeys }
        val overwriteByKey = overwriteManifests.associateBy { WindowsPathIdentity.absoluteKey(File(it.targetPath)) }
        val restoreCandidates = if (restoreBackups) selected.mapNotNull { overwriteByKey[it.normalizedTargetKey] } else emptyList()
        val restoreSkipped = ModMaterializer.restoreBackups(restoreCandidates)
            .mapTo(mutableSetOf()) { WindowsPathIdentity.absoluteKey(File(it)) }
        val restoredKeys = restoreCandidates.asSequence()
            .filter { it.backupPath.isNotBlank() && it.normalizedKey() !in restoreSkipped }
            .mapTo(mutableSetOf()) { it.normalizedKey() }
        var removed = 0
        val preserved = mutableListOf<ModOwnedFile>()

        val symlinkOperations = manifest.operations.filter { operation ->
            val operationPath = operation.targetPath + File.separator
            val ownedUnderOperation = manifest.files.filter { owned ->
                owned.mode == "SYMLINK" &&
                    (owned.normalizedTargetKey == operation.normalizedTargetKey || owned.targetPath.startsWith(operationPath))
            }
            operation.mode == "SYMLINK" && ownedUnderOperation.isNotEmpty() &&
                ownedUnderOperation.all { it.normalizedTargetKey in targetKeys }
        }
        symlinkOperations.forEach { operation ->
            val target = File(operation.targetPath)
            val source = File(operation.sourcePath)
            val link = target.toPath()
            val pointsToSource = runCatching {
                val raw = Files.readSymbolicLink(link)
                val resolved = if (raw.isAbsolute) raw else link.parent.resolve(raw)
                resolved.normalize().toFile().canonicalFile == source.canonicalFile
            }.getOrDefault(false)
            if (Files.isSymbolicLink(link) && pointsToSource) {
                Files.deleteIfExists(link)
                removed++
            } else {
                preserved += selected.filter { owned ->
                    owned.mode == "SYMLINK" &&
                        (owned.normalizedTargetKey == operation.normalizedTargetKey || owned.targetPath.startsWith(operation.targetPath + File.separator))
                }
            }
        }

        selected.filter { it.mode != "SYMLINK" }.forEach { owned ->
            val overwrite = overwriteByKey[owned.normalizedTargetKey]
            when {
                owned.normalizedTargetKey in restoredKeys -> Unit
                overwrite != null && !restoreBackups -> preserved += owned.preserved()
                overwrite?.backupPath?.isBlank() == true -> Unit
                owned.disposition == ModOwnedFileDisposition.SHARED -> Unit
                owned.normalizedTargetKey in restoreSkipped -> preserved += owned.preserved()
                else -> {
                    val target = File(owned.targetPath)
                    val currentHash = ModOwnershipStore.sha256(target)
                    if (target.isFile && currentHash.isNotBlank() && currentHash == owned.installedHash) {
                        if (target.delete()) removed++ else preserved += owned.preserved()
                    } else if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                        preserved += owned.preserved()
                    }
                }
            }
        }
        return ModOwnershipCleanupResult(removed, restoredKeys.size, preserved.distinctBy { it.normalizedTargetKey })
    }

    private fun ModOwnedFile.preserved(): ModOwnedFile =
        copy(active = false, disposition = ModOwnedFileDisposition.STALE_PRESERVED)

    private fun ModOverwriteManifest.normalizedKey(): String = WindowsPathIdentity.absoluteKey(File(targetPath))
}
