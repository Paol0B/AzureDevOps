package paol0b.azuredevops.model

/** Display names and missing unique names are not identity keys. */
fun PullRequest.findReviewerById(userId: String): Reviewer? {
    require(userId.isNotBlank()) { "Authenticated reviewer ID is missing." }
    return reviewers.orEmpty().firstOrNull { it.id?.equals(userId, ignoreCase = true) == true }
}
