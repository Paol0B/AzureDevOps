# OAuth Device Code Flow Implementation

## Overview

The Azure DevOps plugin now uses **OAuth 2.0 Device Code Flow** for authentication, similar to Visual Studio 2022. This provides automatic authentication without requiring app registration or manual CLIENT_ID/SECRET configuration.

## How It Works

### Authentication Flow

1. **User clicks "Sign in with Browser"** in the login dialog
2. **Device Code Request**: Plugin requests a device code from Microsoft Entra ID
3. **Code Display**: A dialog shows the user code (e.g., "B2QK-WXYZ")
4. **Browser Opens**: Browser automatically opens to `https://microsoft.com/devicelogin`
5. **User Authentication**: User enters the code and signs in with their Microsoft account
6. **Token Polling**: Plugin polls Microsoft for the access token
7. **Success**: Token is saved globally in IntelliJ IDEA

### Technical Details

- **Authentication Provider**: Microsoft Entra ID (`login.microsoftonline.com/organizations`)
- **Client ID**: `04b07795-8ddb-461a-bbee-02f9e1bf7b46` (Azure CLI public client)
- **Resource**: `499b84ac-1321-427f-aa17-267ca6975798` (Azure DevOps)
- **Scopes**: `{resource}/.default offline_access`
- **Flow**: Device Code Flow (OAuth 2.0)

## Key Components

### 1. AzureDevOpsOAuthService.kt

Core authentication service that handles the Device Code Flow:

- `authenticate(organizationUrl, callback?)`: Initiates authentication
- `requestDeviceCode()`: Requests device code from Microsoft
- `pollForToken(deviceCode, organizationUrl)`: Polls for access token
- `parseTokenResponse(json, organizationUrl)`: Parses token response

### 2. DeviceCodeAuthDialog.kt

Dialog that shows the device code to the user:

- Displays the user code in a large, copyable text field
- Shows instructions for authentication
- Automatically opens the browser
- Provides "Copy Code" and "Open Browser" buttons
- Updates status as authentication progresses

### 3. AzureDevOpsLoginDialog.kt

Main login dialog with OAuth and PAT options:

- OAuth button: Opens DeviceCodeAuthDialog
- PAT mode: Manual token entry (fallback)
- Validates URLs and saves credentials

### 4. AzureDevOpsAccountManager.kt

Global credential storage:

- Stores tokens securely using IntelliJ PasswordSafe
- Application-level service (credentials persist across projects)
- Manages multiple accounts

## Advantages of Device Code Flow

1. **No App Registration Required**: Works automatically for all users
2. **No Client Secrets**: Public client ID is safe to embed
3. **User-Friendly**: Simple browser-based authentication
4. **Automatic**: No manual configuration needed
5. **Secure**: Tokens stored in IntelliJ's secure storage

## Testing

To test the authentication flow:

1. Run the plugin in debug mode
2. Go to **File → New → Project from Version Control → Azure DevOps**
3. Click the **+** button to add an account
4. Enter your organization URL (e.g., `https://dev.azure.com/YourOrg`)
5. Click **Sign in with Browser**
6. Copy the code shown in the dialog
7. Sign in with your Microsoft account in the browser
8. Enter the code when prompted
9. Plugin will automatically receive the token

## Error Handling

The implementation handles:

- **authorization_pending**: User hasn't completed auth yet (continues polling)
- **authorization_declined**: User declined authorization
- **expired_token**: Device code expired (15 minutes timeout)
- **Network errors**: Retries polling, shows error message

## Polling Configuration

- **Interval**: 5 seconds (default from Microsoft)
- **Timeout**: 900 seconds (15 minutes, default from Microsoft)
- **Method**: POST to `/oauth2/v2.0/token` with device code

## API Endpoints

### Device Code Request
```
POST https://login.microsoftonline.com/organizations/oauth2/v2.0/devicecode
Content-Type: application/x-www-form-urlencoded

client_id={CLIENT_ID}&scope={SCOPES}
```

Response:
```json
{
  "device_code": "...",
  "user_code": "B2QK-WXYZ",
  "verification_uri": "https://microsoft.com/devicelogin",
  "expires_in": 900,
  "interval": 5,
  "message": "To sign in, use a web browser to open..."
}
```

### Token Request (Polling)
```
POST https://login.microsoftonline.com/organizations/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

client_id={CLIENT_ID}&grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code={DEVICE_CODE}
```

Response (on success):
```json
{
  "access_token": "eyJ0eXAi...",
  "refresh_token": "0.AXcA...",
  "expires_in": 3600
}
```

Response (pending):
```json
{
  "error": "authorization_pending",
  "error_description": "..."
}
```

## References

- [Microsoft Identity Platform Device Code Flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)
- [Azure DevOps OAuth Documentation](https://learn.microsoft.com/en-us/azure/devops/integrate/get-started/authentication/oauth)
- [Azure CLI Client ID](https://github.com/Azure/azure-cli)
