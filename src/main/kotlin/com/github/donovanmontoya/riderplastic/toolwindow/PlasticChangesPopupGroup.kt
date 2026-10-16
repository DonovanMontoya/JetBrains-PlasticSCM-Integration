package com.github.donovanmontoya.riderplastic.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/**
 * The IDE's Local Changes context menu, including the actions other plugins add to it, for the Plastic SCM tree.
 *
 * Actions in [replacements] (by action ID) stand in for the IDE's own, for example so check-in and undo always go to
 * Plastic. IDE actions that work through the file's owning VCS or the IDE changelists are disabled for items another
 * VCS owns (such as a nested Git repository), since they would act through that VCS instead of Plastic.
 */
class PlasticChangesPopupGroup(private val replacements: Map<String, AnAction>) : ActionGroup(), DumbAware {

    private val guardedActions = ConcurrentHashMap<String, AnAction>()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val actionManager = ActionManager.getInstance()
        val platformMenu = actionManager.getAction(CHANGES_VIEW_POPUP_MENU) as? DefaultActionGroup
            ?: return replacements.values.toTypedArray()
        val children = platformMenu.getChildren(actionManager)
        val ids = children.map { actionManager.getId(it) }
        val missing = replacements.filterKeys { it !in ids }.values
        return (missing + children.mapIndexed { index, action ->
            val id = ids[index]
            replacements[id] ?: if (id in OWNING_VCS_ACTIONS) guardedActions.getOrPut(id) { PlasticOwnedOnly(action) } else action
        }).toTypedArray()
    }

    internal class PlasticOwnedOnly(action: AnAction) : AnActionWrapper(action) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            if (e.otherVcsSelected) e.presentation.isEnabled = false
        }
    }

    /** Takes the guarded actions' shortcuts, doing nothing, while items another VCS owns are selected. */
    private class OtherVcsShortcutBlocker : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        // When disabled, the shortcut goes on to the IDE's action.
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.otherVcsSelected
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    companion object {
        private val AnActionEvent.otherVcsSelected: Boolean get() = getData(PlasticChangesTree.OTHER_VCS_SELECTED) == true

        private const val CHANGES_VIEW_POPUP_MENU = "ChangesViewPopupMenu"
        const val CHECKIN_FILES = "CheckinFiles"
        const val ROLLBACK = IdeActions.CHANGES_VIEW_ROLLBACK

        private val OWNING_VCS_ACTIONS = setOf(
            "ChangesView.AddUnversioned", "ChangesView.RemoveDeleted", "ChangesView.Edit", IdeActions.MOVE_TO_ANOTHER_CHANGE_LIST,
            "ChangesView.NewChangeList", "ChangesView.RemoveChangeList", "ChangesView.SetDefault", "ChangesView.Rename",
            "ChangesView.Shelve",
        )

        /** Not in the menu, but its shortcut reaches the tree. */
        private const val SHELVE_SILENTLY = "ChangesView.ShelveSilently"

        /**
         * Keeps the shortcuts of the actions this menu disables for items another VCS owns from reaching those actions
         * while [component] has focus.
         */
        fun guardShortcuts(component: JComponent, parentDisposable: Disposable) {
            val shortcuts = (OWNING_VCS_ACTIONS + SHELVE_SILENTLY).flatMap { KeymapUtil.getActiveKeymapShortcuts(it).shortcuts.asList() }
            OtherVcsShortcutBlocker().registerCustomShortcutSet(CustomShortcutSet(*shortcuts.toTypedArray()), component, parentDisposable)
        }
    }
}
