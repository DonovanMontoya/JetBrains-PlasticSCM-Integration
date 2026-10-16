package com.github.donovanmontoya.riderplastic.vcs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class PlasticMetadataWatcherTest {

    private val root = Paths.get("/ws/game")
    private val nestedRoot = Paths.get("/ws/game/Packages/tools")
    private val dirs = mapOf("/ws/game/.plastic" to root, "/ws/game/Packages/tools/.plastic" to nestedRoot)

    @Test
    fun `metadata changes refresh their workspace and flag switches`() {
        val changed = PlasticMetadataWatcher.changedWorkspaces(
            listOf(
                "/ws/game/.plastic/plastic.wktree",
                "/ws/game/.plastic/plastic.selector",
                "/ws/game/Packages/tools/.plastic/plastic.changes",
            ),
            dirs,
        )
        assertEquals(mapOf(root to true, nestedRoot to false), changed)
    }

    @Test
    fun `lock files, the Plastic GUI layout and workspace files are not metadata changes`() {
        val changed = PlasticMetadataWatcher.changedWorkspaces(
            listOf(
                // `cm status` itself creates this lock briefly; reacting to it would refresh endlessly.
                "/ws/game/.plastic/plastic.trees.lck",
                "/ws/game/.plastic/plastic.workspaceui",
                "/ws/game/.plastic/changelists/default.xml",
                "/ws/game/Assets/Player.cs",
                "/ws/game/plastic.wktree",
            ),
            dirs,
        )
        assertEquals(emptyMap<Any, Any>(), changed)
    }
}
