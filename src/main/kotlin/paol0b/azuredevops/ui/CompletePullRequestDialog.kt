package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.CompletionOptions
import paol0b.azuredevops.model.MergeStrategy
import paol0b.azuredevops.model.PullRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Modern dialog for completing a Pull Request or setting auto-complete
 * Designed with professional UX/UI best practices
 */
class CompletePullRequestDialog(
    private val project: Project,
    private val pullRequest: PullRequest,
    private val isAutoComplete: Boolean = false,
    private val currentUserId: String? = null
) : DialogWrapper(project) {

    private val mergeStrategyCombo: JComboBox<MergeStrategyItem>
    private val deleteSourceBranchCheckbox: JBCheckBox
    private val mergeCommitMessageArea: JBTextArea
    private val commentArea: JBTextArea
    private val bypassPolicyCheckbox: JBCheckBox
    private val bypassReasonField: JBTextField
    private val transitionWorkItemsCheckbox: JBCheckBox

    init {
        title = if (isAutoComplete) {
            "Set Auto-Complete - PR #${pullRequest.pullRequestId}"
        } else {
            "Complete Pull Request #${pullRequest.pullRequestId}"
        }

        // Initialize merge strategy combo with icons and better labels
        mergeStrategyCombo = JComboBox<MergeStrategyItem>().apply {
            addItem(MergeStrategyItem(MergeStrategy.NO_FAST_FORWARD, "Merge Commit", "Preserve full history"))
            addItem(MergeStrategyItem(MergeStrategy.SQUASH, "Squash Commit", "Single unified commit"))
            addItem(MergeStrategyItem(MergeStrategy.REBASE, "Rebase & Fast-Forward", "Linear history"))
            addItem(MergeStrategyItem(MergeStrategy.REBASE_MERGE, "Rebase & Merge", "Rebase with merge commit"))
            selectedIndex = 0
            renderer = MergeStrategyRenderer()
        }

        val isUserPRCreator = pullRequest.isCreatedByUser(currentUserId)
        
        deleteSourceBranchCheckbox = JBCheckBox("Delete source branch after merge", isUserPRCreator).apply {
            isEnabled = isUserPRCreator
            toolTipText = if (!isUserPRCreator) {
                "Only the PR creator (${pullRequest.createdBy?.displayName ?: "owner"}) can delete the source branch"
            } else {
                "Remove ${pullRequest.getSourceBranchName()} after successful merge"
            }
        }

        mergeCommitMessageArea = JBTextArea(8, 60).apply {
            text = generateDefaultCommitMessage()
            lineWrap = true
            wrapStyleWord = true
        }

        commentArea = JBTextArea(4, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Add an optional comment to explain this merge..."
        }

        bypassPolicyCheckbox = JBCheckBox("Override branch policies (use with caution)", false).apply {
            addActionListener {
                bypassReasonField.isEnabled = isSelected
                bypassReasonField.background = if (isSelected) {
                    JBColor.namedColor("TextField.background")
                } else {
                    UIUtil.getInactiveTextFieldBackgroundColor()
                }
            }
            toolTipText = "Bypass required policies - requires admin permissions"
        }

        bypassReasonField = JBTextField().apply {
            isEnabled = false
            background = UIUtil.getInactiveTextFieldBackgroundColor()
            emptyText.text = "Required: explain why policies are being overridden"
        }

        transitionWorkItemsCheckbox = JBCheckBox("Complete linked work items", true).apply {
            toolTipText = "Automatically transition associated work items to the next state"
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0)
        }

        val scrollPane = JBScrollPane().apply {
            border = JBUI.Borders.empty()
            setViewportView(createFormPanel())
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.preferredSize = Dimension(750, 600)

        return mainPanel
    }

    private fun createFormPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }

        // PR Info Section
        panel.add(createPRInfoSection())
        panel.add(Box.createVerticalStrut(20))

        // Merge Strategy Section
        panel.add(createSectionHeader("Merge Strategy", AllIcons.Vcs.Merge))
        panel.add(Box.createVerticalStrut(8))
        panel.add(createMergeStrategyPanel())
        panel.add(Box.createVerticalStrut(20))

        // Merge Options Section
        panel.add(createSectionHeader("Merge Options", AllIcons.General.Settings))
        panel.add(Box.createVerticalStrut(8))
        panel.add(createOptionsPanel())
        panel.add(Box.createVerticalStrut(20))

        // Commit Message Section
        panel.add(createSectionHeader("Commit Message", AllIcons.Vcs.CommitNode))
        panel.add(Box.createVerticalStrut(8))
        panel.add(createCommitMessagePanel())
        panel.add(Box.createVerticalStrut(20))

        // Comment Section
        panel.add(createSectionHeader("Comment (Optional)", AllIcons.General.Balloon))
        panel.add(Box.createVerticalStrut(8))
        panel.add(createCommentPanel())
        panel.add(Box.createVerticalStrut(20))

        // Advanced Section (collapsed by default)
        if (!isAutoComplete) {
            panel.add(createAdvancedSection())
        }

        return panel
    }

    private fun createPRInfoSection(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            background = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val titleLabel = JBLabel(pullRequest.title).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val branchInfo = JBLabel().apply {
            text = "<html><span style='color: #6A9955;'>${pullRequest.getSourceBranchName()}</span> " +
                    "<span style='color: gray;'>→</span> " +
                    "<span style='color: #4FC1E9;'>${pullRequest.getTargetBranchName()}</span></html>"
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val authorInfo = JBLabel().apply {
            text = "Created by ${pullRequest.createdBy?.displayName ?: "Unknown"}"
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(6))
        panel.add(branchInfo)
        panel.add(Box.createVerticalStrut(4))
        panel.add(authorInfo)

        return panel
    }

    private fun createSectionHeader(title: String, icon: Icon? = null): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }

        icon?.let {
            panel.add(JLabel(it))
            panel.add(Box.createHorizontalStrut(6))
        }

        panel.add(JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 13f)
        })
        
        panel.add(Box.createHorizontalGlue())

        return panel
    }

    private fun createMergeStrategyPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 80)
        }

        mergeStrategyCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        mergeStrategyCombo.maximumSize = Dimension(Int.MAX_VALUE, mergeStrategyCombo.preferredSize.height)

        panel.add(mergeStrategyCombo)

        return panel
    }

    private fun createOptionsPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 100)
        }

        deleteSourceBranchCheckbox.alignmentX = JComponent.LEFT_ALIGNMENT
        deleteSourceBranchCheckbox.maximumSize = Dimension(Int.MAX_VALUE, deleteSourceBranchCheckbox.preferredSize.height)
        panel.add(deleteSourceBranchCheckbox)

        val isUserPRCreator = pullRequest.isCreatedByUser(currentUserId)
        if (!isUserPRCreator) {
            panel.add(Box.createVerticalStrut(4))
            panel.add(JBLabel("<html><small style='color: #BB5544;'>⚠ Only ${pullRequest.createdBy?.displayName ?: "the PR creator"} can delete the source branch</small></html>").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(24)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }

        panel.add(Box.createVerticalStrut(8))
        transitionWorkItemsCheckbox.alignmentX = JComponent.LEFT_ALIGNMENT
        transitionWorkItemsCheckbox.maximumSize = Dimension(Int.MAX_VALUE, transitionWorkItemsCheckbox.preferredSize.height)
        panel.add(transitionWorkItemsCheckbox)

        return panel
    }

    private fun createCommitMessagePanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        val scrollPane = JBScrollPane(mergeCommitMessageArea).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4)
            )
            preferredSize = Dimension(0, 150)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        val charCount = JBLabel().apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 10f)
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        mergeCommitMessageArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateCount()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateCount()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateCount()
            private fun updateCount() {
                val count = mergeCommitMessageArea.text.length
                charCount.text = "$count characters"
            }
        })
        charCount.text = "${mergeCommitMessageArea.text.length} characters"

        panel.add(charCount, BorderLayout.SOUTH)

        return panel
    }

    private fun createCommentPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 120)
        }

        val scrollPane = JBScrollPane(commentArea).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4)
            )
            preferredSize = Dimension(0, 80)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun createAdvancedSection(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        val expandablePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.namedColor("Component.borderColor", JBColor.border()), 1),
                JBUI.Borders.empty(12)
            )
            background = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 180)
        }

        val header = createSectionHeader("Advanced Options", AllIcons.General.Warning)
        header.alignmentX = JComponent.LEFT_ALIGNMENT
        expandablePanel.add(header)
        expandablePanel.add(Box.createVerticalStrut(12))

        bypassPolicyCheckbox.alignmentX = JComponent.LEFT_ALIGNMENT
        bypassPolicyCheckbox.maximumSize = Dimension(Int.MAX_VALUE, bypassPolicyCheckbox.preferredSize.height)
        expandablePanel.add(bypassPolicyCheckbox)
        expandablePanel.add(Box.createVerticalStrut(8))

        bypassReasonField.alignmentX = JComponent.LEFT_ALIGNMENT
        bypassReasonField.maximumSize = Dimension(Int.MAX_VALUE, bypassReasonField.preferredSize.height)
        expandablePanel.add(bypassReasonField)

        panel.add(expandablePanel)

        return panel
    }

    private fun generateDefaultCommitMessage(): String {
        val title = pullRequest.title
        val description = pullRequest.description ?: ""
        
        return if (description.isNotBlank()) {
            "$title\n\n$description"
        } else {
            title
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (mergeCommitMessageArea.text.isNullOrBlank()) {
            return ValidationInfo("Merge commit message is required", mergeCommitMessageArea)
        }

        if (bypassPolicyCheckbox.isSelected && bypassReasonField.text.isNullOrBlank()) {
            return ValidationInfo("Bypass reason is required when overriding policies", bypassReasonField)
        }

        return null
    }

    fun getCompletionOptions(): CompletionOptions {
        val selectedStrategy = (mergeStrategyCombo.selectedItem as MergeStrategyItem).strategy
        
        return CompletionOptions(
            mergeStrategy = selectedStrategy.toApiValue(),
            deleteSourceBranch = deleteSourceBranchCheckbox.isSelected,
            mergeCommitMessage = mergeCommitMessageArea.text.trim().takeIf { it.isNotBlank() },
            bypassPolicy = bypassPolicyCheckbox.isSelected,
            bypassReason = bypassReasonField.text.trim().takeIf { it.isNotBlank() },
            transitionWorkItems = transitionWorkItemsCheckbox.isSelected
        )
    }

    fun getComment(): String? {
        return commentArea.text.trim().takeIf { it.isNotBlank() }
    }

    /**
     * Data class for merge strategy items with labels and descriptions
     */
    private data class MergeStrategyItem(
        val strategy: MergeStrategy,
        val label: String,
        val description: String
    ) {
        override fun toString(): String = label
    }

    /**
     * Custom renderer for merge strategy combo box with descriptions
     */
    private class MergeStrategyRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (component is JLabel && value is MergeStrategyItem) {
                if (index >= 0) {
                    // In dropdown list - show label and description
                    component.text = "<html><b>${value.label}</b><br><small style='color: gray;'>${value.description}</small></html>"
                    component.border = JBUI.Borders.empty(6, 8)
                } else {
                    // In closed combo box - show only label
                    component.text = value.label
                }
            }
            
            return component
        }
    }
}
