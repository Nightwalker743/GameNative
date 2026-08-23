package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModOwnershipManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sidecarRoundTrip_andOverlayWinner_areDeterministic() {
        val root = temporaryFolder.newFolder("cache")
        val low = manifest("low", "Data/Scripts/X.pex", "one", priority = 10)
        val high = manifest("high", "data/scripts/x.pex", "two", priority = 20)
        ModOwnershipStore.writePending(root, low)
        ModOwnershipStore.commit(root, low.installId)

        assertEquals(low, ModOwnershipStore.read(root, low.installId))
        val overlay = ModProfileOverlayPlanner.build(listOf(high, low), mapOf("high" to 20, "low" to 10))
        assertEquals("high", overlay.targets.values.single().winner.installId)
        assertEquals(1, overlay.conflicts.size)
        assertTrue(overlay.targets.values.single().hasCaseCollision)
    }

    @Test
    fun overlayTransition_comparesDesiredWinnerAgainstTheCurrentDeployedWinner() {
        val target = temporaryFolder.newFile("shared.txt").apply { writeText("low") }
        val low = manifest("low", target, ModOwnershipStore.sha256(target), priority = 20)
        val high = manifest("high", target, "different", priority = 10)

        val transition = ModProfileOverlayPlanner.transition(
            listOf(low, high),
            desiredPriorities = mapOf("low" to 10, "high" to 20),
        )

        assertEquals("low", transition.current.targets.values.single().winner.installId)
        assertEquals("high", transition.desired.targets.values.single().winner.installId)
        assertEquals(1, transition.changedWinnerKeys.size)
        assertTrue(transition.requiresRebuild)
        assertTrue(transition.safeToRebuild)
    }

    @Test
    fun staleCleanup_removesOnlyUnchangedOwnedFiles() = runBlocking {
        val targetRoot = temporaryFolder.newFolder("game")
        val unchanged = File(targetRoot, "unchanged.txt").apply { writeText("owned") }
        val modified = File(targetRoot, "modified.txt").apply { writeText("changed") }
        val ownedHash = ModOwnershipStore.sha256(temporaryFolder.newFile("source.txt").apply { writeText("owned") })
        val manifest = ModOwnershipManifest(
            installId = "install",
            appId = "game",
            planDigest = "digest",
            files = listOf(unchanged, modified).map { target ->
                ModOwnedFile(
                    sourceRelativePath = target.name,
                    targetRoot = "GAME_DIR",
                    targetRelativePath = target.name,
                    targetPath = target.absolutePath,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.COPY.name,
                    installedHash = ownedHash,
                    installedSize = 5,
                    installedMtime = 1,
                    disposition = ModOwnedFileDisposition.CREATED,
                )
            },
        )

        val result = ModOwnershipReconciler.removeOwnedFiles(manifest, emptyList())

        assertFalse(unchanged.exists())
        assertTrue(modified.exists())
        assertEquals(listOf(modified.absolutePath), result.skippedPaths)
    }

    @Test
    fun reconfigurationDiff_reportsAddedMovedAndStaleOutputs() {
        val previous = ModOwnershipManifest(
            installId = "install",
            appId = "game",
            planDigest = "old",
            files = listOf(
                owned("A.txt", "Data/A.txt"),
                owned("B.txt", "Data/B.txt"),
            ),
        )
        val next = ModInstallPlan(
            files = listOf(
                planned("A.txt", "Data/Moved/A.txt"),
                planned("C.txt", "Data/C.txt"),
            ),
            producerId = "fixture",
        )

        val diff = ModOwnershipPlanDiffer.compare(previous, next)

        assertEquals(1, diff.added)
        assertEquals(1, diff.moved)
        assertEquals(1, diff.stale)
    }

    private fun owned(source: String, target: String): ModOwnedFile = ModOwnedFile(
        sourceRelativePath = source,
        targetRoot = "GAME_DIR",
        targetRelativePath = target,
        targetPath = "C:/Game/$target",
        normalizedTargetKey = WindowsPathIdentity.absoluteKey(File("C:/Game/$target")),
        mode = ModPlacementMode.OVERWRITE_COPY.name,
        installedHash = source,
        installedSize = 1,
        installedMtime = 1,
        disposition = ModOwnedFileDisposition.CREATED,
    )

    private fun planned(source: String, target: String): PlannedModFile = PlannedModFile(
        sourceRelativePath = source,
        targetRoot = "GAME_DIR",
        targetRelativePath = target,
        normalizedTargetKey = WindowsPathIdentity.targetKey("GAME_DIR", target),
        status = PlannedFileStatus.PLACED,
        origin = PlacementOrigin.FOMOD_REQUIRED,
        reason = "fixture",
    )

    private fun manifest(installId: String, targetPath: String, hash: String, priority: Int): ModOwnershipManifest {
        val target = File("C:/Game/$targetPath")
        return manifest(installId, target, hash, priority)
    }

    private fun manifest(installId: String, target: File, hash: String, priority: Int): ModOwnershipManifest {
        return ModOwnershipManifest(
            installId = installId,
            appId = "game",
            planDigest = "digest-$installId",
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = "x.pex",
                    targetRoot = "GAME_DIR",
                    targetRelativePath = target.name,
                    targetPath = target.path,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    installedHash = hash,
                    installedSize = 1,
                    installedMtime = 1,
                    disposition = ModOwnedFileDisposition.OVERWROTE,
                    priority = priority,
                ),
            ),
        )
    }
}
