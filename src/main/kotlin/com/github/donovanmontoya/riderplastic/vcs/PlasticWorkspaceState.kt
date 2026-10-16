package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.PlasticStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PlasticWorkspaceInfo(val selector: String?, val changeset: Long?)

fun interface PlasticWorkspaceListener {
    fun workspaceChanged(root: Path, info: PlasticWorkspaceInfo)

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(PlasticWorkspaceListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Caches the branch and changeset each Plastic workspace root is on, and its changes in folders another VCS owns.
 * Refreshed from the change provider so it stays in sync with the Commit tool window.
 */
@Service(Service.Level.PROJECT)
class PlasticWorkspaceState(private val project: Project) : Disposable {

    private val infos = ConcurrentHashMap<Path, PlasticWorkspaceInfo>()
    private val nestedByRoot = ConcurrentHashMap<Path, PlasticNestedRootChanges>()
    private val statusReads = AtomicLong()

    fun roots(): List<Path> =
        ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(PlasticVcs.getInstance(project)).map { it.toNioPath() }

    fun info(root: Path): PlasticWorkspaceInfo? = infos[root]

    /** The workspace that matters most for UI purposes (the first root). */
    fun primary(): Pair<Path, PlasticWorkspaceInfo?>? = roots().firstOrNull()?.let { it to infos[it] }

    /** Blocking: re-reads the selector via `cm workspaceinfo`. */
    fun refreshSelector(root: Path, changeset: Long? = infos[root]?.changeset) {
        val selector = try {
            PlasticCli.currentSelector(root)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Unable to read workspace info for $root", e)
            infos[root]?.selector
        }
        update(root, PlasticWorkspaceInfo(selector, changeset))
    }

    /** Records a `cm status` of the whole workspace at [root], as read by the change provider. */
    fun statusRead(root: Path, status: PlasticStatus) {
        updateChangeset(root, status.changeset)
        nestedByRoot[root] = PlasticNestedRootChanges.of(project, root, status)
        statusReads.incrementAndGet()
    }

    /**
     * How many times [statusRead] was called. A change list update that leaves this unchanged involved only other
     * VCSs, so [nestedChanges] may be out of date and needs [PlasticNestedRootChanges.collect].
     */
    val statusReadCount: Long get() = statusReads.get()

    /** Plastic changes in folders the IDE assigns to another VCS, across all workspaces. */
    fun nestedChanges(): PlasticNestedRootChanges = PlasticNestedRootChanges.merge(roots().mapNotNull { nestedByRoot[it] })

    /** Stores the result of [PlasticNestedRootChanges.collect]; returns whether anything changed. */
    fun updateNestedChanges(changes: Map<Path, PlasticNestedRootChanges>): Boolean {
        var changed = false
        for ((root, value) in changes) {
            if (nestedByRoot.put(root, value) != value) changed = true
        }
        return changed
    }

    private fun updateChangeset(root: Path, changeset: Long?) {
        val old = infos[root]
        if (old == null) {
            refreshSelector(root, changeset)
        } else if (old.changeset == null) {
            // The first changeset seen after the startup branch read; the branch is still current.
            update(root, old.copy(changeset = changeset))
        } else if (old.changeset != changeset) {
            // A changed changeset usually means update/switch happened outside the IDE; re-read the branch too.
            refreshSelector(root, changeset)
        }
    }

    private fun update(root: Path, info: PlasticWorkspaceInfo) {
        if (infos.put(root, info) != info && !project.isDisposed) {
            project.messageBus.syncPublisher(PlasticWorkspaceListener.TOPIC).workspaceChanged(root, info)
        }
    }

    override fun dispose() {}

    companion object {
        private val LOG = logger<PlasticWorkspaceState>()

        fun getInstance(project: Project): PlasticWorkspaceState = project.service()
    }
}
