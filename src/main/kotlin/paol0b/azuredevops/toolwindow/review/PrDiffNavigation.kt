package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContentFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsUrlParser

internal object PrDiffNavigation {
    fun createContent(project: Project, text: String, type: FileType, file: VirtualFile?) =
        if (file != null) DiffContentFactory.getInstance().create(project, text, file)
        else DiffContentFactory.getInstance().create(project, text, type)

    fun matchesCheckout(urls: List<String>, branch: String?, trackedBranch: String?, prUrl: String?, sourceRef: String?): Boolean {
        if (branch == null || sourceRef == null || !sourceRef.startsWith("refs/heads/")) return false
        if ((trackedBranch ?: branch) != sourceRef.removePrefix("refs/heads/")) return false
        val expected = prUrl?.let(AzureDevOpsUrlParser::parse) ?: return false
        return urls.mapNotNull(AzureDevOpsUrlParser::parse).any {
            it.organization.equals(expected.organization, ignoreCase = true) &&
                it.project.equals(expected.project, ignoreCase = true) &&
                it.repository.equals(expected.repository, ignoreCase = true) &&
                it.selfHostedUrl?.trimEnd('/')?.lowercase() == expected.selfHostedUrl?.trimEnd('/')?.lowercase()
        }
    }

    fun findFile(root: VirtualFile, path: String): VirtualFile? {
        val relative = path.removePrefix("/")
        if (relative.isBlank() || '\\' in relative || relative.split('/').any { it == ".." || it == "." }) return null
        return root.findFileByRelativePath(relative)?.takeIf { it.isValid && !it.isDirectory && it.isInLocalFileSystem }
    }

    fun localFile(project: Project, pr: PullRequest?, path: String): VirtualFile? {
        if (pr == null) return null
        val matches = GitRepositoryManager.getInstance(project).repositories.filter { repository ->
            val branch = repository.currentBranch?.name
            val tracking = repository.branchTrackInfos.firstOrNull { it.localBranch.name == branch }
            val remotes = repository.remotes.filter { tracking == null || it.name == tracking.remote.name }
            matchesCheckout(remotes.flatMap { it.urls }, branch, tracking?.remoteBranch?.nameForRemoteOperations,
                pr.repository?.remoteUrl, pr.sourceRefName)
        }
        return matches.singleOrNull()?.root?.let { findFile(it, path) }
    }
}
