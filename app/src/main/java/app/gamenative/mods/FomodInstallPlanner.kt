package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import java.io.File
import java.util.Locale

data class FomodExpectedMapping(
    val mapping: FomodFileMapping,
    val origin: PlacementOrigin,
    val ordinal: Int,
)

data class FomodSelectionEvaluation(
    val mappings: List<FomodExpectedMapping>,
    val flags: Map<String, String>,
    val blockingIssues: List<String>,
)

object FomodSelectionEvaluator {
    fun evaluate(
        installer: FomodInstaller,
        selectedPluginKeys: Set<String>,
        environment: FomodEnvironmentSnapshot = FomodEnvironmentSnapshot(),
    ): FomodSelectionEvaluation {
        val selectedPlugins = FomodRecipeGenerator.selectedPluginsForKeys(installer, selectedPluginKeys, environment)
        val flags = linkedMapOf<String, String>()
        selectedPlugins.forEach { plugin -> plugin.conditionFlags.forEach { (name, value) -> flags[name] = value } }
        var ordinal = 0
        val expected = buildList {
            installer.requiredFiles.forEach { mapping ->
                add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_REQUIRED, ordinal++))
            }
            selectedPlugins.forEach { plugin ->
                plugin.files.forEach { mapping ->
                    add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_OPTION, ordinal++))
                }
            }
            installer.conditionalFileInstalls.forEach { conditional ->
                if (conditional.dependencies.evaluate(flags, environment) == FomodFactState.TRUE) {
                    conditional.files.forEach { mapping ->
                        add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_CONDITIONAL, ordinal++))
                    }
                }
            }
        }
        val blockers = buildList {
            addAll(installer.unsupportedWarnings)
            when (installer.moduleDependencies.evaluate(flags, environment)) {
                FomodFactState.FALSE -> add("The installed game does not satisfy this FOMOD's requirements")
                FomodFactState.UNKNOWN -> if (installer.moduleDependencies.hasFacts()) {
                    add("FOMOD game requirements could not be determined safely")
                }
                FomodFactState.TRUE -> Unit
            }
            if (installer.conditionalFileInstalls.any { it.dependencies.evaluate(flags, environment) == FomodFactState.UNKNOWN }) {
                add("A selected FOMOD conditional depends on unknown game facts")
            }
            if (installer.conditionalFileInstalls.any { it.dependencies.unsupportedCount() > 0 }) {
                add("A selected FOMOD conditional uses unsupported dependencies")
            }
            if (
                installer.steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }
                    .any { it.dependencies.unsupportedCount() > 0 }
            ) {
                add("FOMOD option availability depends on unsupported game facts")
            }
            if (
                installer.steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }
                    .any { it.dependencies.evaluate(flags, environment) == FomodFactState.UNKNOWN }
            ) {
                add("FOMOD option availability depends on unknown game facts")
            }
        }
        return FomodSelectionEvaluation(expected, flags, blockers.distinct())
    }

    private fun FomodDependencyExpression.hasFacts(): Boolean =
        flagDependencies.isNotEmpty() || fileDependencies.isNotEmpty() || pluginDependencies.isNotEmpty() ||
            gameDependencies.isNotEmpty() || childGroups.isNotEmpty() || unsupportedDependencyCount > 0
}

object FomodPlanExpander {
    fun expand(
        installer: FomodInstaller,
        evaluation: FomodSelectionEvaluation,
        extractedRoot: File,
        targetRoot: String = ModTargetRoot.GAME_DIR.name,
        targetRelativePath: String = "Data",
    ): ModInstallPlan {
        val root = extractedRoot.canonicalFile
        val expanded = mutableListOf<ExpandedFomodFile>()
        val missing = mutableListOf<PlannedModFile>()

        evaluation.mappings.forEach { expected ->
            val sourcePath = joinPath(installer.basePath, expected.mapping.source)
            val source = resolveCaseInsensitive(root, sourcePath)
            when {
                source == null || !source.exists() -> missing += expected.missingFile(sourcePath)
                expected.mapping.directory && !source.isDirectory -> missing += expected.missingFile(sourcePath)
                !expected.mapping.directory && !source.isFile -> missing += expected.missingFile(sourcePath)
                expected.mapping.directory -> {
                    val sourceRoot = source.canonicalFile
                    source.walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { file -> file.relativeTo(sourceRoot).path.lowercase(Locale.ROOT) }
                        .forEach { file ->
                            val relative = file.canonicalFile.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                            val destination = joinPath(targetRelativePath, expected.mapping.destination, relative)
                            expanded += expected.expanded(file, root, targetRoot, destination)
                        }
                }
                else -> {
                    val destination = if (expected.mapping.destination.isBlank()) {
                        joinPath(targetRelativePath, source.name)
                    } else {
                        joinPath(targetRelativePath, expected.mapping.destination)
                    }
                    expanded += expected.expanded(source, root, targetRoot, destination)
                }
            }
        }

        val planned = expanded.map { it.file }.toMutableList()
        expanded.groupBy { it.file.normalizedTargetKey }.filterKeys { it != null }.values.forEach { contenders ->
            if (contenders.size < 2) return@forEach
            val winner = contenders.maxWithOrNull(
                compareBy<ExpandedFomodFile> { it.file.priority }.thenBy { it.ordinal },
            ) ?: return@forEach
            contenders.filter { it !== winner }.forEach { loser ->
                val index = planned.indexOf(loser.file)
                planned[index] = loser.file.copy(
                    status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                    reason = "Superseded by a higher-priority FOMOD mapping",
                )
            }
        }
        planned += missing
        val blockers = buildList {
            addAll(evaluation.blockingIssues)
            if (missing.isNotEmpty()) add("${missing.size} selected FOMOD mapping(s) have missing or mismatched sources")
            if (planned.any { it.status == PlannedFileStatus.UNSUPPORTED }) add("Some selected FOMOD destinations are invalid")
        }
        return ModInstallPlan(
            files = planned.sortedWith(
                compareBy<PlannedModFile> { it.normalizedTargetKey.orEmpty() }
                    .thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) }
                    .thenByDescending { it.priority },
            ),
            blockingIssues = blockers.distinct(),
        )
    }

    private data class ExpandedFomodFile(
        val file: PlannedModFile,
        val ordinal: Int,
    )

    private fun FomodExpectedMapping.expanded(
        source: File,
        extractedRoot: File,
        targetRoot: String,
        destination: String,
    ): ExpandedFomodFile {
        val targetKey = WindowsPathIdentity.targetKey(targetRoot, destination)
        return ExpandedFomodFile(
            file = PlannedModFile(
                sourceRelativePath = source.canonicalFile.relativeTo(extractedRoot).path.replace(File.separatorChar, '/'),
                targetRoot = targetRoot,
                targetRelativePath = destination,
                normalizedTargetKey = targetKey,
                status = if (targetKey == null) PlannedFileStatus.UNSUPPORTED else PlannedFileStatus.PLACED,
                origin = origin,
                priority = mapping.priority,
                sizeBytes = source.length(),
                reason = "Selected FOMOD ${origin.name.lowercase(Locale.ROOT).replace('_', ' ')} mapping",
                risk = if (targetKey == null) PlacementRisk.REVIEW else PlacementRisk.SAFE,
            ),
            ordinal = ordinal,
        )
    }

    private fun FomodExpectedMapping.missingFile(sourcePath: String): PlannedModFile = PlannedModFile(
        sourceRelativePath = sourcePath,
        status = PlannedFileStatus.MISSING,
        origin = origin,
        priority = mapping.priority,
        reason = "Selected FOMOD source is missing or has the wrong file type",
        risk = PlacementRisk.REVIEW,
    )

    private fun resolveCaseInsensitive(root: File, relativePath: String): File? {
        val segments = normalizedArchiveKey(relativePath)?.split('/').orEmpty()
        val displaySegments = normalizeArchiveDisplayPath(relativePath).split('/').filter(String::isNotBlank)
        if (segments.size != displaySegments.size) return null
        var current = root
        displaySegments.forEach { segment ->
            val matches = current.listFiles().orEmpty().filter { it.name.equals(segment, ignoreCase = true) }
            if (matches.size != 1) return null
            current = matches.single()
        }
        val candidate = runCatching { current.canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun joinPath(vararg paths: String): String =
        paths.map(::normalizeArchiveDisplayPath).filter(String::isNotBlank).joinToString("/")
}
