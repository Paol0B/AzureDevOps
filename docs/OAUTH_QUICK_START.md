# 🚀 Quick Start Guide - Microsoft Entra ID OAuth

## ⚡ 5-Minute OAuth Configuration with Microsoft Entra ID

### 1️⃣ Register App in Azure Portal (2 minutes)

Visit: https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps/ApplicationsListBlade

Click **"+ New registration"** and fill in:
- **Name**: `Azure DevOps IntelliJ Plugin`
- **Supported account types**: `Accounts in any organizational directory (Multitenant)`
- **Redirect URI**: `Web` → `http://localhost:8888/callback`

Click **"Register"** and save the **Application (client) ID**

### 2️⃣ Create Client Secret (1 minute)

1. Go to **"Certificates & secrets"**
2. Click **"+ New client secret"**
3. Add description: `IntelliJ Plugin`
4. Select expiration: `24 months`
5. Click **"Add"**
6. **Copy the secret VALUE immediately** (shown only once!)

### 3️⃣ Add Azure DevOps Permissions (1 minute)

1. Go to **"API permissions"**
2. Click **"+ Add a permission"**
3. Select **"Azure DevOps"**
4. Select **"Delegated permissions"**
5. Check: ✅ **user_impersonation**
6. Click **"Add permissions"**
7. (Optional) Click **"Grant admin consent"**

### 4️⃣ Configure Plugin (1 minute)

Edit: `src/main/kotlin/paol0b/azuredevops/checkout/AzureDevOpsOAuthService.kt`

Replace lines 50-51:

```kotlin
private val CLIENT_ID = "YOUR_CLIENT_ID_HERE"
private val CLIENT_SECRET = "YOUR_CLIENT_SECRET_HERE"
```

With your credentials:

```kotlin
private val CLIENT_ID = "12345678-abcd-1234-5678-123456789abc"
private val CLIENT_SECRET = "abC8Q~xXxXxXxXxXxXxXxXxXxXxXxXxXxX"
```

### 5️⃣ Build & Test (2 minutes)

```bash
./gradlew clean buildPlugin
```

Install plugin: **Settings → Plugins → ⚙️ → Install Plugin from Disk** → Select `build/distributions/AzureDevOps-1.2.zip`

### 4️⃣ Use OAuth Authentication

1. **File → New → Project from Version Control**
2. Select **Azure DevOps**
3. Enter organization URL: `https://dev.azure.com/YourOrg`
4. Click **"Sign in with Browser (OAuth)"**
5. Browser opens → Login → Done! ✅

## 📝 User Flow

```
┌─────────────────────────────────────────────────┐
│  File → New → Project from Version Control     │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Select: Azure DevOps                           │
│  (appears next to GitHub, GitLab)              │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Click "+" to add account                       │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Enter: https://dev.azure.com/MyOrganization    │
│  Click: "Sign in with Browser (OAuth)"         │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Browser opens → Login to Azure DevOps          │
│  (OAuth authentication happens here)            │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  ✅ Success! Credentials saved globally         │
│  Repository list appears                        │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Select repository → Clone                      │
└─────────────────────────────────────────────────┘
```

## 🔐 Security

- Credentials stored using IntelliJ `PasswordSafe` (encrypted)
- OAuth uses localhost callback (secure for local apps)
- Token never exposed in logs or UI

## 🛠️ Alternative: Use PAT

Don't want OAuth? Check **"Use Personal Access Token instead"**:

1. Create PAT: **Azure DevOps → User Settings → Personal Access Tokens**
2. Scope: **Code (Read)**
3. Enter PAT in dialog
4. Click **"Sign In"**

## 📖 Full Documentation

See [OAUTH_SETUP.md](./OAUTH_SETUP.md) for detailed documentation.
