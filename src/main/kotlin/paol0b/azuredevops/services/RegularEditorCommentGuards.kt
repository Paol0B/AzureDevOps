package paol0b.azuredevops.services

object RegularEditorCommentGuards {
    fun repository(remotes: List<Pair<String, String>>, trackedRemote: String?): AzureDevOpsRepoInfo {
        val candidates = remotes.filter { trackedRemote == null || it.first == trackedRemote }
            .mapNotNull { AzureDevOpsUrlParser.parse(it.second) }
            .distinctBy { listOf(it.selfHostedUrl?.lowercase(), it.organization.lowercase(),
                it.project.lowercase(), it.repository.lowercase()) }
        require(candidates.isNotEmpty()) { "The branch does not have an Azure DevOps remote." }
        require(candidates.size == 1) { "This branch has multiple Azure repositories. Set its upstream before commenting." }
        return candidates.single()
    }

    /** Local state can change while the server check is in flight; validate on both sides of it. */
    fun beforePost(checkLocal: () -> Unit, serverVersionMatches: () -> Boolean) {
        checkLocal()
        check(serverVersionMatches()) {
            "The PR source version changed. Your draft is preserved; reopen Add PR comment against the current code."
        }
        checkLocal()
    }
}
