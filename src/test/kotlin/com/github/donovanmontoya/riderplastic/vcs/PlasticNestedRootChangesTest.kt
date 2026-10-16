package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticChangeType
import com.github.donovanmontoya.riderplastic.cli.PlasticStatus
import com.github.donovanmontoya.riderplastic.cli.PlasticStatusEntry
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.nio.file.Paths

class PlasticNestedRootChangesTest : BasePlatformTestCase() {

    private val root = Paths.get("/ws/game").toAbsolutePath()
    private val scripts = root.resolve("Assets/Scripts")

    fun `test only items in folders another VCS owns are nested changes`() {
        val status = PlasticStatus(
            12,
            listOf(
                // Outside the Git folder, even where the IDE assigns no VCS (such as ignored files), it is the change provider's.
                entry(PlasticChangeType.CHANGED, root.resolve("Assets/Scene.unity")),
                entry(PlasticChangeType.PRIVATE, root.resolve(".DS_Store")),
                entry(PlasticChangeType.CHANGED, root.resolve("Assets/ScriptsOld/Legacy.cs")),
                entry(PlasticChangeType.CHANGED, scripts.resolve("Player.cs")),
                entry(PlasticChangeType.ADDED, scripts.resolve("Shop/ShopManager.cs")),
                entry(PlasticChangeType.PRIVATE, scripts.resolve("Notes.txt")),
                entry(PlasticChangeType.IGNORED, scripts.resolve("Build.log")),
            ),
        )

        val nested = PlasticNestedRootChanges.of(project, root, status, listOf(scripts, Paths.get("/elsewhere/repo").toAbsolutePath()))

        assertEquals(
            listOf(scripts.resolve("Player.cs"), scripts.resolve("Shop/ShopManager.cs")),
            nested.changes.map { ChangesUtil.getFilePath(it).ioFile.toPath() },
        )
        assertEquals(listOf(scripts.resolve("Notes.txt")), nested.unversioned.map { it.ioFile.toPath() })
    }

    fun `test workspaces without another VCS inside have no nested changes`() {
        val status = PlasticStatus(12, listOf(entry(PlasticChangeType.CHANGED, root.resolve("Assets/Scene.unity"))))
        // A Git repository around the workspace owns nothing in it: the IDE gives the deeper Plastic mapping priority.
        val nested = PlasticNestedRootChanges.of(project, root, status, listOf(Paths.get("/ws").toAbsolutePath()))
        assertSame(PlasticNestedRootChanges.EMPTY, nested)
    }

    fun `test folders inside another nested folder are covered by its status`() {
        val tools = root.resolve("Packages/tools")
        assertEquals(
            listOf(scripts, tools),
            PlasticNestedRootChanges.outermost(listOf(scripts, scripts.resolve("Vendor/lib"), tools)),
        )
    }

    private fun entry(type: PlasticChangeType, path: Path) = PlasticStatusEntry(type, path, null, false, type.name)
}
