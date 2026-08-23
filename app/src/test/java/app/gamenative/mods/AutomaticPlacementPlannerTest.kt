package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPlacementPlannerTest {
    @Test
    fun bethesdaPlan_placesMixedLooseContentWithoutFlatteningDirectories() {
        val entries = archive("Sounds/theme.xwm", "Scripts/menu.pex", "Example.esp", "readme.txt")

        val candidate = AutomaticPlacementPlanner.plan("Skyrim Special Edition", entries).recommended!!

        assertTrue(candidate.plan.isComplete)
        assertEquals(
            setOf("Data/Sounds/theme.xwm", "Data/Scripts/menu.pex", "Data/Example.esp"),
            candidate.plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertEquals(PlannedFileStatus.INTENTIONALLY_IGNORED, candidate.plan.files.single { it.sourceRelativePath == "readme.txt" }.status)
    }

    @Test
    fun bethesdaPlan_stripsOneWrapperAndDataContainer() {
        val candidate = AutomaticPlacementPlanner.plan(
            "Fallout 4",
            archive("Cool Mod/Data/meshes/rifle.nif", "Cool Mod/Data/textures/rifle.dds"),
        ).recommended!!

        assertEquals(
            setOf("Data/meshes/rifle.nif", "Data/textures/rifle.dds"),
            candidate.plan.files.mapNotNull { it.targetRelativePath }.toSet(),
        )
        assertTrue(candidate.plan.isComplete)
    }

    @Test
    fun automaticPlan_blocksUnexplainedInstallableFiles() {
        val candidate = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Sounds/theme.xwm", "Mystery/config.bin"),
        ).recommended!!

        assertFalse(candidate.plan.isComplete)
        assertEquals(PlannedFileStatus.UNSUPPORTED, candidate.plan.files.single { it.sourceRelativePath == "Mystery/config.bin" }.status)
        assertTrue(candidate.plan.blockingIssues.isNotEmpty())
    }

    @Test
    fun planningIsStableAcrossArchiveOrderAndManualFolderInferencePreservesContentFolder() {
        val first = archive("Sounds/theme.xwm", "Scripts/menu.pex", "Example.esp")
        val second = first.reversed()

        assertEquals(
            AutomaticPlacementPlanner.plan("Skyrim Special Edition", first).recommended!!.plan.digest,
            AutomaticPlacementPlanner.plan("Skyrim Special Edition", second).recommended!!.plan.digest,
        )
        assertTrue(AutomaticPlacementPlanner.inferIncludeSourceDirectory(listOf("Sounds"), first, "Data"))
        assertFalse(AutomaticPlacementPlanner.inferIncludeSourceDirectory(listOf("Data"), archive("Data/file.txt"), "Data"))
    }

    @Test
    fun ambiguousStructuralVariants_blockAutomaticChoiceAndKeepCommonFolderVisible() {
        val result = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Option A/Data/textures/x.dds", "Option B/Data/textures/x.dds", "Common/Data/scripts/y.pex"),
        )

        assertEquals(setOf("Option A", "Option B"), result.optionGroups.single().choices.map { it.sourceDirectory }.toSet())
        assertEquals(listOf("Common"), result.optionGroups.single().commonSourceDirectories)
        assertFalse(result.recommended!!.plan.isComplete)
        assertTrue(result.recommended!!.plan.blockingIssues.any { "variant" in it.lowercase() })

        val group = result.optionGroups.single()
        val selected = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Option A/Data/textures/x.dds", "Option B/Data/textures/x.dds", "Common/Data/scripts/y.pex"),
            selectedOptions = mapOf(group.stableId to "Option B"),
        ).recommended!!.plan
        assertTrue(selected.blockingIssues.toString(), selected.isComplete)
        assertEquals(
            setOf("Data/textures/x.dds", "Data/scripts/y.pex"),
            selected.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertTrue(selected.files.any { it.sourceRelativePath.startsWith("Option A/") && it.status == PlannedFileStatus.INTENTIONALLY_IGNORED })
    }

    @Test
    fun mixedDataAndRootBinary_areSeparatedAndRootBinaryRequiresReview() {
        val plan = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Data/Scripts/x.pex", "dinput8.dll"),
        ).recommended!!.plan

        assertEquals(
            setOf("Data/Scripts/x.pex", "dinput8.dll"),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.mapNotNull { it.targetRelativePath }.toSet(),
        )
        assertFalse(plan.isComplete)
        assertEquals(PlacementRisk.UNSAFE, plan.files.single { it.sourceRelativePath == "dinput8.dll" }.risk)
        val approved = plan.withRiskyRootApproval(true)
        assertTrue(approved.isComplete)
        assertFalse(approved.withRiskyRootApproval(false).isComplete)
    }

    @Test
    fun frameworkRules_produceCompletePlansWithoutRegressingLegacyTargets() {
        val cases = listOf(
            Triple("Any Unity game", archive("BepInEx/plugins/Test.dll", "BepInEx/config/Test.cfg"), setOf("BepInEx/plugins/Test.dll", "BepInEx/config/Test.cfg")),
            Triple("Another Unity game", archive("Mods/Test.dll", "UserData/settings.cfg"), setOf("Mods/Test.dll", "UserData/settings.cfg")),
            Triple("Any Unreal game", archive("Content/Paks/Test.pak", "Content/Paks/Test.utoc"), setOf("Content/Paks/Test.pak", "Content/Paks/Test.utoc")),
            Triple("Cyberpunk 2077", archive("archive/pc/mod/Test.archive", "r6/scripts/Test.reds"), setOf("archive/pc/mod/Test.archive", "r6/scripts/Test.reds")),
        )

        cases.forEach { (game, entries, targets) ->
            val plan = AutomaticPlacementPlanner.plan(game, entries).recommended!!.plan
            assertTrue("$game: ${plan.blockingIssues}", plan.isComplete)
            assertEquals(targets, plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet())
        }
    }

    private fun archive(vararg paths: String): List<ModArchiveEntry> =
        paths.map { ModArchiveEntry(it, directory = false, sizeBytes = 1L) }
}
