package paol0b.azuredevops.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import paol0b.azuredevops.model.GitBranch
import paol0b.azuredevops.services.GitRepositoryService
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Dialog per creare una Pull Request su Azure DevOps
 */
class CreatePullRequestDialog(
    private val project: Project,
    private val gitService: GitRepositoryService
) : DialogWrapper(project) {

    private val sourceBranchCombo: ComboBox<GitBranch>
    private val targetBranchCombo: ComboBox<GitBranch>
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea()

    init {
        title = "Create Azure DevOps Pull Request"
        
        // Ottieni i branch disponibili
        val branches = gitService.getAllBranches()
        val currentBranch = gitService.getCurrentBranch()
        val defaultTarget = gitService.getDefaultTargetBranch()

        if (branches.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "Nessun branch Git trovato nel repository.",
                "Errore"
            )
        }

        // Setup combo boxes
        sourceBranchCombo = ComboBox(branches.toTypedArray())
        targetBranchCombo = ComboBox(branches.toTypedArray())

        // Imposta i valori di default
        currentBranch?.let { current ->
            sourceBranchCombo.selectedItem = branches.firstOrNull { it.displayName == current.displayName }
        }

        defaultTarget?.let { target ->
            targetBranchCombo.selectedItem = branches.firstOrNull { it.displayName == target.displayName }
        }

        // Imposta il renderer per mostrare solo il displayName
        sourceBranchCombo.renderer = BranchListCellRenderer()
        targetBranchCombo.renderer = BranchListCellRenderer()

        // Descrizione area setup
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.rows = 8

        init()
    }

    override fun createCenterPanel(): JComponent {
        val descriptionScroll = JScrollPane(descriptionArea).apply {
            preferredSize = Dimension(500, 150)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Source Branch:"), sourceBranchCombo, 1, false)
            .addTooltip("Il branch da cui partire per la Pull Request")
            .addLabeledComponent(JBLabel("Target Branch:"), targetBranchCombo, 1, false)
            .addTooltip("Il branch di destinazione (solitamente main o master)")
            .addSeparator()
            .addLabeledComponent(JBLabel("Title:"), titleField, 1, false)
            .addTooltip("Titolo della Pull Request (obbligatorio)")
            .addLabeledComponent(JBLabel("Description:"), descriptionScroll, 1, true)
            .addTooltip("Descrizione opzionale della Pull Request")
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo("Il titolo è obbligatorio", titleField)
        }

        val sourceBranch = sourceBranchCombo.selectedItem as? GitBranch
        val targetBranch = targetBranchCombo.selectedItem as? GitBranch

        if (sourceBranch == null) {
            return ValidationInfo("Seleziona un branch di origine", sourceBranchCombo)
        }

        if (targetBranch == null) {
            return ValidationInfo("Seleziona un branch di destinazione", targetBranchCombo)
        }

        if (sourceBranch.name == targetBranch.name) {
            return ValidationInfo("Il branch di origine e destinazione non possono essere uguali", sourceBranchCombo)
        }

        return null
    }

    /**
     * Ottiene il branch di origine selezionato
     */
    fun getSourceBranch(): GitBranch? = sourceBranchCombo.selectedItem as? GitBranch

    /**
     * Ottiene il branch di destinazione selezionato
     */
    fun getTargetBranch(): GitBranch? = targetBranchCombo.selectedItem as? GitBranch

    /**
     * Ottiene il titolo della PR
     */
    fun getPrTitle(): String = titleField.text.trim()

    /**
     * Ottiene la descrizione della PR
     */
    fun getDescription(): String = descriptionArea.text.trim()

    /**
     * Custom renderer per visualizzare solo il displayName dei branch
     */
    private class BranchListCellRenderer : com.intellij.ui.SimpleListCellRenderer<GitBranch>() {
        override fun customize(
            list: javax.swing.JList<out GitBranch>,
            value: GitBranch?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            text = value?.displayName ?: ""
        }
    }
}
