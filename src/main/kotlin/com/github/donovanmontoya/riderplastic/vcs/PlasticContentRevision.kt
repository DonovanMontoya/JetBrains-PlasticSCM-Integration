package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.ContentRevisionCache
import java.nio.file.Path

/**
 * Content of a file at some repository revision, loaded lazily with `cm cat`.
 *
 * @param spec the revision spec to pass to `cm cat`: a workspace path (meaning "the loaded revision")
 *             or something like `revid:123`.
 */
class PlasticContentRevision(
    private val root: Path,
    private val filePath: FilePath,
    private val revision: VcsRevisionNumber,
    private val spec: String,
) : ByteBackedContentRevision {

    @Volatile
    private var bytes: ByteArray? = null

    override fun getContentAsBytes(): ByteArray {
        bytes?.let { return it }
        return try {
            PlasticCli.cat(root, spec).also { bytes = it }
        } catch (e: VcsException) {
            throw e
        } catch (e: Exception) {
            throw VcsException(e)
        }
    }

    override fun getContent(): String? = ContentRevisionCache.getAsString(contentAsBytes, filePath, null)

    override fun getFile(): FilePath = filePath

    override fun getRevisionNumber(): VcsRevisionNumber = revision

    override fun toString(): String = "PlasticContentRevision($filePath@$revision)"
}
