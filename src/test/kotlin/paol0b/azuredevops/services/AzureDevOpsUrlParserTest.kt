package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
