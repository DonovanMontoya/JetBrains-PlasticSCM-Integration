package com.github.donovanmontoya.riderplastic.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.PathUtil
import java.nio.file.Path

/**
 * Refreshes Plastic status when a workspace's `.plastic` metadata changes, as the IDE's Git support does for `.git`.
 * Check-ins, updates, switches and undos made outside the IDE change what `cm status` reports without touching the
 * files themselves. Only the files holding workspace state count: `cm status` leaves them alone but briefly creates a
 * lock file next to them, which must not start another refresh.
 */
@Service(Service.Level.PROJECT)
class PlasticMetadataWatcher(private val project: Project) : Disposable {

    /** Each watched `.plastic` folder, by its system-independent path, to its workspace root. */
    @Volatile
    private var metadataDirs = emptyMap<String, Path>()
    private var watchRequests = emptySet<LocalFileSystem.WatchRequest>()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = metadataChanged(events)
            },
        )
    }

    /** Blocking: watches the metadata of the workspaces at [roots] instead of the previous ones. */
    @Synchronized
    fun watch(roots: List<Path>) {
        val dirs = roots.associateBy { FileUtil.toSystemIndependentName(it.resolve(METADATA_DIR).toString()) }
        if (dirs == metadataDirs) return
        val fileSystem = LocalFileSystem.getInstance()
        watchRequests = fileSystem.replaceWatchedRoots(watchRequests, null, dirs.keys)
        // The VFS only reports changes to files it has loaded.
        dirs.keys.forEach { fileSystem.refreshAndFindFileByPath(it)?.children }
        metadataDirs = dirs
    }

    // Runs under the write lock for every file event batch in the IDE, so it stays cheap.
    private fun metadataChanged(events: List<VFileEvent>) {
        val dirs = metadataDirs
        if (dirs.isEmpty()) return
        val changed = changedWorkspaces(events.asSequence().map { it.path }.asIterable(), dirs)
        if (changed.isEmpty()) return
        val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
        changed.keys.forEach { dirtyScopeManager.dirDirtyRecursively(PlasticChangeProvider.filePath(it, true)) }
        val switched = changed.filterValues { it }.keys
        if (switched.isNotEmpty()) {
            val state = PlasticWorkspaceState.getInstance(project)
            ApplicationManager.getApplication().executeOnPooledThread { switched.forEach(state::refreshSelector) }
        }
    }

    @Synchronized
    override fun dispose() {
        LocalFileSystem.getInstance().removeWatchedRoots(watchRequests)
    }

    companion object {
        private const val METADATA_DIR = ".plastic"
        private const val SELECTOR_FILE = "plastic.selector"

        /** The selector, the loaded tree with its checkouts, and pending adds, moves and deletes. */
        private val STATE_FILES = setOf(SELECTOR_FILE, "plastic.wktree", "plastic.changes")

        fun getInstance(project: Project): PlasticMetadataWatcher = project.service()

        /**
         * The workspace roots whose metadata files are among [paths], each with whether its selector changed (a switch
         * to another branch, label or changeset). [metadataDirs] maps `.plastic` folder paths to workspace roots.
         */
        internal fun changedWorkspaces(paths: Iterable<String>, metadataDirs: Map<String, Path>): Map<Path, Boolean> {
            val changed = LinkedHashMap<Path, Boolean>()
            for (path in paths) {
                val name = PathUtil.getFileName(path)
                if (name !in STATE_FILES) continue
                val root = metadataDirs[PathUtil.getParentPath(path)] ?: continue
                changed.merge(root, name == SELECTOR_FILE, Boolean::or)
            }
            return changed
        }
    }
}
