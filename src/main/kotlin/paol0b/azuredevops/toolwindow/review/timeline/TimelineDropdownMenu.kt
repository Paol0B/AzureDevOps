package paol0b.azuredevops.toolwindow.review.timeline

import paol0b.azuredevops.model.ThreadStatus
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator

/**
 * Factory for the overflow (⋮) popup menu on comment cards.
 *
 * Options:
 *  ─ Mark as Resolved / Reopen
 *  ─ Won't Fix
 *  ─ By Design
 *  ─── separator ───
 *  ─ Closed
 */
object TimelineDropdownMenu {

    /**
     * Create the popup menu for a comment thread.
     *
     * @param threadId      The Azure DevOps thread id
     * @param currentStatus The current thread status
     * @param onStatusChange Callback when the user picks a new status
     */
    fun createThreadPopup(
        threadId: Int,
        currentStatus: ThreadStatus,
        onStatusChange: (ThreadStatus) -> Unit
    ): JPopupMenu {
        val popup = JPopupMenu()

        // Toggle resolve / reopen
        if (currentStatus == ThreadStatus.Active || currentStatus == ThreadStatus.Pending) {
            popup.add(menuItem("✓  Mark as Resolved") { onStatusChange(ThreadStatus.Fixed) })
        } else {
            popup.add(menuItem("↺  Reopen") { onStatusChange(ThreadStatus.Active) })
        }

        popup.add(JSeparator())

        // Additional status options
        if (currentStatus != ThreadStatus.WontFix) {
            popup.add(menuItem("Won't Fix") { onStatusChange(ThreadStatus.WontFix) })
        }
        if (currentStatus != ThreadStatus.ByDesign) {
            popup.add(menuItem("By Design") { onStatusChange(ThreadStatus.ByDesign) })
        }
        if (currentStatus != ThreadStatus.Closed) {
            popup.add(menuItem("Close") { onStatusChange(ThreadStatus.Closed) })
        }
        if (currentStatus != ThreadStatus.Pending) {
            popup.add(menuItem("Pending") { onStatusChange(ThreadStatus.Pending) })
        }

        return popup
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem {
        return JMenuItem(text).apply {
            addActionListener { action() }
        }
    }
}
