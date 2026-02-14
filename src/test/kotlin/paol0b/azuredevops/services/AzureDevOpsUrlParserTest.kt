package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AzureDevOpsUrlParserTest {

    @Test
    fun `test parsing dev azure com url`() {
        val url = "https://dev.azure.com/myorg/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)
        
        assertNotNull("Should parse dev.azure.com URL", info)
        assertEquals("myorg", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertFalse("Should not use visualstudio domain", info?.useVisualStudioDomain ?: true)
        assertNull("Should not be self-hosted", info?.selfHostedUrl)
    }

    @Test
    fun `test parsing visualstudio com url`() {
        // This is the "real case" the user wants to test compatibility for
        val url = "https://myorg.visualstudio.com/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)
        
        assertNotNull("Should parse visualstudio.com URL", info)
        assertEquals("myorg", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertTrue("Should use visualstudio domain", info?.useVisualStudioDomain ?: false)
        assertNull("Should not be self-hosted", info?.selfHostedUrl)
    }

    @Test
    fun `test parsing legacy ssh url`() {
        val url = "myorg@vs-ssh.visualstudio.com:v3/myorg/MyProject/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)
        
        assertNotNull("Should parse legacy SSH URL", info)
        assertEquals("myorg", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertTrue("Should use visualstudio domain", info?.useVisualStudioDomain ?: false)
    }

    @Test
    fun `test parsing ssh v3 url`() {
        val url = "git@ssh.dev.azure.com:v3/myorg/MyProject/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)
        
        assertNotNull("Should parse SSH v3 URL", info)
        assertEquals("myorg", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertFalse("Should not use visualstudio domain", info?.useVisualStudioDomain ?: true)
    }
    
    @Test
    fun `test encoded characters`() {
        val url = "https://dev.azure.com/myorg/My%20Project/_git/My%20Repo"
        val info = AzureDevOpsUrlParser.parse(url)
        
        assertNotNull("Should parse encoded URL", info)
        assertEquals("myorg", info?.organization)
        assertEquals("My Project", info?.project)
        assertEquals("My Repo", info?.repository)
    }

    // --- Self-hosted Azure DevOps Server tests ---

    @Test
    fun `test parsing self-hosted url with collection`() {
        val url = "https://tfs.company.com/tfs/DefaultCollection/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)

        assertNotNull("Should parse self-hosted URL with collection", info)
        assertEquals("DefaultCollection", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertNotNull("Should have self-hosted URL", info?.selfHostedUrl)
        assertEquals("https://tfs.company.com/tfs/DefaultCollection", info?.selfHostedUrl)
        assertTrue("Should report as self-hosted", info?.isSelfHosted() ?: false)
    }

    @Test
    fun `test parsing self-hosted url without collection path`() {
        val url = "https://azuredevops.internal.net/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)

        assertNotNull("Should parse self-hosted URL without collection path", info)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertNotNull("Should have self-hosted URL", info?.selfHostedUrl)
        assertTrue("Should report as self-hosted", info?.isSelfHosted() ?: false)
    }

    @Test
    fun `test parsing self-hosted url with port`() {
        val url = "https://devops.mycompany.com:8080/tfs/DefaultCollection/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)

        assertNotNull("Should parse self-hosted URL with port", info)
        assertEquals("DefaultCollection", info?.organization)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertNotNull("Should have self-hosted URL", info?.selfHostedUrl)
        assertTrue("Self-hosted URL should contain port", info?.selfHostedUrl?.contains(":8080") ?: false)
    }

    @Test
    fun `test parsing self-hosted url with git suffix`() {
        val url = "https://tfs.company.com/tfs/DefaultCollection/MyProject/_git/MyRepo.git"
        val info = AzureDevOpsUrlParser.parse(url)

        assertNotNull("Should parse self-hosted URL with .git suffix", info)
        assertEquals("MyRepo", info?.repository)
        assertTrue("Should report as self-hosted", info?.isSelfHosted() ?: false)
    }

    @Test
    fun `test parsing http self-hosted url`() {
        val url = "http://tfs.internal:8080/tfs/DefaultCollection/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(url)

        assertNotNull("Should parse HTTP self-hosted URL", info)
        assertEquals("MyProject", info?.project)
        assertEquals("MyRepo", info?.repository)
        assertNotNull("Should have self-hosted URL", info?.selfHostedUrl)
        assertTrue("Self-hosted URL should use http", info?.selfHostedUrl?.startsWith("http://") ?: false)
    }

    @Test
    fun `test cloud urls are not detected as self-hosted`() {
        val cloudUrl = "https://dev.azure.com/myorg/MyProject/_git/MyRepo"
        val info = AzureDevOpsUrlParser.parse(cloudUrl)
        assertNotNull(info)
        assertNull("Cloud URL should not be self-hosted", info?.selfHostedUrl)
        assertFalse("Cloud URL should not report as self-hosted", info?.isSelfHosted() ?: true)
    }
}
