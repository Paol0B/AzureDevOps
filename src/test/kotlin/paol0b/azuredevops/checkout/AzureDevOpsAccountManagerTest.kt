package paol0b.azuredevops.checkout

import com.intellij.testFramework.LightPlatformTestCase

class AzureDevOpsAccountManagerTest : LightPlatformTestCase() {
    private val serverUrl = "https://dev.azure.com/account-manager-test"

    fun testPatLoginReplacesOAuthAccountForSameServer() {
        val manager = AzureDevOpsAccountManager()
        manager.addAccount(serverUrl, "oauth-token", "refresh-token")
        manager.addPatAccount(serverUrl, "pat-token")

        val account = manager.getAccounts().single()
        assertEquals(AuthType.PAT, account.authType)
        assertEquals("pat-token", manager.getToken(account.id))
        assertNull(manager.getRefreshToken(account.id))
    }

    fun testRemoveAccountDeletesBothTokens() {
        val manager = AzureDevOpsAccountManager()
        val account = manager.addAccount(serverUrl, "oauth-token", "refresh-token")

        manager.removeAccount(account.id)

        assertEmpty(manager.getAccounts())
        assertNull(manager.getToken(account.id))
        assertNull(manager.getRefreshToken(account.id))
    }
}
