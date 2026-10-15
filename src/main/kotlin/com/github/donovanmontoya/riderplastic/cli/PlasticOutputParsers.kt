package com.github.donovanmontoya.riderplastic.cli

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

data class PlasticHistoryEntry(
    val changeset: Long,
    val branch: String,
    val date: Date?,
    val owner: String,
    val revisionId: String,
    val comment: String,
)

data class PlasticAnnotationLine(
    val changeset: Long,
    val owner: String,
    val date: Date?,
    val branch: String,
    val comment: String,
)

/**
 * Parsers for `cm` outputs that are requested with an explicit `--format`.
 * Field and record separators are chosen so they never collide with real content.
 */
object PlasticOutputParsers {

    const val FIELD = "#@F@#"
    const val RECORD = "#@R@#"

    /** .NET-style date format passed to `--dateformat`, and the matching Java pattern. */
    const val CM_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    private val JAVA_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** `{1}` changeset, `{2}` branch, `{0}` date, `{5}` owner, `{6}` revision id, `{4}` comment. */
    val HISTORY_FORMAT = listOf("{1}", "{2}", "{0}", "{5}", "{6}", "{4}").joinToString(FIELD) + RECORD

    /** One line per source line; content is not requested since the IDE already has it. */
    val ANNOTATE_FORMAT = listOf("{cs}", "{owner}", "{date}", "{branch}", "{comment}").joinToString(FIELD)

    fun parseHistory(output: String): List<PlasticHistoryEntry> =
        output.split(RECORD).mapNotNull { record ->
            val fields = record.trim('\r', '\n').split(FIELD)
            if (fields.size < 6) return@mapNotNull null
            val cs = fields[0].trim().toLongOrNull() ?: return@mapNotNull null
            PlasticHistoryEntry(
                changeset = cs,
                branch = fields[1].trim(),
                date = parseDate(fields[2]),
                owner = fields[3].trim(),
                revisionId = fields[4].trim(),
                // The comment is last so that any accidental separator text in it is kept intact.
                comment = fields.subList(5, fields.size).joinToString(FIELD).trim(),
            )
        }.sortedByDescending { it.changeset }

    fun parseAnnotate(output: String): List<PlasticAnnotationLine> =
        output.lineSequence().map { it.trimEnd('\r') }.filter { it.contains(FIELD) }.map { line ->
            val fields = line.split(FIELD)
            PlasticAnnotationLine(
                changeset = fields.getOrNull(0)?.trim()?.toLongOrNull() ?: -1,
                owner = fields.getOrNull(1)?.trim().orEmpty(),
                date = fields.getOrNull(2)?.let(::parseDate),
                branch = fields.getOrNull(3)?.trim().orEmpty(),
                comment = if (fields.size > 4) fields.subList(4, fields.size).joinToString(FIELD).trim() else "",
            )
        }.toList()

    /**
     * Extracts the branch (or changeset/label) the workspace is on from `cm workspaceinfo` output, e.g.
     * `Branch /main/task42@myrepo@myserver:8087` -> `/main/task42`.
     */
    fun parseWorkspaceSelector(output: String): String? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val spec = line.substringAfter(' ', line).trim()
        val withoutRepo = spec.substringBefore('@').trim()
        if (withoutRepo.isEmpty()) return null
        return when {
            line.startsWith("Branch", ignoreCase = true) || line.startsWith("BR ") -> withoutRepo
            line.startsWith("Changeset", ignoreCase = true) || line.startsWith("CS ") -> "cs:$withoutRepo"
            line.startsWith("Label", ignoreCase = true) || line.startsWith("LB ") -> "lb:$withoutRepo"
            else -> withoutRepo.removePrefix("br:")
        }
    }

    /**
     * Extracts the loaded changeset from the first line of `cm status --header`,
     * e.g. `cs:263@myrepo@myserver (head)` -> 263.
     */
    fun parseHeaderChangeset(output: String): Long? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return Regex("""\bcs:(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** Parses `cm find branch --format={name} --nototal` output. */
    fun parseBranches(output: String): List<String> =
        output.lineSequence().map { it.trim() }.filter { it.startsWith("/") }.distinct().sorted().toList()

    private fun parseDate(text: String): Date? = try {
        Date.from(LocalDateTime.parse(text.trim(), JAVA_DATE_FORMAT).atZone(ZoneId.systemDefault()).toInstant())
    } catch (_: Exception) {
        null
    }
}
