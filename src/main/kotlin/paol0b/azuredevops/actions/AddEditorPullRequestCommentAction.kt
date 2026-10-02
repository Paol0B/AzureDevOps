package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.awt.RelativePoint
import git4idea.repo.GitRepositoryManager
import paol0b.azuredevops.services.*
import paol0b.azuredevops.toolwindow.review.InlineCommentEditorComponent
import java.awt.Dimension
import java.awt.Point

/** Start a new PR thread from the checked-out source file, rather than replying to an existing thread. */
class AddEditorPullRequestCommentAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && !editor.isViewer &&
            !editor.isDisposed && file != null && file.isInLocalFileSystem && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)?.takeUnless { it.isDisposed || it.isViewer } ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        fun showError(message: String) {
            if (!project.isDisposed && !editor.isDisposed) Messages.showErrorDialog(project, message, "Unable to Add PR Comment")
        }
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFile(file)
            ?: return showError("This file is not in a Git repository.")
        val branch = repository.currentBranch?.name ?: return showError("Check out the PR source branch first.")
        val tracking = repository.branchTrackInfos.firstOrNull { it.localBranch.name == branch }
        val sourceBranch = tracking?.remoteBranch?.nameForRemoteOperations ?: branch
        val remote = try {
            RegularEditorCommentGuards.repository(repository.remotes.flatMap { remote -> remote.urls.map { remote.name to it } },
                tracking?.remote?.name)
        } catch (error: IllegalArgumentException) { return showError(error.message.orEmpty()) }
        val relativePath = VfsUtilCore.getRelativePath(file, repository.root, '/')
            ?: return showError("Could not find the file path in this repository.")
        if (editor.caretModel.caretCount > 1) return showError("Select one contiguous code range to comment.")
        val text = editor.document.text
        val stamp = editor.document.modificationStamp
        val revision = repository.currentRevision
        val caret = editor.caretModel.offset
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        val api = AzureDevOpsApiClient.getInstance(project)
        fun checkLocalVersion() {
            check(ReadAction.compute<Boolean, RuntimeException> {
                !project.isDisposed && !editor.isDisposed && file.isValid &&
                    editor.document.modificationStamp == stamp && repository.currentBranch?.name == branch &&
                    repository.currentRevision == revision
            }) { "The file or branch changed. Your draft is preserved; reopen Add PR comment against the current code." }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val base = remote.selfHostedUrl ?: "https://dev.azure.com/${remote.organization}"
                PullRequestLookup(1, serverBase = base).requireSameServer(AzureDevOpsConfigService.getInstance(project).getApiBaseUrl())
                val pr = api.findPullRequestForBranch(sourceBranch, remote.project, remote.repository)
                    ?: error("No active pull request was found for branch '$sourceBranch'.")
                check(pr.sourceRefName == "refs/heads/$sourceBranch") { "The PR does not match the checked-out branch." }
                val sourceCommit = pr.lastMergeSourceCommit?.commitId?.takeIf { it.isNotBlank() }
                    ?: error("The PR source version is unavailable. Try refreshing the PR.")
                val repoId = pr.repository?.id ?: error("The PR repository is unavailable.")
                val changes = api.getPullRequestChanges(pr.pullRequestId, remote.project, repoId)
                val path = "/$relativePath"
                val source = api.getFileContent(sourceCommit, path, remote.project, repoId)
                val target = RegularEditorCommentTarget.resolve(text, source, path, changes, caret, start, end)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || editor.isDisposed) return@invokeLater
                    try { checkLocalVersion() } catch (error: Exception) { showError(error.message.orEmpty()); return@invokeLater }
                    var popup: JBPopup? = null
                    val component = InlineCommentEditorComponent(project, api, pr.pullRequestId, path, target.range,
                        remote.project, repoId, target.change.changeTrackingId,
                        onCommentAdded = {
                            popup?.cancel()
                            if (!editor.isDisposed && !project.isDisposed) PullRequestCommentsService.getInstance(project).loadCommentsInEditor(editor, file, pr)
                        },
                        onCancel = { popup?.cancel() },
                        beforeSubmit = {
                            RegularEditorCommentGuards.beforePost(::checkLocalVersion) {
                                val current = api.getPullRequest(pr.pullRequestId, remote.project, repoId)
                                current.status == paol0b.azuredevops.model.PullRequestStatus.Active && current.sourceRefName == pr.sourceRefName &&
                                    current.lastMergeSourceCommit?.commitId == sourceCommit
                            }
                        })
                    component.preferredSize = Dimension(480, component.preferredSize.height.coerceAtLeast(150))
                    val created = component.createPopup()
                    popup = created
                    Disposer.register(project, created)
                    val anchor = editor.logicalPositionToXY(LogicalPosition(target.range.startLine, 0))
                    created.show(RelativePoint(editor.contentComponent, Point(40, anchor.y)))
                }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater { showError(error.message ?: "Unable to load the PR comment target.") }
            }
        }
    }
}
