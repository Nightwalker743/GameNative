package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Test

class FomodEnvironmentTest {
    @Test
    fun dependencies_preserveUnknownAndEvaluateSupportedFacts() {
        val expression = FomodDependencyExpression(
            fileDependencies = listOf(FomodFileDependency("Data/Required.dll", FomodRequiredFileState.ACTIVE)),
            pluginDependencies = listOf(FomodPluginDependency("Example.esp", FomodRequiredFileState.ACTIVE)),
            gameDependencies = listOf(FomodGameDependency("1.6.0")),
        )

        assertEquals(FomodFactState.UNKNOWN, expression.evaluate(emptyMap(), FomodEnvironmentSnapshot()))
        assertEquals(
            FomodFactState.TRUE,
            expression.evaluate(
                emptyMap(),
                FomodEnvironmentSnapshot(
                    gameVersion = "1.6.1170",
                    fileFacts = mapOf("data/required.dll" to true),
                    presentPlugins = setOf("example.esp"),
                    activePlugins = setOf("example.esp"),
                ),
            ),
        )
    }

    @Test
    fun unknownConditional_blocksInsteadOfGuessing() {
        val installer = FomodInstaller(
            moduleName = "Dependencies",
            requiredFiles = emptyList(),
            steps = emptyList(),
            conditionalFileInstalls = listOf(
                FomodConditionalFileInstall(
                    FomodDependencyExpression(
                        fileDependencies = listOf(FomodFileDependency("Data/Maybe.dll", FomodRequiredFileState.ACTIVE)),
                    ),
                    listOf(FomodFileMapping("Maybe.dll", "Maybe.dll", 0, directory = false)),
                ),
            ),
        )

        val result = FomodSelectionEvaluator.evaluate(installer, emptySet())

        assertTrue(result.mappings.isEmpty())
        assertTrue(result.blockingIssues.any { "unknown" in it.lowercase() })
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
