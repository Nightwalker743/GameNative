package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V25_to_V26
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPlacementMigrationAndroidTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
    )

    @Test
    fun migrate25To26_preservesInstallAndRecipe() {
        helper.createDatabase(DATABASE_NAME, 25).apply {
            execSQL(
                """
                INSERT INTO mod_install (
                    install_id, app_id, source, nexus_game_domain, nexus_mod_id, nexus_file_id,
                    mod_name, file_name, version, size_bytes, archive_path, extracted_path,
                    enabled, status, created_at, updated_at, downloaded_at, metadata_json, archive_sha256
                ) VALUES (
                    'install-1', 'steam:489830', 'LOCAL_ARCHIVE', NULL, NULL, NULL,
                    'Historical mod', 'historical.zip', '1.0', 42, '/archive.zip', '/extracted',
                    1, 'APPLIED', 1, 2, 3, '{}', 'archive-hash'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO mod_placement_recipe (
                    install_id, source_subpath, target_root, target_relative_path, mode,
                    strip_prefix_segments, include_source_directory, enabled
                ) VALUES (
                    'install-1', 'Scripts', 'GAME_DIR', 'Data', 'OVERWRITE_COPY', 0, 1, 1
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            26,
            true,
            ROOM_MIGRATION_V25_to_V26,
        ).use { database ->
            database.query(
                """
                SELECT i.mod_name, r.source_subpath, r.target_relative_path, r.target_file_name
                FROM mod_install i
                JOIN mod_placement_recipe r ON r.install_id = i.install_id
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Historical mod", cursor.getString(0))
                assertEquals("Scripts", cursor.getString(1))
                assertEquals("Data", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "mod-placement-migration"
    }
}
