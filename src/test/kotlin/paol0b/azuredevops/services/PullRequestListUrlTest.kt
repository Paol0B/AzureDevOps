package paol0b.azuredevops.services

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class PullRequestListUrlTest {

    @Test
    fun `creator criterion is encoded as Azure DevOps search criteria`() {
        val url = buildPullRequestListUrl(
            endpointUrl = "https://dev.azure.com/acme/_apis/git/pullrequests",
            status = "active",
            pageSize = 200,
            skip = 400,
            criteria = PullRequestQueryCriteria(creatorId = "user id/with spaces")
        ).toHttpUrl()

        assertEquals("active", url.queryParameter("searchCriteria.status"))
        assertEquals("user id/with spaces", url.queryParameter("searchCriteria.creatorId"))
        assertEquals("200", url.queryParameter("\$top"))
        assertEquals("400", url.queryParameter("\$skip"))
        assertEquals("7.0", url.queryParameter("api-version"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `criteria prevents accidental creator AND reviewer query`() {
        PullRequestQueryCriteria(creatorId = "creator", reviewerId = "reviewer")
    }
}
