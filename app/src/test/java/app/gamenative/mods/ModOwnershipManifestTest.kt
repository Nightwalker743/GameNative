package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    private fun manifest(installId: String, targetPath: String, hash: String, priority: Int): ModOwnershipManifest {
        val target = File("C:/Game/$targetPath")
        return ModOwnershipManifest(
            installId = installId,
            appId = "game",
            planDigest = "digest-$installId",
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = "x.pex",
                    targetRoot = "GAME_DIR",
                    targetRelativePath = targetPath,
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
