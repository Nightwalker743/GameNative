package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModInstallPlanContractTest {
    @Test
    fun plan_requiresEverySelectedFileToBePlacedOrExplained() {
        val complete = plan(placed = listOf("Sounds/theme.xwm"), ignored = listOf("readme.txt"))
        val partial = plan(placed = listOf("Sounds/theme.xwm"), unsupported = listOf("install.exe"))

        assertTrue(complete.isComplete)
        assertEquals(1.0, complete.coverage, 0.0)
        assertFalse(partial.isComplete)
        assertEquals(1, partial.unresolvedCount)
    }

    @Test
    fun differentialPolicy_neverTradesCoverageForAReplacement() {
        val baseline = plan(placed = listOf("Scripts/a.pex", "Plugin.esp"))
        val worse = plan(placed = listOf("Scripts/a.pex"), unsupported = listOf("Plugin.esp"))
        val better = plan(placed = listOf("Scripts/a.pex", "Plugin.esp", "Sounds/theme.xwm"))

        assertFalse(PlacementPlanRegressionPolicy.canReplace(baseline, worse))
        assertTrue(PlacementPlanRegressionPolicy.canReplace(baseline, better))
    }

    @Test
    fun digest_isStableAcrossArchiveEnumerationOrder() {
        val first = plan(placed = listOf("Scripts/a.pex", "Sounds/theme.xwm"))
        val second = plan(placed = listOf("Sounds/theme.xwm", "Scripts/a.pex"))

        assertEquals(first.digest, second.digest)
    }

    private fun plan(
        placed: List<String>,
        ignored: List<String> = emptyList(),
        unsupported: List<String> = emptyList(),
    ): ModInstallPlan = ModInstallPlan(
        files = buildList {
            placed.forEach { source ->
                add(file(source, PlannedFileStatus.PLACED, "Selected by fixture rule"))
            }
            ignored.forEach { source ->
                add(file(source, PlannedFileStatus.INTENTIONALLY_IGNORED, "Known documentation"))
            }
            unsupported.forEach { source ->
                add(file(source, PlannedFileStatus.UNSUPPORTED, "Requires review", PlacementRisk.REVIEW))
            }
        },
        blockingIssues = unsupported.map { "Unresolved installable file: $it" },
    )

    private fun file(
        source: String,
        status: PlannedFileStatus,
        reason: String,
        risk: PlacementRisk = PlacementRisk.SAFE,
    ): PlannedModFile = PlannedModFile(
        sourceRelativePath = source,
        targetRoot = ModTargetRoot.GAME_DIR.name.takeIf { status == PlannedFileStatus.PLACED },
        targetRelativePath = "Data/$source".takeIf { status == PlannedFileStatus.PLACED },
        normalizedTargetKey = "game_dir:data/${source.lowercase()}".takeIf { status == PlannedFileStatus.PLACED },
        status = status,
        origin = PlacementOrigin.GAME_RULE,
        sizeBytes = 1L,
        reason = reason,
        risk = risk,
    )
}
