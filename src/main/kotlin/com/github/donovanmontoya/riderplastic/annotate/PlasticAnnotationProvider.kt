package com.github.donovanmontoya.riderplastic.annotate

import com.github.donovanmontoya.riderplastic.cli.PlasticAnnotationLine
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.vcs.PlasticRevisionNumber
import com.github.donovanmontoya.riderplastic.vcs.PlasticVcs
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.annotate.LineAnnotationAspectAdapter
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.DateFormatUtil
import com.intellij.vcsUtil.VcsUtil
import java.util.Date

/** "Annotate with Plastic SCM Blame", backed by `cm annotate`. */
class PlasticAnnotationProvider(private val project: Project) : AnnotationProvider {

    override fun annotate(file: VirtualFile): FileAnnotation = annotate(file, null)

    override fun annotate(file: VirtualFile, revision: VcsFileRevision?): FileAnnotation {
        val filePath = VcsUtil.getFilePath(file)
        val root = PlasticVcs.rootFor(project, filePath) ?: throw VcsException("${file.path} is not in a Plastic SCM workspace")
        val lines = PlasticCli.annotate(root, file.toNioPath())
        val current = revision?.revisionNumber
            ?: PlasticWorkspaceState.getInstance(project).info(root)?.changeset?.let(::PlasticRevisionNumber)
        return PlasticFileAnnotation(project, file, lines, current)
    }
}

class PlasticFileAnnotation(
    project: Project,
    private val file: VirtualFile,
    private val lines: List<PlasticAnnotationLine>,
    private val currentRevision: VcsRevisionNumber?,
) : FileAnnotation(project) {

    private val aspects: Array<LineAnnotationAspect> = arrayOf(
        aspect(LineAnnotationAspect.REVISION, "Changeset") { it.changeset.toString() },
        aspect(LineAnnotationAspect.DATE, "Date") { line -> line.date?.let { DateFormatUtil.formatPrettyDate(it) }.orEmpty() },
        aspect(LineAnnotationAspect.AUTHOR, "Author") { it.owner },
        aspect("Branch", "Branch", showByDefault = false) { it.branch },
    )

    private fun aspect(
        id: String,
        name: String,
        showByDefault: Boolean = true,
        value: (PlasticAnnotationLine) -> String,
    ): LineAnnotationAspect = object : LineAnnotationAspectAdapter(id, name, showByDefault) {
        override fun getValue(line: Int): String = lines.getOrNull(line)?.let(value).orEmpty()

        override fun showAffectedPaths(lineNum: Int) {}
    }

    override fun getAspects(): Array<LineAnnotationAspect> = aspects

    override fun getLineCount(): Int = lines.size

    override fun getToolTip(lineNumber: Int): String? {
        val line = lines.getOrNull(lineNumber) ?: return null
        val date = line.date?.let { DateFormatUtil.formatDateTime(it) }.orEmpty()
        return "Changeset ${line.changeset} on ${line.branch}\n${line.owner}  $date\n\n${StringUtil.escapeXmlEntities(line.comment)}"
    }

    override fun getAnnotatedContent(): String? = null

    override fun getLineRevisionNumber(lineNumber: Int): VcsRevisionNumber? =
        lines.getOrNull(lineNumber)?.changeset?.takeIf { it >= 0 }?.let(::PlasticRevisionNumber)

    override fun getLineDate(lineNumber: Int): Date? = lines.getOrNull(lineNumber)?.date

    override fun getCurrentRevision(): VcsRevisionNumber? = currentRevision

    override fun getRevisions(): List<VcsFileRevision>? = null

    override fun getVcsKey(): VcsKey = PlasticVcs.KEY

    override fun getFile(): VirtualFile = file
}
