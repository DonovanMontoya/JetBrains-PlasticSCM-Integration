package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.history.PlasticFileRevision
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Throwable2Computable
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.ContentRevisionCache
import java.io.IOException
import java.nio.file.Path

/**
 * Content of [filePath] at [revision], loaded lazily with `cm cat`. A [PlasticRevisionNumber] pins the changeset, so
 * everything that reads a change's "before" side (the diff viewer, gutter markers, AI commit messages) gets exactly the
 * content the local edits started from; otherwise the workspace's loaded revision is read.
 */
class PlasticContentRevision(
    private val project: Project,
    private val root: Path,
    private val filePath: FilePath,
    private val revision: VcsRevisionNumber,
) : ByteBackedContentRevision {

    private val spec = (revision as? PlasticRevisionNumber)?.let { PlasticFileRevision.specAtChangeset(filePath, it.changeset) }
        ?: filePath.path

    @Volatile
    private var bytes: ByteArray? = null

    override fun getContentAsBytes(): ByteArray {
        bytes?.let { return it }
        return try {
            loadCached { PlasticCli.cat(root, spec) }.also { bytes = it }
        } catch (e: VcsException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            throw VcsException(e)
        }
    }

    override fun getContent(): String? = ContentRevisionCache.getAsString(contentAsBytes, filePath, null)

    /** Separate bounded path for AI; ordinary diff/history reads retain their existing behavior. */
    fun getContentAsBytesLimited(maxBytes: Int): ByteArray {
        val content = bytes ?: loadCached { PlasticCli.catLimited(root, spec, maxBytes) }
        require(content.size <= maxBytes) { "A selected base revision exceeds the AI size limit." }
        return content
    }

    /**
     * Content pinned to a changeset never changes, so it goes through the IDE's revision cache: reopening a diff or
     * generating a comment for a file already read skips `cm cat`, which takes about half a second.
     */
    private fun loadCached(load: () -> ByteArray): ByteArray {
        val changeset = revision as? PlasticRevisionNumber ?: return load()
        return ContentRevisionCache.getOrLoadAsBytes(
            project, filePath, changeset, PlasticVcs.KEY, ContentRevisionCache.UniqueType.REPOSITORY_CONTENT,
            Throwable2Computable<ByteArray, VcsException, IOException> { load() },
        )
    }

    override fun getFile(): FilePath = filePath

    override fun getRevisionNumber(): VcsRevisionNumber = revision

    override fun toString(): String = "PlasticContentRevision($filePath@$revision)"
}
