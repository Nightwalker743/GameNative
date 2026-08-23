package app.gamenative.mods

import java.util.Locale

enum class ArchiveContentRole {
    INSTALLABLE,
    DOCUMENTATION,
    METADATA,
    INSTALLER_SUPPORT,
    RISKY_ROOT,
    INVALID,
}

data class IndexedArchiveFile(
    val displayPath: String,
    val normalizedKey: String,
    val sizeBytes: Long,
    val role: ArchiveContentRole,
)

data class ArchiveTreeNode(
    val displayPath: String,
    val normalizedKey: String,
    val descendantFileCount: Int,
    val descendantBytes: Long,
    val semanticAnchors: Set<String>,
    val optionStyleWrapper: Boolean,
)

data class ModArchiveIndex(
    val files: List<IndexedArchiveFile>,
    val nodes: List<ArchiveTreeNode>,
    val caseCollisions: Map<String, List<String>>,
) {
    val hasFomod: Boolean
        get() = files.any { it.normalizedKey.endsWith("fomod/moduleconfig.xml") }

    fun filesUnder(sourcePath: String): List<IndexedArchiveFile> {
        val key = normalizedArchiveKey(sourcePath) ?: return emptyList()
        if (key.isBlank()) return files
        return files.filter { it.normalizedKey == key || it.normalizedKey.startsWith("$key/") }
    }

    fun isDirectory(sourcePath: String): Boolean {
        val key = normalizedArchiveKey(sourcePath) ?: return false
        return nodes.any { it.normalizedKey == key } || files.any { it.normalizedKey.startsWith("$key/") }
    }

    companion object {
        private val semanticAnchors = setOf(
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
        )

        fun build(entries: List<ModArchiveEntry>): ModArchiveIndex {
            val indexedFiles = entries.asSequence()
                .filterNot { it.directory }
                .map { entry ->
                    val display = normalizeArchiveDisplayPath(entry.path)
                    val key = normalizedArchiveKey(entry.path)
                    IndexedArchiveFile(
                        displayPath = display.ifBlank { entry.path },
                        normalizedKey = key.orEmpty(),
                        sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                        role = if (key == null) ArchiveContentRole.INVALID else classify(display),
                    )
                }
                .sortedWith(compareBy<IndexedArchiveFile> { it.normalizedKey }.thenBy { it.displayPath })
                .toList()
            val directoryPaths = buildSet {
                entries.filter { it.directory }.forEach { entry ->
                    normalizeArchiveDisplayPath(entry.path).takeIf(String::isNotBlank)?.let(::add)
                }
                indexedFiles.forEach { file ->
                    val segments = file.displayPath.split('/')
                    (1 until segments.size).forEach { count -> add(segments.take(count).joinToString("/")) }
                }
            }
            val nodes = directoryPaths.mapNotNull { path ->
                val key = normalizedArchiveKey(path) ?: return@mapNotNull null
                val descendants = indexedFiles.filter { it.normalizedKey.startsWith("$key/") }
                ArchiveTreeNode(
                    displayPath = path,
                    normalizedKey = key,
                    descendantFileCount = descendants.size,
                    descendantBytes = descendants.sumOf { it.sizeBytes },
                    semanticAnchors = descendants.flatMapTo(mutableSetOf()) { descendant ->
                        descendant.normalizedKey.split('/').filter { it in semanticAnchors }
                    },
                    optionStyleWrapper = looksLikeOptionWrapper(path.substringAfterLast('/')),
                )
            }.sortedBy { it.normalizedKey }
            return ModArchiveIndex(
                files = indexedFiles,
                nodes = nodes,
                caseCollisions = indexedFiles.groupBy { it.normalizedKey }
                    .filterValues { variants -> variants.map { it.displayPath }.distinct().size > 1 }
                    .mapValues { (_, variants) -> variants.map { it.displayPath }.distinct().sorted() },
            )
        }

        private fun classify(path: String): ArchiveContentRole {
            val normalized = path.lowercase(Locale.ROOT)
            val name = normalized.substringAfterLast('/')
            if (normalized.startsWith("__macosx/") || name in setOf(".ds_store", "thumbs.db", "desktop.ini")) {
                return ArchiveContentRole.METADATA
            }
            if (normalized.contains("/fomod/") || normalized.startsWith("fomod/")) {
                return ArchiveContentRole.INSTALLER_SUPPORT
            }
            if (
                name.startsWith("readme") ||
                name.startsWith("changelog") ||
                name.startsWith("license") ||
                name.endsWith(".md") ||
                name.endsWith(".pdf")
            ) {
                return ArchiveContentRole.DOCUMENTATION
            }
            if (!normalized.contains('/') && listOf(".dll", ".asi", ".exe", ".ini", ".bat", ".cmd", ".ps1", ".msi").any(name::endsWith)) {
                return ArchiveContentRole.RISKY_ROOT
            }
            return ArchiveContentRole.INSTALLABLE
        }

        private fun looksLikeOptionWrapper(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT)
            return Regex("^\\d{1,2}[ _.-]").containsMatchIn(normalized) ||
                listOf("optional", "option", "variant", "choose", "pick one").any(normalized::contains)
        }
    }
}

internal fun normalizeArchiveDisplayPath(path: String): String =
    path.trim().replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")

internal fun normalizedArchiveKey(path: String): String? {
    val normalized = path.trim().replace('\\', '/')
    if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    val segments = normalized.split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/") { it.lowercase(Locale.ROOT) }
}
