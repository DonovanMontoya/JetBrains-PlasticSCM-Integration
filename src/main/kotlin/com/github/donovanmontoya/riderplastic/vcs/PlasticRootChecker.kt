package com.github.donovanmontoya.riderplastic.vcs

import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile

/**
 * Lets the IDE auto-detect Plastic workspaces: a workspace root is a directory containing a `.plastic` folder.
 */
class PlasticRootChecker : VcsRootChecker() {

    override fun getSupportedVcs(): VcsKey = PlasticVcs.KEY

    override fun isRoot(file: VirtualFile): Boolean = file.findChild(METADATA_DIR)?.isDirectory == true

    override fun isVcsDir(dirName: String): Boolean = dirName.equals(METADATA_DIR, ignoreCase = true)

    companion object {
        const val METADATA_DIR = ".plastic"

        fun findWorkspaceRoot(file: VirtualFile): VirtualFile? {
            var current: VirtualFile? = if (file.isDirectory) file else file.parent
            while (current != null) {
                if (current.findChild(METADATA_DIR)?.isDirectory == true) return current
                current = current.parent
            }
            return null
        }
    }
}
