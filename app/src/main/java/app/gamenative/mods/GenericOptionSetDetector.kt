package app.gamenative.mods

import java.util.Locale

data class GenericOptionChoice(
    val sourceDirectory: String,
    val overlappingTargetCount: Int,
)

data class GenericOptionGroup(
    val stableId: String,
    val choices: List<GenericOptionChoice>,
    val commonSourceDirectories: List<String>,
    val reason: String,
)

object GenericOptionSetDetector {
    fun detect(index: ModArchiveIndex): List<GenericOptionGroup> {
        if (index.hasFomod) return emptyList()
        val roots = index.nodes.filter { '/' !in it.displayPath && it.descendantFileCount > 0 }
        val signatures = roots.associateWith { root ->
            index.filesUnder(root.displayPath).mapTo(mutableSetOf()) { file ->
                file.normalizedKey.removePrefix("${root.normalizedKey}/")
            }
        }
        val related = roots.associateWith { root ->
            roots.filter { other ->
                if (root == other) return@filter false
                val overlap = signatures.getValue(root).intersect(signatures.getValue(other)).size
                val smaller = minOf(signatures.getValue(root).size, signatures.getValue(other).size).coerceAtLeast(1)
                overlap > 0 && (overlap.toDouble() / smaller >= 0.6 || root.optionStyleWrapper || other.optionStyleWrapper)
            }
        }
        val visited = mutableSetOf<String>()
        val groups = mutableListOf<List<ArchiveTreeNode>>()
        roots.forEach { root ->
            if (!visited.add(root.normalizedKey)) return@forEach
            val component = mutableListOf(root)
            val queue = ArrayDeque(related.getValue(root))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!visited.add(next.normalizedKey)) continue
                component += next
                queue.addAll(related.getValue(next))
            }
            if (component.size > 1) groups += component
        }
        val optionRootKeys = groups.flatten().mapTo(mutableSetOf()) { it.normalizedKey }
        val common = roots.filter { it.normalizedKey !in optionRootKeys }.map { it.displayPath }.sorted()
        return groups.map { choices ->
            GenericOptionGroup(
                stableId = choices.map { it.normalizedKey }.sorted().joinToString("|").hashCode().toUInt().toString(16),
                choices = choices.sortedBy { it.normalizedKey }.map { root ->
                    val peers = choices.filter { it != root }
                    GenericOptionChoice(
                        sourceDirectory = root.displayPath,
                        overlappingTargetCount =
                        peers.maxOfOrNull { signatures.getValue(root).intersect(signatures.getValue(it)).size } ?: 0,
                    )
                },
                commonSourceDirectories = common,
                reason = "Sibling folders contain competing files for the same normalized targets",
            )
        }.sortedBy { it.choices.first().sourceDirectory.lowercase(Locale.ROOT) }
    }
}
