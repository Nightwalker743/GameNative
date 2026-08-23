package app.gamenative.mods

import java.security.MessageDigest

enum class PlannedFileStatus {
    PLACED,
    INTENTIONALLY_IGNORED,
    UNSUPPORTED,
    MISSING,
    CONFLICTED,
}

enum class PlacementOrigin {
    MANUAL_RECIPE,
    GAME_RULE,
    LEGACY_PRESET,
    FOMOD_REQUIRED,
    FOMOD_OPTION,
    FOMOD_CONDITIONAL,
}

enum class PlacementRisk {
    SAFE,
    REVIEW,
    UNSAFE,
}

data class PlannedModFile(
    val sourceRelativePath: String,
    val targetRoot: String? = null,
    val targetRelativePath: String? = null,
    val normalizedTargetKey: String? = null,
    val status: PlannedFileStatus,
    val origin: PlacementOrigin,
    val priority: Int = 0,
    val sizeBytes: Long = 0L,
    val reason: String,
    val evidence: List<String> = emptyList(),
    val risk: PlacementRisk = PlacementRisk.SAFE,
)

data class ModInstallPlan(
    val files: List<PlannedModFile>,
    val warnings: List<String> = emptyList(),
    val blockingIssues: List<String> = emptyList(),
) {
    val selectedCount: Int
        get() = files.count { it.status != PlannedFileStatus.INTENTIONALLY_IGNORED }

    val placedCount: Int
        get() = files.count { it.status == PlannedFileStatus.PLACED }

    val ignoredCount: Int
        get() = files.count { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED }

    val unresolvedCount: Int
        get() = files.count {
            it.status == PlannedFileStatus.UNSUPPORTED ||
                it.status == PlannedFileStatus.MISSING ||
                it.status == PlannedFileStatus.CONFLICTED
        }

    val installableBytes: Long
        get() = files.filter { it.status != PlannedFileStatus.INTENTIONALLY_IGNORED }.sumOf { it.sizeBytes }

    val placedBytes: Long
        get() = files.filter { it.status == PlannedFileStatus.PLACED }.sumOf { it.sizeBytes }

    val coverage: Double
        get() = if (installableBytes > 0L) {
            placedBytes.toDouble() / installableBytes.toDouble()
        } else if (selectedCount == 0) {
            1.0
        } else {
            placedCount.toDouble() / selectedCount.toDouble()
        }

    val isComplete: Boolean
        get() = blockingIssues.isEmpty() &&
            unresolvedCount == 0 &&
            files.none { it.risk == PlacementRisk.UNSAFE }

    val digest: String
        get() {
            val canonical = files
                .sortedWith(
                    compareBy<PlannedModFile> { it.normalizedTargetKey.orEmpty() }
                        .thenBy { it.sourceRelativePath.lowercase() }
                        .thenByDescending { it.priority },
                )
                .joinToString("\n") { file ->
                    listOf(
                        file.sourceRelativePath,
                        file.targetRoot.orEmpty(),
                        file.targetRelativePath.orEmpty(),
                        file.normalizedTargetKey.orEmpty(),
                        file.status.name,
                        file.origin.name,
                        file.priority.toString(),
                        file.sizeBytes.toString(),
                    ).joinToString("|")
                }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    fun sanitizedManifest(): String = buildString {
        appendLine("plan-version: 1")
        appendLine("digest: $digest")
        appendLine("complete: $isComplete")
        appendLine("placed: $placedCount/$selectedCount")
        files.sortedBy { it.sourceRelativePath.lowercase() }.forEach { file ->
            append(file.status.name)
            append(' ')
            append(file.sourceRelativePath)
            if (file.targetRoot != null && file.targetRelativePath != null) {
                append(" -> ")
                append(file.targetRoot)
                append('/')
                append(file.targetRelativePath)
            }
            append(" [")
            append(file.reason.replace('\n', ' '))
            appendLine(']')
        }
        warnings.sorted().forEach { appendLine("warning: ${it.replace('\n', ' ')}") }
        blockingIssues.sorted().forEach { appendLine("blocker: ${it.replace('\n', ' ')}") }
    }
}

data class PlacementPlanQuality(
    val placedFiles: Int,
    val placedBytes: Long,
    val unresolvedFiles: Int,
    val blockers: Int,
    val highestRisk: PlacementRisk,
)

object PlacementPlanRegressionPolicy {
    fun quality(plan: ModInstallPlan): PlacementPlanQuality = PlacementPlanQuality(
        placedFiles = plan.placedCount,
        placedBytes = plan.placedBytes,
        unresolvedFiles = plan.unresolvedCount,
        blockers = plan.blockingIssues.size,
        highestRisk = plan.files.maxOfOrNull { it.risk } ?: PlacementRisk.SAFE,
    )

    fun canReplace(baseline: ModInstallPlan, candidate: ModInstallPlan): Boolean {
        val old = quality(baseline)
        val new = quality(candidate)
        return new.placedFiles >= old.placedFiles &&
            new.placedBytes >= old.placedBytes &&
            new.unresolvedFiles <= old.unresolvedFiles &&
            new.blockers <= old.blockers &&
            new.highestRisk <= old.highestRisk
    }
}
