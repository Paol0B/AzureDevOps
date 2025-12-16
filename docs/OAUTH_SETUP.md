# Azure DevOps OAuth Setup Guide - Microsoft Entra ID

This guide explains how to configure OAuth 2.0 authentication using **Microsoft Entra ID** (recommended by Microsoft) for the Azure DevOps IntelliJ plugin.

> **Note:** Microsoft Entra ID OAuth is the recommended authentication method. Azure DevOps OAuth is deprecated and no longer accepts new registrations as of April 2025.

## Prerequisites

You need to register an OAuth application with Microsoft Entra ID (formerly Azure AD) to obtain:
- **Application (client) ID**
- **Client Secret**

## Step 1: Register Your Application in Azure Portal

1. Go to [Azure Portal - App Registrations](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)

2. Click **"+ New registration"**

3. Fill in the application details:
   - **Name**: `Azure DevOps IntelliJ Plugin`
   - **Supported account types**: Select **"Accounts in any organizational directory (Any Azure AD directory - Multitenant)"**
     > ✅ **Questo è corretto**: Supporta account aziendali/scolastici da qualsiasi organizzazione
     > ⚠️ **Non selezionare** l'opzione con "personal Microsoft accounts" - può causare problemi
   - **Redirect URI**: Select **"Web"** and enter: `http://localhost:8888/callback`

4. Click **"Register"**

5. Save the **Application (client) ID** - you'll need this later

## Step 2: Create a Client Secret

1. In your app registration, go to **"Certificates & secrets"**

2. Click **"+ New client secret"**

3. Add a description (e.g., "IntelliJ Plugin Secret")

4. Select an expiration period (recommended: 24 months)

5. Click **"Add"**

6. **IMPORTANT**: Copy the **Value** immediately - it won't be shown again!

## Step 3: Add API Permissions for Azure DevOps

1. In your app registration, go to **"API permissions"**

2. Click **"+ Add a permission"**

3. Select **"Azure DevOps"** from the list of Microsoft APIs

4. Select **"Delegated permissions"**

5. Check the following permissions:
   - ✅ **user_impersonation** - Access Azure DevOps on behalf of the user
   
   Or select specific scopes:
   - ✅ **vso.code** - Read source code
   - ✅ **vso.code_write** - Read and write source code  
   - ✅ **vso.project** - Read projects and teams

6. Click **"Add permissions"**

7. (Optional) Click **"Grant admin consent for [Your Organization]"** to avoid user consent prompts

## Step 4: Configure the Plugin

Open the file `src/main/kotlin/paol0b/azuredevops/checkout/AzureDevOpsOAuthService.kt` and replace:

```kotlin
private val CLIENT_ID = "YOUR_CLIENT_ID_HERE"
private val CLIENT_SECRET = "YOUR_CLIENT_SECRET_HERE"
```

With your actual credentials from Azure Portal:

```kotlin
private val CLIENT_ID = "12345678-1234-1234-1234-123456789abc"  // Application (client) ID
private val CLIENT_SECRET = "abC8Q~xXxXxXxXxXxXxXxXxXxXxXxXxXxXxX"  // Client secret value
```

## Step 3: Rebuild the Plugin

After updating the credentials, rebuild the plugin:

```bash
./gradlew clean buildPlugin
```

## Step 4: Test OAuth Flow

1. Install the rebuilt plugin in IntelliJ IDEA
2. Go to **File → New → Project from Version Control**
3. Select **Azure DevOps** from the list
4. Click **"+"** to add a new account
5. Enter your organization URL (e.g., `https://dev.azure.com/YourOrg`)
6. Click **"Sign in with Browser (OAuth)"**
7. Your browser will open to authenticate
8. After successful authentication, you'll be redirected back
9. The plugin will automatically fetch your repositories

## OAuth Flow Diagram

```
User Action                    Browser                     Azure DevOps
    |                             |                              |
    | 1. Click "Sign in"         |                              |
    |--------------------------->|                              |
    |                             |                              |
    | 2. Open browser with auth URL                             |
    |----------------------------------------------------------->|
    |                             |                              |
    |                             | 3. User logs in             |
    |                             |----------------------------->|
    |                             |                              |
    |                             | 4. Authorization code       |
    |                             |<-----------------------------|
    |                             |                              |
    | 5. Callback to localhost:8888/callback                    |
    |<-----------------------------------------------------------|
    |                             |                              |
    | 6. Exchange code for token                                |
    |----------------------------------------------------------->|
    |                             |                              |
    | 7. Access token             |                              |
    |<-----------------------------------------------------------|
    |                             |                              |
    | 8. Save credentials globally in IDE                       |
    |                             |                              |
```

## Security Notes

⚠️ **Important Security Considerations:**

1. **Never commit credentials**: Don't commit your `CLIENT_ID` and `CLIENT_SECRET` to version control
2. **Use environment variables**: For production, consider loading credentials from environment variables
3. **Token storage**: Tokens are stored securely using IntelliJ's `PasswordSafe` API
4. **Localhost redirect**: OAuth uses `localhost:8888` which is safe for local development

## Alternative: Personal Access Token

If you prefer not to set up OAuth, users can still authenticate using Personal Access Tokens:

1. In the login dialog, check **"Use Personal Access Token instead"**
2. Enter the organization URL
3. Enter a PAT created at: **Azure DevOps → User Settings → Personal Access Tokens**
4. Click **"Sign In"**

## Troubleshooting

### "The client does not exist or is not enabled for consumers"

**Questo errore indica che stai provando ad usare un account personale Microsoft quando l'app è configurata solo per account aziendali.**

**Soluzione:**
1. **USA UN ACCOUNT AZIENDALE/SCOLASTICO** (work/school account) per Azure DevOps
   - Account validi: nome@azienda.com, nome@scuola.edu
   - Account NON validi per Azure DevOps: outlook.com, hotmail.com, live.com
   
2. Al momento del login, ti apparirà una schermata per **scegliere l'account**
   - Seleziona l'account aziendale/scolastico
   - NON usare account Microsoft personali

3. Se continua a dare errore, verifica la configurazione app:
   - Vai su [Azure Portal - App Registrations](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)
   - Seleziona la tua app → **"Authentication"**
   - **Supported account types** deve essere: **"Accounts in any organizational directory (Multitenant)"**
   - NON deve includere "personal Microsoft accounts"

> 💡 **Importante**: L'endpoint `/organizations` con `prompt=select_account` ti permette di scegliere tra tutti i tuoi account al login!

### OAuth authentication fails
- Verify your **Application (client) ID** and **Client Secret** are correct
- Check that the callback URL is exactly: `http://localhost:8888/callback`
- Ensure port 8888 is not blocked by firewall
- Check IntelliJ logs: **Help → Show Log in Explorer**

### Browser doesn't open
- Try manually opening the authentication URL
- Check if default browser is set correctly
- Verify `BrowserUtil.browse()` has permissions

### Token exchange fails
- Verify your client secret is correct (it's long, like a JWT token)
- Check network connectivity to Azure DevOps
- Review logs for detailed error messages

## References

- [Azure DevOps OAuth Documentation](https://docs.microsoft.com/en-us/azure/devops/integrate/get-started/authentication/oauth)
- [Register an OAuth App](https://app.vsaex.visualstudio.com/app/register)
- [Azure DevOps REST API](https://docs.microsoft.com/en-us/rest/api/azure/devops/)
