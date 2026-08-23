package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModInstall
import app.gamenative.data.ModTargetRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FomodInstallerTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("fomod_installer").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun parse_readsRequiredFilesAndOptionGroups() {
        val moduleConfig = writeModuleConfig(
            """
            <config>
                <moduleName>Example Installer</moduleName>
                <moduleDependencies operator="And">
                    <fileDependency file="Data/Required.dll" state="Active" />
                    <gameDependency version="1.6.0" />
                </moduleDependencies>
                <requiredInstallFiles>
                    <folder source="Common" destination="" priority="0" />
                </requiredInstallFiles>
                <installSteps>
                    <installStep name="Textures">
                        <optionalFileGroups>
                            <group name="Texture Size" type="SelectExactlyOne">
                                <plugins>
                                    <plugin name="2K">
                                        <description>Two kay textures</description>
                                        <typeDescriptor><type name="Recommended" /></typeDescriptor>
                                        <conditionFlags>
                                            <flag name="TextureSize">2K</flag>
                                        </conditionFlags>
                                        <files>
                                            <folder source="2K" destination="textures" priority="10" />
                                            <file source="Plugins/Example.esp" destination="Example.esp" priority="11" />
                                        </files>
                                    </plugin>
                                </plugins>
                            </group>
                        </optionalFileGroups>
                    </installStep>
                </installSteps>
            </config>
            """.trimIndent(),
        )

        val installer = FomodParser.parse(moduleConfig)

        assertEquals("Example Installer", installer.moduleName)
        assertEquals(1, installer.requiredFiles.size)
        assertEquals(FomodGroupType.SELECT_EXACTLY_ONE, installer.steps.single().groups.single().type)
        val plugin = installer.steps.single().groups.single().plugins.single()
        assertEquals("2K", plugin.name)
        assertEquals(FomodPluginType.RECOMMENDED, plugin.type)
        assertEquals("2K", plugin.conditionFlags["TextureSize"])
        assertEquals(2, plugin.files.size)
        assertEquals("Data/Required.dll", installer.moduleDependencies.fileDependencies.single().file)
        assertEquals("1.6.0", installer.moduleDependencies.gameDependencies.single().version)
    }

    @Test
    fun generate_convertsSelectedFilesToPlacementRecipes() {
        val installer = FomodInstaller(
            moduleName = "Example",
            requiredFiles = listOf(FomodFileMapping("Common", "", 0, directory = true)),
            steps = listOf(
                FomodStep(
                    name = "Step",
                    groups = listOf(
                        FomodGroup(
                            name = "Group",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin(
                                    name = "Option",
                                    description = "",
                                    imagePath = "",
                                    type = FomodPluginType.OPTIONAL,
                                    files = listOf(
                                        FomodFileMapping("Option", "textures", 1, directory = true),
                                        FomodFileMapping("Plugins/Example.esp", "Example.esp", 2, directory = false),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = FomodRecipeGenerator.generate(
            installId = "install",
            installer = installer,
            selectedPluginNames = setOf("Option"),
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )

        assertTrue(result.unsupportedMappings.isEmpty())
        assertEquals(
            listOf("Common->Data", "Option->Data/textures", "Plugins/Example.esp->Data"),
            result.recipes.map { "${it.sourceSubpath}->${it.targetRelativePath}" },
        )
    }

    @Test
    fun parseWithExtractedRoot_prefixesMappingsFromNestedFomodBase() {
        val wrapper = File(tempDir, "Wrapper")
        val moduleConfig = File(wrapper, "Fomod/ModuleConfig.xml").apply {
            parentFile?.mkdirs()
            writeText(
                """
                <config>
                    <moduleName>Nested</moduleName>
                    <requiredInstallFiles>
                        <folder source="00 Main" destination="" priority="0" />
                    </requiredInstallFiles>
                </config>
                """.trimIndent(),
            )
        }

        val installer = FomodParser.parse(moduleConfig, tempDir)
        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = "install",
            installer = installer,
            selectedPluginKeys = emptySet(),
        )

        assertEquals(listOf("Wrapper/00 Main"), result.recipes.map { it.sourceSubpath })
    }

    @Test
    fun detector_findsNestedCapitalizedFomodFolder() {
        val moduleConfig = File(tempDir, "Mod Name/Fomod/ModuleConfig.xml").apply {
            parentFile?.mkdirs()
            writeText("<config />")
        }

        assertEquals(moduleConfig.canonicalFile, FomodInstallerDetector.moduleConfigFile(tempDir)?.canonicalFile)
    }

    @Test
    fun parse_rejectsDoctypeXml() {
        val moduleConfig = writeModuleConfig(
            """
            <!DOCTYPE config [
                <!ENTITY local SYSTEM "file:///etc/passwd">
            ]>
            <config>
                <moduleName>&local;</moduleName>
            </config>
            """.trimIndent(),
        )

        val error = runCatching { FomodParser.parse(moduleConfig) }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    @Test
    fun generate_preservesRenamedFileDestination() {
        val installer = FomodInstaller(
            moduleName = "Example",
            requiredFiles = listOf(FomodFileMapping("Plugins/Source.esp", "Renamed.esp", 0, directory = false)),
            steps = emptyList(),
        )

        val result = FomodRecipeGenerator.generate(
            installId = "install",
            installer = installer,
            selectedPluginNames = emptySet(),
        )

        assertTrue(result.unsupportedMappings.isEmpty())
        assertEquals("Renamed.esp", result.recipes.single().targetFileName)
    }

    @Test
    fun generateForPluginKeys_handlesDuplicateOptionNames() {
        val installer = FomodInstaller(
            moduleName = "Example",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Step",
                    groups = listOf(
                        FomodGroup(
                            name = "A",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin("Default", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("A", "A", 0, true))),
                            ),
                        ),
                        FomodGroup(
                            name = "B",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin("Default", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("B", "B", 0, true))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = "install",
            installer = installer,
            selectedPluginKeys = setOf(FomodRecipeGenerator.pluginKey(0, 1, 0)),
        )

        assertEquals(listOf("B"), result.recipes.map { it.sourceSubpath })
    }

    @Test
    fun generate_rejectsAmbiguousDuplicateOptionNames() {
        val installer = FomodInstaller(
            moduleName = "Example",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Step",
                    groups = listOf(
                        FomodGroup(
                            name = "A",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin("Default", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("A", "A", 0, true))),
                            ),
                        ),
                        FomodGroup(
                            name = "B",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin("Default", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("B", "B", 0, true))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val error = runCatching {
            FomodRecipeGenerator.generate(
                installId = "install",
                installer = installer,
                selectedPluginNames = setOf("Default"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun generateForPluginKeys_includesMatchingConditionalFilesFromFlags() {
        val moduleConfig = writeModuleConfig(
            """
            <config>
                <moduleName>Conditional</moduleName>
                <installSteps>
                    <installStep name="Shape">
                        <optionalFileGroups>
                            <group name="Body" type="SelectExactlyOne">
                                <plugins>
                                    <plugin name="Slim">
                                        <conditionFlags>
                                            <flag name="BodyShape">Slim</flag>
                                        </conditionFlags>
                                    </plugin>
                                    <plugin name="Curvy">
                                        <conditionFlags>
                                            <flag name="BodyShape">Curvy</flag>
                                        </conditionFlags>
                                    </plugin>
                                </plugins>
                            </group>
                        </optionalFileGroups>
                    </installStep>
                </installSteps>
                <conditionalFileInstalls>
                    <patterns>
                        <pattern>
                            <dependencies operator="And">
                                <flagDependency flag="BodyShape" value="Slim" />
                            </dependencies>
                            <files>
                                <folder source="00 Required (Slim)" destination="" priority="0" />
                            </files>
                        </pattern>
                        <pattern>
                            <dependencies operator="And">
                                <flagDependency flag="BodyShape" value="Curvy" />
                            </dependencies>
                            <files>
                                <folder source="00 Required (Curvy)" destination="" priority="0" />
                            </files>
                        </pattern>
                    </patterns>
                </conditionalFileInstalls>
            </config>
            """.trimIndent(),
        )

        val installer = FomodParser.parse(moduleConfig)
        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = "install",
            installer = installer,
            selectedPluginKeys = setOf(FomodRecipeGenerator.pluginKey(0, 0, 0)),
        )

        assertTrue(installer.unsupportedWarnings.isEmpty())
        assertEquals(listOf("00 Required (Slim)->Data"), result.recipes.map { "${it.sourceSubpath}->${it.targetRelativePath}" })
    }

    @Test
    fun generateForPluginKeys_appliesDependencyTypePatternsFromFlags() {
        val moduleConfig = writeModuleConfig(
            """
            <config>
                <moduleName>Dynamic Types</moduleName>
                <installSteps>
                    <installStep name="Base">
                        <optionalFileGroups>
                            <group name="Variant" type="SelectExactlyOne">
                                <plugins>
                                    <plugin name="A">
                                        <conditionFlags>
                                            <flag name="Variant">A</flag>
                                        </conditionFlags>
                                    </plugin>
                                </plugins>
                            </group>
                        </optionalFileGroups>
                    </installStep>
                    <installStep name="Patch">
                        <optionalFileGroups>
                            <group name="Required Patch" type="SelectAny">
                                <plugins>
                                    <plugin name="Patch A">
                                        <typeDescriptor>
                                            <dependencyType>
                                                <defaultType name="NotUsable" />
                                                <patterns>
                                                    <pattern>
                                                        <dependencies operator="And">
                                                            <flagDependency flag="Variant" value="A" />
                                                        </dependencies>
                                                        <type name="Required" />
                                                    </pattern>
                                                </patterns>
                                            </dependencyType>
                                        </typeDescriptor>
                                        <files>
                                            <folder source="PatchA" destination="" priority="0" />
                                        </files>
                                    </plugin>
                                </plugins>
                            </group>
                        </optionalFileGroups>
                    </installStep>
                </installSteps>
            </config>
            """.trimIndent(),
        )

        val installer = FomodParser.parse(moduleConfig)
        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = "install",
            installer = installer,
            selectedPluginKeys = setOf(FomodRecipeGenerator.pluginKey(0, 0, 0)),
        )

        assertEquals(listOf("PatchA"), result.recipes.map { it.sourceSubpath })
    }

    @Test
    fun mcmHelperShape_plansAndAppliesEverySelectedFile() = runBlocking {
        val moduleConfig = writeModuleConfig(
            """
            <config>
              <moduleName>MCM Helper fixture</moduleName>
              <requiredInstallFiles>
                <file source="Required/config.json" destination="MCM/Config/SkyUI_SE/config.json" />
                <file source="Required/settings.ini" destination="MCM/Config/SkyUI_SE/settings.ini" />
                <file source="Required/readme.txt" destination="MCM/Settings/readme.txt" />
                <file source="Required/SKI_ConfigMenu.psc" destination="Source/Scripts/SKI_ConfigMenu.psc" />
              </requiredInstallFiles>
              <installSteps><installStep name="Choices"><optionalFileGroups>
                <group name="Runtime" type="SelectExactlyOne"><plugins>
                  <plugin name="Skyrim SE"><files><folder source="SkyrimSE" destination="" /></files></plugin>
                  <plugin name="Skyrim VR"><files><folder source="SkyrimVR" destination="" /></files></plugin>
                </plugins></group>
                <group name="Plugin" type="SelectExactlyOne"><plugins>
                  <plugin name="ESL"><files><file source="Plugins/MCMHelper.esl" destination="MCMHelper.esl" /></files></plugin>
                  <plugin name="ESP"><files><file source="Plugins/MCMHelper.esp" destination="MCMHelper.esp" /></files></plugin>
                </plugins></group>
                <group name="Assets" type="SelectExactlyOne"><plugins>
                  <plugin name="BSA"><files><file source="BSA/MCMHelper.bsa" destination="MCMHelper.bsa" /></files></plugin>
                  <plugin name="Loose"><files><folder source="Loose" destination="" /></files></plugin>
                </plugins></group>
              </optionalFileGroups></installStep></installSteps>
            </config>
            """.trimIndent(),
        )
        listOf(
            "Required/config.json",
            "Required/settings.ini",
            "Required/readme.txt",
            "Required/SKI_ConfigMenu.psc",
            "SkyrimSE/SKSE/Plugins/MCMHelper.dll",
            "SkyrimSE/SKSE/Plugins/MCMHelper.pdb",
            "SkyrimVR/SKSE/Plugins/MCMHelper.dll",
            "Plugins/MCMHelper.esl",
            "Plugins/MCMHelper.esp",
            "BSA/MCMHelper.bsa",
            "Loose/MCM/Config/SkyUI_SE/loose.json",
        ).forEach { path ->
            File(tempDir, path).apply {
                parentFile?.mkdirs()
                writeText(path)
            }
        }
        val installer = FomodParser.parse(moduleConfig, tempDir)
        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = "mcm",
            installer = installer,
            selectedPluginKeys = setOf("0:0:0", "0:1:0", "0:2:0"),
            extractedRoot = tempDir,
        )
        val expected = setOf(
            "Data/MCM/Config/SkyUI_SE/config.json",
            "Data/MCM/Config/SkyUI_SE/settings.ini",
            "Data/MCM/Settings/readme.txt",
            "Data/Source/Scripts/SKI_ConfigMenu.psc",
            "Data/SKSE/Plugins/MCMHelper.dll",
            "Data/SKSE/Plugins/MCMHelper.pdb",
            "Data/MCMHelper.esl",
            "Data/MCMHelper.bsa",
        )

        assertTrue(result.plan!!.isComplete)
        assertEquals(
            expected,
            result.plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )

        val game = File(tempDir, "game").apply { mkdirs() }
        val install = ModInstall(
            installId = "mcm",
            appId = "game",
            modName = "MCM Helper fixture",
            fileName = "fixture.zip",
            archivePath = "",
            extractedPath = tempDir.absolutePath,
        )
        val applied = ModMaterializer.apply(
            install,
            result.recipes,
            game,
            "",
            File(tempDir, "backups"),
            allowOverwrite = true,
        )
        assertTrue(applied.errors.isEmpty())
        assertEquals(
            expected,
            game.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(game).path.replace(File.separatorChar, '/') }
                .toSet(),
        )
    }

    private fun writeModuleConfig(xml: String): File {
        val file = File(tempDir, "fomod/ModuleConfig.xml")
        file.parentFile?.mkdirs()
        file.writeText(xml)
        return file
    }
}
