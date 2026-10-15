package com.github.donovanmontoya.riderplastic.cli

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Kind of a pending change as reported by `cm status --machinereadable`.
 */
enum class PlasticChangeType {
    ADDED,
    CHANGED,
    DELETED,
    MOVED,
    PRIVATE,
    IGNORED,
}

/**
 * One line of `cm status` output.
 *
 * [path] is the current location of the item; [oldPath] is only set for moves.
 */
data class PlasticStatusEntry(
    val type: PlasticChangeType,
    val path: Path,
    val oldPath: Path?,
    val isDirectory: Boolean,
    val code: String,
)

data class PlasticStatus(
    /** Changeset currently loaded in the workspace, if the header could be parsed. */
    val changeset: Long?,
    val entries: List<PlasticStatusEntry>,
)

/**
 * Parses the output of
 * `cm status --all --machinereadable --fieldseparator=<FIELD_SEPARATOR>`.
 *
 * Example lines (with `|` as separator for readability):
 * ```
 * STATUS|12|myrepo|localhost:8087
 * CH|C:\ws\src\Foo.cs|False|NO_MERGES
 * MV|C:\ws\old.cs|C:\ws\new.cs|False|NO_MERGES
 * PR|C:\ws\notes.txt|False|NO_MERGES
 * ```
 */
object PlasticStatusParser {

    /** Unlikely to ever appear in a file path on any OS. */
    const val FIELD_SEPARATOR = "#~#"

    private val TYPE_MAP = mapOf(
        "AD" to PlasticChangeType.ADDED,
        "CP" to PlasticChangeType.ADDED, // copied
        "CH" to PlasticChangeType.CHANGED,
        "CO" to PlasticChangeType.CHANGED, // checked out
        "RP" to PlasticChangeType.CHANGED, // replaced (merge)
        "HD" to PlasticChangeType.CHANGED, // hidden changed
        "DE" to PlasticChangeType.DELETED,
        "LD" to PlasticChangeType.DELETED, // locally deleted
        "MV" to PlasticChangeType.MOVED,
        "LM" to PlasticChangeType.MOVED, // locally moved
        "PR" to PlasticChangeType.PRIVATE,
        "IG" to PlasticChangeType.IGNORED,
    )

    fun parse(output: String, workspaceRoot: Path): PlasticStatus {
        var changeset: Long? = null
        // A path may be reported several times (e.g. checked out *and* moved); keep the most specific entry.
        val entries = LinkedHashMap<Path, PlasticStatusEntry>()

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val fields = line.split(FIELD_SEPARATOR).map { it.trim() }

            if (fields[0] == "STATUS") {
                changeset = fields.getOrNull(1)?.toLongOrNull()
                continue
            }

            val type = TYPE_MAP[fields[0]] ?: continue
            if (fields.size < 2) continue

            // Trailing fields are <isDirectory> and <merge info>; everything between type and those are paths.
            var end = fields.size
            if (end > 2 && fields[end - 1].isMergeInfo()) end--
            var isDirectory = false
            if (end > 2 && fields[end - 1].isBoolean()) {
                isDirectory = fields[end - 1].equals("true", ignoreCase = true)
                end--
            }
            val paths = fields.subList(1, end).filter { it.isNotEmpty() }
            if (paths.isEmpty()) continue

            val entry = if (type == PlasticChangeType.MOVED && paths.size >= 2) {
                PlasticStatusEntry(type, resolve(workspaceRoot, paths[1]), resolve(workspaceRoot, paths[0]), isDirectory, fields[0])
            } else {
                PlasticStatusEntry(type, resolve(workspaceRoot, paths[0]), null, isDirectory, fields[0])
            }

            val existing = entries[entry.path]
            if (existing == null || priority(entry.type) > priority(existing.type)) {
                entries[entry.path] = entry
            }
        }

        return PlasticStatus(changeset, entries.values.toList())
    }

    private fun priority(type: PlasticChangeType) = when (type) {
        PlasticChangeType.IGNORED -> 0
        PlasticChangeType.PRIVATE -> 1
        PlasticChangeType.CHANGED -> 2
        PlasticChangeType.DELETED -> 3
        PlasticChangeType.ADDED -> 4
        PlasticChangeType.MOVED -> 5
    }

    private fun String.isBoolean() = equals("true", ignoreCase = true) || equals("false", ignoreCase = true)

    private fun String.isMergeInfo() = this == "NO_MERGES" || startsWith("MERGE") || startsWith("REPLACED") ||
        startsWith("CHERRY") || startsWith("SUBTRACT") || contains("_MERGE") || contains("_FROM")

    private fun resolve(root: Path, path: String): Path {
        val p = Paths.get(path)
        return (if (p.isAbsolute) p else root.resolve(p)).normalize()
    }
}
