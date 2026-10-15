package com.github.donovanmontoya.riderplastic.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class PlasticParsersTest {

    private val root = Paths.get(System.getProperty("java.io.tmpdir"), "ws").toAbsolutePath()
    private val sep = PlasticStatusParser.FIELD_SEPARATOR

    private fun p(vararg parts: String) = root.resolve(Paths.get("", *parts)).toString()

    @Test
    fun `status maps every change type`() {
        val output = listOf(
            "STATUS${sep}42${sep}myrepo${sep}localhost:8087",
            "CH$sep${p("src", "Changed.cs")}${sep}False${sep}NO_MERGES",
            "AD$sep${p("src", "Added.cs")}${sep}False${sep}NO_MERGES",
            "LD$sep${p("src", "Gone.cs")}${sep}False${sep}NO_MERGES",
            "MV$sep${p("Old.cs")}$sep${p("New.cs")}${sep}False${sep}NO_MERGES",
            "PR$sep${p("notes.txt")}${sep}False${sep}NO_MERGES",
            "IG$sep${p("Library")}${sep}True${sep}NO_MERGES",
        ).joinToString("\r\n")

        val status = PlasticStatusParser.parse(output, root)

        assertEquals(42L, status.changeset)
        val byType = status.entries.associateBy { it.type }
        assertEquals(p("src", "Changed.cs"), byType.getValue(PlasticChangeType.CHANGED).path.toString())
        assertEquals(p("src", "Added.cs"), byType.getValue(PlasticChangeType.ADDED).path.toString())
        assertEquals(p("src", "Gone.cs"), byType.getValue(PlasticChangeType.DELETED).path.toString())
        val moved = byType.getValue(PlasticChangeType.MOVED)
        assertEquals(p("New.cs"), moved.path.toString())
        assertEquals(p("Old.cs"), moved.oldPath.toString())
        assertEquals(p("notes.txt"), byType.getValue(PlasticChangeType.PRIVATE).path.toString())
        assertEquals(true, byType.getValue(PlasticChangeType.IGNORED).isDirectory)
    }

    @Test
    fun `status resolves relative paths and prefers the most specific entry`() {
        val output = "CO${sep}a.cs${sep}False${sep}NO_MERGES\nMV${sep}b.cs${sep}a.cs${sep}False${sep}NO_MERGES"
        val status = PlasticStatusParser.parse(output, root)
        assertNull(status.changeset)
        assertEquals(1, status.entries.size)
        assertEquals(PlasticChangeType.MOVED, status.entries[0].type)
        assertEquals(root.resolve("a.cs"), status.entries[0].path)
    }

    @Test
    fun `history keeps multi-line comments and sorts newest first`() {
        val f = PlasticOutputParsers.FIELD
        val r = PlasticOutputParsers.RECORD
        val output = "3${f}/main${f}2026-01-02 10:00:00${f}alice${f}101${f}first line\nsecond line$r\n" +
            "7${f}/main/task1${f}2026-02-03 11:30:00${f}bob${f}155${f}fix$r\n"

        val history = PlasticOutputParsers.parseHistory(output)

        assertEquals(listOf(7L, 3L), history.map { it.changeset })
        assertEquals("/main/task1", history[0].branch)
        assertEquals("bob", history[0].owner)
        assertEquals("155", history[0].revisionId)
        assertEquals("first line\nsecond line", history[1].comment)
    }

    @Test
    fun `annotate yields one entry per line`() {
        val f = PlasticOutputParsers.FIELD
        val output = "12${f}alice${f}2026-01-02 10:00:00${f}/main${f}initial\n13${f}bob${f}bad date${f}/main/x${f}"
        val lines = PlasticOutputParsers.parseAnnotate(output)
        assertEquals(2, lines.size)
        assertEquals(12L, lines[0].changeset)
        assertEquals("bob", lines[1].owner)
        assertNull(lines[1].date)
    }

    @Test
    fun `workspace selector is extracted from workspaceinfo`() {
        assertEquals("/main/task42", PlasticOutputParsers.parseWorkspaceSelector("Branch /main/task42@myrepo@local\n"))
        assertEquals("cs:15", PlasticOutputParsers.parseWorkspaceSelector("Changeset 15@myrepo@cloud"))
        assertEquals("lb:v1.0", PlasticOutputParsers.parseWorkspaceSelector("Label v1.0@myrepo@cloud"))
        assertNull(PlasticOutputParsers.parseWorkspaceSelector(""))
    }

    @Test
    fun `loaded changeset is read from status header`() {
        assertEquals(263L, PlasticOutputParsers.parseHeaderChangeset("cs:263@MyRepo@cloud (head)\nCH foo.cs"))
        assertEquals(12L, PlasticOutputParsers.parseHeaderChangeset("\r\ncs:12@rep@localhost:8087\r\n"))
        assertNull(PlasticOutputParsers.parseHeaderChangeset("Branch /main@rep@server"))
    }

    @Test
    fun `branches are filtered and sorted`() {
        assertEquals(
            listOf("/main", "/main/task1"),
            PlasticOutputParsers.parseBranches("/main/task1\r\n/main\n\nTotal: 2\n"),
        )
    }
}
