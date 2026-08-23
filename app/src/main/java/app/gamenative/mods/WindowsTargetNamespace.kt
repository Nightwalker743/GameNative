package app.gamenative.mods

import java.io.File
import java.util.Locale

data class WindowsTargetResolution(
    val file: File?,
    val normalizedRelativeKey: String?,
    val caseMerges: List<String> = emptyList(),
    val ambiguousSegments: List<String> = emptyList(),
) {
    val isValid: Boolean
        get() = file != null && normalizedRelativeKey != null && ambiguousSegments.isEmpty()
}

class WindowsTargetNamespace(
    root: File,
) {
    private val root = root.canonicalFile
    private val listingCache = mutableMapOf<String, Map<String, List<File>>>()

    fun resolve(relativePath: String): WindowsTargetResolution {
        val segments = WindowsPathIdentity.relativeSegments(relativePath)
            ?: return WindowsTargetResolution(null, null)
        var current = root
        val caseMerges = mutableListOf<String>()
        val ambiguities = mutableListOf<String>()

        segments.forEachIndexed { index, requested ->
            val matches = childrenByWindowsName(current)[WindowsPathIdentity.segmentKey(requested)].orEmpty()
            when {
                matches.size > 1 -> {
                    ambiguities += segments.take(index + 1).joinToString("/")
                    return@forEachIndexed
                }
                matches.size == 1 -> {
                    val existing = matches.single()
                    if (existing.name != requested) {
                        caseMerges += "${segments.take(index).joinToString("/")}/$requested -> ${existing.name}"
                            .trimStart('/')
                    }
                    current = existing
                }
                else -> current = File(current, requested)
            }
        }

        return WindowsTargetResolution(
            file = current.takeIf { ambiguities.isEmpty() },
            normalizedRelativeKey = segments.joinToString("/") { WindowsPathIdentity.segmentKey(it) },
            caseMerges = caseMerges,
            ambiguousSegments = ambiguities,
        )
    }

    fun invalidate() {
        listingCache.clear()
    }

    private fun childrenByWindowsName(dir: File): Map<String, List<File>> {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return listingCache.getOrPut(key) {
            if (!dir.isDirectory) {
                emptyMap()
            } else {
                dir.listFiles().orEmpty().groupBy { WindowsPathIdentity.segmentKey(it.name) }
            }
        }
    }
}

object WindowsPathIdentity {
    private val reservedNames = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        (1..9).forEach { index ->
            add("com$index")
            add("lpt$index")
        }
    }

    fun normalizedRelativeKey(path: String): String? =
        relativeSegments(path)?.joinToString("/", transform = ::segmentKey)

    fun targetKey(targetRoot: String, relativePath: String): String? =
        normalizedRelativeKey(relativePath)?.let { "$targetRoot:${it}" }

    fun absoluteKey(file: File): String =
        file.absoluteFile.normalize().path
            .replace(File.separatorChar, '/')
            .split('/')
            .joinToString("/", transform = ::segmentKey)

    internal fun segmentKey(segment: String): String =
        segment.trimEnd(' ', '.').lowercase(Locale.ROOT)

    internal fun relativeSegments(path: String): List<String>? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val segments = normalized.split('/').filter(String::isNotBlank)
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.any(::isUnsafeWindowsSegment)) return null
        return segments
    }

    private fun isUnsafeWindowsSegment(segment: String): Boolean {
        val key = segmentKey(segment)
        if (key.isBlank() || key.substringBefore('.') in reservedNames) return true
        return segment.any { it.code < 32 || it in setOf('<', '>', ':', '"', '|', '?', '*') }
    }
}
