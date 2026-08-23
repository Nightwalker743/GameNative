package app.gamenative.mods

import java.io.File
import java.nio.file.Files

enum class ModVerificationIssueType {
    MISSING,
    MODIFIED,
    WRONG_CASE,
    AMBIGUOUS,
    STALE,
    OWNERSHIP,
}

data class ModVerificationIssue(
    val type: ModVerificationIssueType,
    val targetPath: String,
    val detail: String,
    val installId: String = "",
)

data class ModDeploymentVerification(val issues: List<ModVerificationIssue>) {
    val successful: Boolean get() = issues.isEmpty()
}

object ModDeploymentVerifier {
    fun verify(plan: ModMaterializationPlan): ModDeploymentVerification =
        ModDeploymentVerification(
            plan.files.flatMap { file ->
                verifyTarget(
                    target = file.target,
                    expectedHash = ModOwnershipStore.sha256(file.source),
                    stale = false,
                    installId = file.installId,
                )
            },
        )

    fun verify(manifest: ModOwnershipManifest): ModDeploymentVerification =
        ModDeploymentVerification(
            manifest.files.flatMap { file ->
                verifyTarget(File(file.targetPath), file.installedHash, stale = !file.active, installId = manifest.installId)
            },
        )

    fun verify(overlay: ModProfileOverlay): ModDeploymentVerification =
        ModDeploymentVerification(
            overlay.targets.values.flatMap { target ->
                verifyTarget(
                    target = File(target.winner.file.targetPath),
                    expectedHash = target.winner.file.installedHash,
                    stale = false,
                    installId = target.winner.installId,
                ) + if (target.hasCaseCollision) {
                    listOf(
                        ModVerificationIssue(
                            ModVerificationIssueType.AMBIGUOUS,
                            target.winner.file.targetPath,
                            "Enabled mods use case-variant target paths",
                            target.winner.installId,
                        ),
                    )
                } else {
                    emptyList()
                }
            },
        )

    private fun verifyTarget(
        target: File,
        expectedHash: String,
        stale: Boolean,
        installId: String,
    ): List<ModVerificationIssue> {
        val parent = target.parentFile
        val caseMatches = parent?.listFiles().orEmpty().filter { it.name.equals(target.name, ignoreCase = true) }
        if (caseMatches.size > 1) {
            return listOf(issue(ModVerificationIssueType.AMBIGUOUS, target, "Multiple case variants exist", installId))
        }
        val actual = when {
            target.exists() || Files.isSymbolicLink(target.toPath()) -> target
            caseMatches.size == 1 -> caseMatches.single()
            stale -> return emptyList()
            else -> return listOf(issue(ModVerificationIssueType.MISSING, target, "Required planned file is missing", installId))
        }
        if (stale) {
            return listOf(issue(ModVerificationIssueType.STALE, actual, "A preserved stale managed file is still present", installId))
        }
        val issues = mutableListOf<ModVerificationIssue>()
        if (actual.name != target.name) {
            issues += issue(ModVerificationIssueType.WRONG_CASE, actual, "Target exists with unexpected casing", installId)
        }
        val currentHash = ModOwnershipStore.sha256(actual)
        if (!actual.isFile || expectedHash.isBlank() || currentHash != expectedHash) {
            issues += issue(ModVerificationIssueType.MODIFIED, actual, "Target content differs from the reviewed plan", installId)
        }
        return issues
    }

    private fun issue(type: ModVerificationIssueType, target: File, detail: String, installId: String) =
        ModVerificationIssue(type, target.absolutePath, detail, installId)
}
