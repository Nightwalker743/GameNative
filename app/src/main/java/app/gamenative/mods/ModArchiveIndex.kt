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
        val prefix = "$key/"
        var low = 0
        var high = files.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (files[middle].normalizedKey < key) low = middle + 1 else high = middle
        }
        var end = low
        while (end < files.size && (files[end].normalizedKey == key || files[end].normalizedKey.startsWith(prefix))) {
            end++
        }
        return files.subList(low, end).toList()
    }

    fun isDirectory(sourcePath: String): Boolean {
        val key = normalizedArchiveKey(sourcePath) ?: return false
        return nodes.binarySearchBy(key) { it.normalizedKey } >= 0
    }

    companion object {
        private val semanticAnchors = ModPlacementRulePacks.archiveSemanticAnchors

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
            val displayPaths = sortedMapOf<String, String>()
            val counts = mutableMapOf<String, Int>()
            val bytes = mutableMapOf<String, Long>()
            val anchors = mutableMapOf<String, MutableSet<String>>()
            entries.asSequence().filter { it.directory }.forEach { entry ->
                val display = normalizeArchiveDisplayPath(entry.path)
                val key = normalizedArchiveKey(display)
                if (display.isNotBlank() && key != null) displayPaths.merge(key, display, ::stableDisplayPath)
            }
            indexedFiles.forEach { file ->
                val displaySegments = file.displayPath.split('/')
                val keySegments = file.normalizedKey.split('/')
                val fileAnchors = keySegments.filterTo(mutableSetOf()) { it in semanticAnchors }
                (1 until keySegments.size).forEach { count ->
                    val key = keySegments.take(count).joinToString("/")
                    val display = displaySegments.take(count).joinToString("/")
                    displayPaths.merge(key, display, ::stableDisplayPath)
                    counts[key] = counts.getOrDefault(key, 0) + 1
                    bytes[key] = bytes.getOrDefault(key, 0L) + file.sizeBytes
                    anchors.getOrPut(key, ::mutableSetOf).addAll(fileAnchors)
                }
            }
            val nodes = displayPaths.map { (key, display) ->
                ArchiveTreeNode(
                    displayPath = display,
                    normalizedKey = key,
                    descendantFileCount = counts.getOrDefault(key, 0),
                    descendantBytes = bytes.getOrDefault(key, 0L),
                    semanticAnchors = anchors[key].orEmpty(),
                    optionStyleWrapper = looksLikeOptionWrapper(display.substringAfterLast('/')),
                )
            }
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

        private fun stableDisplayPath(left: String, right: String): String =
            minOf(left, right, compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
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
