package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import java.io.File

data class ResolvedModTargetRoot(
    val type: ModTargetRoot,
    val label: String,
    val dir: File,
)

data class ModTargetPlanInspection(
    val caseMerges: List<String>,
    val ambiguousPaths: List<String>,
)

object ModTargetResolver {
    fun normalizeRelativePath(path: String): String =
        path.trim().replace('\\', '/').trim('/')

    fun normalizedTargetKey(targetRoot: String, targetRelativePath: String): String? =
        if (targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
            WindowsPathIdentity.absoluteKey(File(targetRelativePath.trim().replace('\\', '/')))
        } else {
            WindowsPathIdentity.targetKey(targetRoot, targetRelativePath)
        }

    fun roots(gameRootDir: File?, winePrefix: String): List<ResolvedModTargetRoot> {
        val result = mutableListOf<ResolvedModTargetRoot>()
        if (gameRootDir?.isDirectory == true) {
            result += ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game Directory", gameRootDir)
        }
        if (winePrefix.isNotBlank()) {
            val driveC = File(winePrefix, "drive_c")
            if (driveC.isDirectory) {
                result += ResolvedModTargetRoot(ModTargetRoot.WINE_C, "C: Drive", driveC)
                val userHome = ModContainerResolver.getWineUserHome(winePrefix)
                result += ResolvedModTargetRoot(ModTargetRoot.DOCUMENTS, "My Documents", File(userHome, "Documents"))
                result += ResolvedModTargetRoot(ModTargetRoot.MY_GAMES, "My Games", File(userHome, "Documents/My Games"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_ROAMING, "AppData / Roaming", File(userHome, "AppData/Roaming"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCAL, "AppData / Local", File(userHome, "AppData/Local"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCALLOW, "AppData / LocalLow", File(userHome, "AppData/LocalLow"))
            }
        }
        return result
    }

    fun resolve(
        targetRoot: String,
        targetRelativePath: String,
        gameRootDir: File?,
        winePrefix: String,
    ): File? {
        val rootType = runCatching { ModTargetRoot.valueOf(targetRoot) }.getOrNull() ?: return null
        if (rootType == ModTargetRoot.CUSTOM_ABSOLUTE) {
            val rawTarget = File(targetRelativePath.trim().replace('\\', '/'))
            if (!rawTarget.isAbsolute) return null
            val target = rawTarget.safeCanonicalFile() ?: return null
            val allowedRoots = roots(gameRootDir, winePrefix).mapNotNull { it.dir.safeCanonicalFile() }
            return target.takeIf { candidate ->
                allowedRoots.any { root -> candidate.isInsideOrEqual(root) }
            }
        }
        val root = roots(gameRootDir, winePrefix).firstOrNull { it.type == rootType }?.dir ?: return null
        if (WindowsPathIdentity.relativeSegments(targetRelativePath) == null) return null
        val cleanRelative = normalizeRelativePath(targetRelativePath)
        val rootCanonical = root.safeCanonicalFile() ?: return null
        val target = WindowsTargetNamespace(rootCanonical).resolve(cleanRelative).takeIf { it.isValid }?.file ?: return null
        return target.takeIf { it.isInsideOrEqual(rootCanonical) }
    }

    fun resolveWithin(root: File, relativePath: String): File? {
        val rootCanonical = root.safeCanonicalFile() ?: return null
        val resolution = WindowsTargetNamespace(rootCanonical).resolve(relativePath)
        val target = resolution.takeIf { it.isValid }?.file ?: return null
        return target.takeIf { it.isInsideOrEqual(rootCanonical) }
    }

    fun inspectPlan(
        plan: ModInstallPlan,
        resolvedRoots: List<ResolvedModTargetRoot>,
    ): ModTargetPlanInspection {
        val caseMerges = mutableSetOf<String>()
        val ambiguities = mutableSetOf<String>()
        val namespaces = resolvedRoots.associate { it.type.name to WindowsTargetNamespace(it.dir) }
        plan.files.filter { it.status == PlannedFileStatus.PLACED }.forEach { file ->
            val relative = file.targetRelativePath ?: return@forEach
            val namespace = namespaces[file.targetRoot] ?: return@forEach
            val resolution = namespace.resolve(relative)
            caseMerges += resolution.caseMerges
            if (resolution.ambiguousSegments.isNotEmpty()) ambiguities += relative
        }
        return ModTargetPlanInspection(caseMerges.sorted(), ambiguities.sorted())
    }

    private fun File.safeCanonicalFile(): File? =
        runCatching { canonicalFile }.getOrNull()

    private fun File.isInsideOrEqual(root: File): Boolean {
        if (this == root) return true
        val rootPath = root.path
        if (rootPath == File.separator) return path.startsWith(rootPath)
        return path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }
}
