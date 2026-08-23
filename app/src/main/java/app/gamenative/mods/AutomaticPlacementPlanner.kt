package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModTargetRoot
import java.util.Locale

data class AutomaticPlacementCandidate(
    val id: String,
    val label: String,
    val description: String,
    val drafts: List<ModPlacementPresetDraft>,
    val plan: ModInstallPlan,
    val score: Int,
    val evidence: List<String>,
)

data class AutomaticPlacementResult(
    val candidates: List<AutomaticPlacementCandidate>,
    val recommended: AutomaticPlacementCandidate?,
    val optionGroups: List<GenericOptionGroup> = emptyList(),
)

object AutomaticPlacementPlanner {
    private val bethesdaContentDirectories = setOf(
        "meshes",
        "textures",
        "scripts",
        "interface",
        "sound",
        "sounds",
        "strings",
        "skse",
        "f4se",
        "sfse",
        "seq",
        "video",
        "music",
        "lodsettings",
        "calientetools",
        "nemesis_engine",
    )
    private val bethesdaDataExtensions = setOf("esp", "esm", "esl", "bsa", "ba2")

    fun plan(gameName: String, entries: List<ModArchiveEntry>): AutomaticPlacementResult {
        val index = ModArchiveIndex.build(entries)
        val optionGroups = GenericOptionSetDetector.detect(index)
        val legacy = ModPlacementPresetDetector.detect(gameName, entries).map { preset ->
            candidateFromDrafts(
                id = "legacy:${preset.id}",
                label = preset.label,
                description = preset.description,
                drafts = preset.drafts,
                index = index,
                origin = PlacementOrigin.LEGACY_PRESET,
                evidence = listOf("Existing placement preset ${preset.id}"),
            )
        }
        val generated = buildList {
            bethesdaCandidate(gameName, index)?.let(::add)
        }
        val ranked = (generated + legacy)
            .distinctBy { candidate -> candidate.plan.digest }
            .sortedWith(
                compareByDescending<AutomaticPlacementCandidate> { it.plan.isComplete }
                    .thenByDescending { it.score }
                    .thenBy { it.id },
            )
        val baseline = legacy.firstOrNull()
        val bestGenerated = generated.maxWithOrNull(compareBy<AutomaticPlacementCandidate> { it.score }.thenBy { it.id })
        val recommendedBase = when {
            bestGenerated == null -> baseline
            baseline == null -> bestGenerated
            PlacementPlanRegressionPolicy.canReplace(baseline.plan, bestGenerated.plan) &&
                preservesExistingDestinations(baseline.plan, bestGenerated.plan) -> bestGenerated
            else -> baseline
        }
        val optionMessage = optionGroups.firstOrNull()?.let { group ->
            "Choose one package variant: ${group.choices.joinToString { it.sourceDirectory }}"
        }
        val reviewed = if (optionMessage == null) {
            ranked
        } else {
            ranked.map { candidate ->
                candidate.copy(
                    plan = candidate.plan.copy(blockingIssues = (candidate.plan.blockingIssues + optionMessage).distinct()),
                    evidence = candidate.evidence + optionMessage,
                )
            }
        }
        val recommended = recommendedBase?.let { base -> reviewed.firstOrNull { it.id == base.id } }
        return AutomaticPlacementResult(reviewed, recommended, optionGroups)
    }

    fun inferIncludeSourceDirectory(
        selectedPaths: Collection<String>,
        entries: List<ModArchiveEntry>,
        targetRelativePath: String,
    ): Boolean {
        val index = ModArchiveIndex.build(entries)
        val selectedDirectories = selectedPaths.filter(index::isDirectory)
        if (selectedDirectories.isEmpty()) return false
        val targetName = normalizeArchiveDisplayPath(targetRelativePath).substringAfterLast('/')
        return selectedDirectories.any { source ->
            !source.substringAfterLast('/').equals(targetName, ignoreCase = true)
        }
    }

    private fun bethesdaCandidate(gameName: String, index: ModArchiveIndex): AutomaticPlacementCandidate? {
        val game = BethesdaPluginManager.detectGame(gameName) ?: return null
        if (index.hasFomod) return null
        val dataNodes = index.nodes.filter { node -> node.displayPath.substringAfterLast('/').equals("Data", ignoreCase = true) }
        val bestData = dataNodes.maxWithOrNull(
            compareBy<ArchiveTreeNode> { it.descendantFileCount }
                .thenByDescending { it.displayPath.count { char -> char == '/' } }
                .thenBy { it.normalizedKey },
        )
        val drafts = if (bestData != null) {
            listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = bestData.displayPath,
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = false,
                ),
            ) + riskyGameRootDrafts(index)
        } else {
            val sources = index.files.mapNotNull(::bethesdaSourceForFile).distinctBy { it.lowercase(Locale.ROOT) }
            if (sources.isEmpty()) return null
            listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = ModPlacementSources.encode(sources),
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = true,
                ),
            )
        }
        val evidence = buildList {
            if (bestData != null) add("Found ${bestData.displayPath} as a Data container")
            val anchors = index.nodes.flatMapTo(mutableSetOf()) { it.semanticAnchors }.sorted()
            if (anchors.isNotEmpty()) add("Recognized Data content: ${anchors.joinToString()}")
            val loose = index.files.count { it.displayPath.substringAfterLast('.').lowercase(Locale.ROOT) in bethesdaDataExtensions }
            if (loose > 0) add("Found $loose Bethesda plugin/archive file(s)")
        }
        return candidateFromDrafts(
            id = "rules:bethesda-data-v1",
            label = "Complete Bethesda Data plan",
            description = "Maps recognized Data content and loose plugins while preserving content folders.",
            drafts = drafts,
            index = index,
            origin = PlacementOrigin.GAME_RULE,
            evidence = evidence,
        )
    }

    private fun riskyGameRootDrafts(index: ModArchiveIndex): List<ModPlacementPresetDraft> =
        index.files.filter { it.role == ArchiveContentRole.RISKY_ROOT && '/' !in it.displayPath }
            .map { file ->
                ModPlacementPresetDraft(
                    sourceSubpath = file.displayPath,
                    targetRelativePath = "",
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = false,
                )
            }

    private fun bethesdaSourceForFile(file: IndexedArchiveFile): String? {
        if (file.role != ArchiveContentRole.INSTALLABLE) return null
        val segments = file.displayPath.split('/')
        val anchorIndex = segments.indexOfFirst { it.lowercase(Locale.ROOT) in bethesdaContentDirectories }
        if (anchorIndex >= 0) return segments.take(anchorIndex + 1).joinToString("/")
        if (file.displayPath.substringAfterLast('.', "").lowercase(Locale.ROOT) in bethesdaDataExtensions) {
            return file.displayPath
        }
        return null
    }

    private fun candidateFromDrafts(
        id: String,
        label: String,
        description: String,
        drafts: List<ModPlacementPresetDraft>,
        index: ModArchiveIndex,
        origin: PlacementOrigin,
        evidence: List<String>,
    ): AutomaticPlacementCandidate {
        val placedBySource = linkedMapOf<String, PlannedModFile>()
        drafts.forEach { draft ->
            ModPlacementSources.decode(draft.sourceSubpath).ifEmpty { listOf("") }.forEach { source ->
                val sourceIsDirectory = source.isBlank() || index.isDirectory(source)
                index.filesUnder(source).forEach { file ->
                    val relative = when {
                        source.isBlank() -> file.displayPath
                        !sourceIsDirectory -> file.displayPath.substringAfterLast('/')
                        else -> file.displayPath.removePrefixCaseInsensitive("$source/")
                    }
                    val targetPath = listOfNotNull(
                        draft.targetRelativePath.takeIf(String::isNotBlank),
                        source.substringAfterLast('/').takeIf { sourceIsDirectory && source.isNotBlank() && draft.includeSourceDirectory },
                        relative.takeIf(String::isNotBlank),
                    ).joinToString("/")
                    val targetKey = WindowsPathIdentity.targetKey(ModTargetRoot.GAME_DIR.name, targetPath)
                    placedBySource[file.normalizedKey] = PlannedModFile(
                        sourceRelativePath = file.displayPath,
                        targetRoot = ModTargetRoot.GAME_DIR.name,
                        targetRelativePath = targetPath,
                        normalizedTargetKey = targetKey,
                        status = if (targetKey == null) PlannedFileStatus.UNSUPPORTED else PlannedFileStatus.PLACED,
                        origin = origin,
                        sizeBytes = file.sizeBytes,
                        reason = evidence.firstOrNull() ?: description,
                        evidence = evidence,
                        risk = if (file.role == ArchiveContentRole.RISKY_ROOT) PlacementRisk.UNSAFE else PlacementRisk.SAFE,
                    )
                }
            }
        }
        val classified = index.files.map { file ->
            placedBySource[file.normalizedKey] ?: when (file.role) {
                ArchiveContentRole.DOCUMENTATION,
                ArchiveContentRole.METADATA,
                ArchiveContentRole.INSTALLER_SUPPORT -> PlannedModFile(
                    sourceRelativePath = file.displayPath,
                    status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                    origin = origin,
                    sizeBytes = file.sizeBytes,
                    reason = "Known non-installable ${file.role.name.lowercase(Locale.ROOT).replace('_', ' ')}",
                )
                else -> PlannedModFile(
                    sourceRelativePath = file.displayPath,
                    status = PlannedFileStatus.UNSUPPORTED,
                    origin = origin,
                    sizeBytes = file.sizeBytes,
                    reason = "No supported destination was proven",
                    risk = if (file.role == ArchiveContentRole.RISKY_ROOT) PlacementRisk.UNSAFE else PlacementRisk.REVIEW,
                )
            }
        }.toMutableList()
        val duplicateTargets = classified.filter { it.status == PlannedFileStatus.PLACED }
            .groupBy { it.normalizedTargetKey }
            .filterKeys { it != null }
            .filterValues { files -> files.map { it.sourceRelativePath.lowercase(Locale.ROOT) }.distinct().size > 1 }
        if (duplicateTargets.isNotEmpty()) {
            duplicateTargets.values.flatten().forEach { duplicate ->
                val indexOfFile = classified.indexOf(duplicate)
                classified[indexOfFile] = duplicate.copy(
                    status = PlannedFileStatus.CONFLICTED,
                    reason = "Multiple archive files target one Windows path",
                    risk = PlacementRisk.REVIEW,
                )
            }
        }
        val blockers = buildList {
            if (index.caseCollisions.isNotEmpty()) add("Archive contains case-colliding file paths")
            if (classified.any { it.status == PlannedFileStatus.UNSUPPORTED }) add("Some installable files have no proven destination")
            if (duplicateTargets.isNotEmpty()) add("Multiple files target the same Windows path")
            if (classified.any { it.risk == PlacementRisk.UNSAFE }) add("Risky game-root installer content requires review")
        }
        val plan = ModInstallPlan(classified, blockingIssues = blockers)
        val score = (plan.coverage * 1_000).toInt() + evidence.size * 25 - blockers.size * 250
        return AutomaticPlacementCandidate(id, label, description, drafts, plan, score, evidence)
    }

    private fun preservesExistingDestinations(baseline: ModInstallPlan, candidate: ModInstallPlan): Boolean {
        val candidateTargets = candidate.files.associate { it.sourceRelativePath.lowercase(Locale.ROOT) to it.normalizedTargetKey }
        return baseline.files.filter { it.status == PlannedFileStatus.PLACED }.all { old ->
            candidateTargets[old.sourceRelativePath.lowercase(Locale.ROOT)] == old.normalizedTargetKey
        }
    }

    private fun String.removePrefixCaseInsensitive(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
