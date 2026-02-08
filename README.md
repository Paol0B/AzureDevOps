<div align="center">

# 🚀 Azure DevOps Integration Plugin

### Seamless Pull Request Management for JetBrains IDEs

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-blue?logo=jetbrains)](https://plugins.jetbrains.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

*Create and manage Azure DevOps Pull Requests without leaving your IDE*

[Features](#-features) • [Installation](#-installation) • [Quick Start](#-quick-start) • [Documentation](#-documentation)

</div>

---

## IMPORTANT MIGRATION NOTICE (READ BEFORE UPGRADING)

> If you are upgrading from Azure DevOps Integration 1.x to 2.x and you use the new sign-in location (Tools → Azure DevOps Accounts), you may see one or more accounts listed that appear to be non-functional. These are likely leftover accounts created by the old manual Personal Access Token (PAT) authentication and are missing refresh tokens required by the new OAuth 2.0 flow.

- **Why this happens:** old PAT-based accounts do not include a refresh token, so the new OAuth-based flow cannot refresh them and they will not work correctly.
- **Recommended action:** open Tools → Azure DevOps Accounts, remove any existing accounts that came from the previous 1.x authentication, then sign in again using the new OAuth 2.0 sign-in (Tools → Azure DevOps Accounts → Sign In). This ensures tokens are stored with refresh capability and will be maintained automatically.
- **If you must keep PATs:** re-add them intentionally and understand they do not support refresh tokens and may expire.

Please perform this cleanup before using the new authentication features to avoid broken accounts and unexpected authentication failures.


## ✨ Features

<table>
<tr>
<td width="50%">

### 🎯 **Pull Request Management**
- View and organize PRs by state (Active, Completed, Abandoned)
- Create PRs directly from your IDE
- Complete PR details at a glance
- Quick access to PR in browser
- Review PR changes with diffs, timeline, and comments

</td>
<td width="50%">

### 🔧 **Smart Integration**
- Auto-detects Azure DevOps repositories
- Supports HTTPS and SSH URLs
- Branch autocomplete from local/remote
- Secure credential storage

</td>
</tr>
<tr>
<td width="50%">

### 🎨 **User Experience**
- Dedicated tool window
- Smart filters for PR views
- Real-time notifications
- Seamless IDE integration
- Full pipeline visualization with stages, jobs, and logs

</td>
<td width="50%">

### 🔐 **Authentication**
- **OAuth 2.0 with Browser** (like Visual Studio 2022)
- Personal Access Token (PAT) fallback
- No app registration required
- Credentials saved globally in IDE

</td>
</tr>
</table>

---

## 🎬 Quick Demo

```bash
# 1. Clone from Azure DevOps with OAuth
File → New → Project from Version Control → Azure DevOps
  → Sign in with Browser → Clone repository

# 2. Or clone manually and open
git clone https://dev.azure.com/mycompany/MyProject/_git/my-repo

# 3. Start creating PRs! 🎉
```

---

## � Installation

### Via JetBrains Marketplace
1. Open IDE → Settings → Plugins
2. Search for "Azure DevOps Integration"
3. Click Install and restart IDE

## 🔧 Development

### Development Environment Setup

```bash
# Clone the repository
git clone https://github.com/paol0b/azuredevops-plugin.git
cd azuredevops-plugin

# Build
./gradlew buildPlugin

# Run in IDE sandbox
./gradlew runIde

# Test
./gradlew test
```

### Dependencies

- **Kotlin**: 2.1.0
- **IntelliJ Platform**: 2025.1
- **Gson**: 2.11.0 (for JSON parsing)
- **Git4Idea**: Plugin bundled

### Azure DevOps APIs Used

- **POST** `/git/repositories/{repositoryId}/pullrequests` - Create PR
- **GET** `/git/repositories/{repositoryId}` - Verify connection

Documentation: [Azure DevOps REST API Reference](https://learn.microsoft.com/en-us/rest/api/azure/devops/)

---

**Happy coding! 🚀**
