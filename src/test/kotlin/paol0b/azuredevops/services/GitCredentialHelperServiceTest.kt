package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Test

class GitCredentialHelperServiceTest {

    @Test
    fun `credential fill never prompts for a login`() {
        val environment = GitCredentialHelperService.credentialFillProcess().environment()

        assertEquals("0", environment["GIT_TERMINAL_PROMPT"])
        assertEquals("never", environment["GCM_INTERACTIVE"])
    }
}
