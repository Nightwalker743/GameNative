package app.gamenative.mods

import java.io.File
import java.util.Locale

data class FomodEnvironmentSnapshot(
    val gameName: String = "",
    val gameVersion: String? = null,
    val fileFacts: Map<String, Boolean> = emptyMap(),
    val presentPlugins: Set<String> = emptySet(),
    val activePlugins: Set<String> = emptySet(),
) {
    fun evaluate(dependency: FomodFileDependency): FomodFactState {
        val key = dependency.file.normalizedFactKey()
        val present = fileFacts[key] ?: return FomodFactState.UNKNOWN
        val pluginName = dependency.file.substringAfterLast('/').lowercase(Locale.ROOT)
        val isPlugin = pluginName.substringAfterLast('.', "") in setOf("esp", "esm", "esl")
        val active = pluginName in activePlugins
        return requiredState(dependency.state, present, active, isPlugin)
    }

    fun evaluate(dependency: FomodPluginDependency): FomodFactState {
        val key = dependency.plugin.substringAfterLast('/').lowercase(Locale.ROOT)
        val present = when {
            key in activePlugins -> true
            key in presentPlugins -> true
            presentPlugins.isNotEmpty() -> false
            else -> return FomodFactState.UNKNOWN
        }
        return requiredState(dependency.state, present, key in activePlugins, isPlugin = true)
    }

    fun evaluate(dependency: FomodGameDependency): FomodFactState {
        val current = gameVersion ?: return FomodFactState.UNKNOWN
        return if (compareVersions(current, dependency.version) >= 0) FomodFactState.TRUE else FomodFactState.FALSE
    }

    private fun requiredState(
        state: FomodRequiredFileState,
        present: Boolean,
        active: Boolean,
        isPlugin: Boolean,
    ): FomodFactState =
        when (state) {
            FomodRequiredFileState.ACTIVE -> if (present && (!isPlugin || active)) FomodFactState.TRUE else FomodFactState.FALSE
            FomodRequiredFileState.INACTIVE -> if (present && isPlugin && !active) FomodFactState.TRUE else FomodFactState.FALSE
            FomodRequiredFileState.MISSING -> if (!present) FomodFactState.TRUE else FomodFactState.FALSE
        }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.versionParts()
        val rightParts = right.versionParts()
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun String.versionParts(): List<Int> =
        split(Regex("[^0-9]+")).filter(String::isNotBlank).mapNotNull(String::toIntOrNull)
}

object FomodEnvironmentSnapshotBuilder {
    fun build(
        installer: FomodInstaller,
        gameName: String,
        gameRootDir: File?,
        pluginsFile: File? = null,
        gameVersion: String? = null,
    ): FomodEnvironmentSnapshot {
        val requestedFiles = installer.dependencyExpressions()
            .flatMap { it.fileDependencies }
            .map { it.file }
            .distinctBy { it.normalizedFactKey() }
        val requestedPlugins = installer.dependencyExpressions()
            .flatMap { it.pluginDependencies }
            .map { it.plugin }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val fileFacts = if (gameRootDir == null) {
            emptyMap()
        } else {
            requestedFiles.associate { requested ->
                requested.normalizedFactKey() to resolveRequestedFile(gameRootDir, requested).isFile
            }
        }
        val presentPlugins = (requestedFiles + requestedPlugins).asSequence()
            .filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("esp", "esm", "esl") }
            .filter { requested ->
                fileFacts[requested.normalizedFactKey()] == true ||
                    (gameRootDir != null && resolveRequestedFile(gameRootDir, requested).isFile)
            }
            .mapTo(mutableSetOf()) { it.substringAfterLast('/').lowercase(Locale.ROOT) }
        val activePlugins = pluginsFile?.takeIf(File::isFile)?.readLines().orEmpty()
            .map { it.trim().removePrefix("*").substringBefore('#').trim().lowercase(Locale.ROOT) }
            .filterTo(mutableSetOf(), String::isNotBlank)
        return FomodEnvironmentSnapshot(gameName, gameVersion, fileFacts, presentPlugins, activePlugins)
    }

    private fun resolveRequestedFile(gameRootDir: File?, requested: String): File {
        val root = gameRootDir ?: return File("")
        val relative = normalizeArchiveDisplayPath(requested)
        return listOf(relative, "Data/$relative")
            .mapNotNull { candidate -> ModTargetResolver.resolveWithin(root, candidate) }
            .firstOrNull { it.exists() }
            ?: File(root, relative)
    }
}

private fun FomodInstaller.dependencyExpressions(): List<FomodDependencyExpression> =
    listOf(moduleDependencies) + conditionalFileInstalls.map { it.dependencies } +
        steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }.map { it.dependencies }

private fun String.normalizedFactKey(): String =
    normalizeArchiveDisplayPath(this).lowercase(Locale.ROOT)
