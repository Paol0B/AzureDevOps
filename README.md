# Azure DevOps Integration Plugin

A plugin for JetBrains IDEs (IntelliJ IDEA, Rider, PyCharm, etc.) that allows you to create Pull Requests on Azure DevOps directly from your IDE.

## 🎯 Features

- ✅ **Dedicated Tool Window** to view and manage Pull Requests
- ✅ **PR Visualization** with list organized by state (Active, Completed, Abandoned)
- ✅ **Complete PR Details** - title, description, branch, author, reviewers
- ✅ **Smart Filters** - show only active PRs or all
- ✅ **Automatic Detection** of Azure DevOps repository from Git remote URL
- ✅ **Pull Request Creation** to main/master from your IDE
- ✅ **Branch Selection** with autocomplete from local and remote branches
- ✅ **Simple Configuration** - requires only Personal Access Token (PAT)
- ✅ **Auto-Detection** of Organization, Project and Repository from Git URL
- ✅ **Notifications** for immediate feedback
- ✅ **Direct Link** to created PR in browser
- ✅ **Compatibility** with HTTPS and SSH URLs of Azure DevOps
- ✅ **Smart Visibility** - tool window and actions appear only for Azure DevOps repositories

## 📋 Requirements

### 1. Azure DevOps Repository

The plugin works **only with repositories cloned from Azure DevOps**. It must automatically detect one of these URL formats:

**HTTPS:**
- `https://dev.azure.com/{organization}/{project}/_git/{repository}`
- `https://{organization}.visualstudio.com/{project}/_git/{repository}`

**SSH:**
- `git@ssh.dev.azure.com:v3/{organization}/{project}/{repository}`
- `{organization}@vs-ssh.visualstudio.com:v3/{organization}/{project}/{repository}`

The plugin automatically detects **Organization**, **Project**, and **Repository** from the Git remote URL.

### 2. Personal Access Token (PAT)

You need to create a Personal Access Token on Azure DevOps with the following permissions:

1. Go to Azure DevOps → User Settings → Personal Access Tokens
2. Click "New Token"
3. Select the permissions:
   - **Code**: Read & Write
   - **Pull Request**: Read & Write
4. Copy the generated token (you'll use it in the configuration)

### 3. Azure DevOps Repository

Your project repository must be cloned from Azure DevOps. The plugin automatically detects information from the Git remote URL.

**No need to manually configure** organization, project, or repository!

## 🚀 Installation

### Option 1: From Marketplace (when published)
1. Open IDE → Settings → Plugins
2. Search for "Azure DevOps Integration"
3. Click Install and restart IDE

### Option 2: Local Build
```bash
# Clone the repository
git clone https://github.com/paol0b/azuredevops-plugin.git
cd azuredevops-plugin

# Build the plugin
./gradlew buildPlugin

# The .zip file will be in build/distributions/
```

Then install manually:
1. Settings → Plugins → ⚙️ → Install Plugin from Disk
2. Select the generated `.zip` file

## ⚙️ Configuration

### First Setup

1. **Make sure you have an Azure DevOps repository cloned**
   ```bash
   # HTTPS clone example
   git clone https://dev.azure.com/mycompany/MyProject/_git/my-repo
   
   # SSH clone example
   git clone git@ssh.dev.azure.com:v3/mycompany/MyProject/my-repo
   ```

2. **Open the project in your JetBrains IDE**

3. **Go to Settings**
   - File → Settings on Windows/Linux
   - IntelliJ IDEA → Preferences on macOS
   - Or: `Ctrl+Alt+S` (Win/Linux) / `Cmd+,` (Mac)

4. **Navigate to Tools → Azure DevOps**

5. **Verify the detected repository**
   - You should see: "Detected Repository: **mycompany/MyProject/my-repo**"
   - If it doesn't appear, the repository is not from Azure DevOps or the remote URL is not recognized

6. **Enter only the Personal Access Token (PAT)**
   - Paste the token created earlier

7. **Click Test Connection** to verify everything works

8. **Click Apply → OK**

> 💡 **Note**: Organization, Project and Repository are automatically detected from the Git remote URL!

### Configuration Example

```
✅ Detected Repository: mycompany/MyProject/my-repo
   Auto-detected from Git remote URL

🔑 Personal Access Token: ••••••••••••••••••••
```

## 📝 Usage

### Viewing Pull Requests

1. **Open the "Pull Requests" Tool Window**
   - Located in the IDE's bottom bar (like the Commit/Git/Terminal tab)
   - Or: View → Tool Windows → Pull Requests
   - Shortcut: Customizable in Keymap

2. **Explore PRs**
   - **PR List**: View all PRs organized by state (Active, Completed, Abandoned)
   - **PR Details**: Click on a PR to see title, description, branch, author, reviewers, and status
   - **Filter**: Toggle "Show Only Active" to show only active PRs

3. **Available Actions**
   - **New Pull Request**: Create a new PR
   - **Refresh**: Update the PR list
   - **Open in Browser**: Open the selected PR in Azure DevOps

### Creating a Pull Request

1. **Method 1 - VCS Menu:**
   - Go to **VCS → Create Azure DevOps PR**

2. **Method 2 - Toolbar:**
   - Look for the icon in the VCS toolbar

3. **In the Dialog:**
   - **Source Branch**: select the branch you want to create the PR from (default: current branch)
   - **Target Branch**: select the destination branch (default: main or master)
   - **Title**: enter the PR title (required)
   - **Description**: add an optional description
   - Click **OK**

4. **Result:**
   - You'll see a success notification with the PR number
   - You can click "Open in Browser" to open the PR in Azure DevOps

### Example Screenshot

```
┌──────────────────────────────────────────────┐
│ Create Azure DevOps Pull Request            │
├──────────────────────────────────────────────┤
│ Source Branch: │ feature/new-feature      ▼  │
│ Target Branch: │ main                     ▼  │
│ ─────────────────────────────────────────────│
│ Title: │ Add new feature                  │  │
│ Description:                                 │
│ ┌──────────────────────────────────────────┐ │
│ │ This PR adds the new feature for...     │ │
│ │                                          │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│              [Cancel]  [  OK  ]              │
└──────────────────────────────────────────────┘
```

> 💡 **Note**: The plugin only appears if the repository is cloned from Azure DevOps!

## 🏗️ Architecture

### Project Structure

```
src/main/kotlin/paol0b/azuredevops/
├── actions/
│   └── CreatePullRequestAction.kt      # Action to create PR
├── model/
│   └── AzureDevOpsModels.kt           # Data classes
├── services/
│   ├── AzureDevOpsApiClient.kt        # REST API Client
│   ├── AzureDevOpsConfigService.kt    # Configuration management
│   └── GitRepositoryService.kt        # Local Git service
└── ui/
    ├── AzureDevOpsConfigurable.kt     # UI Settings
    └── CreatePullRequestDialog.kt     # PR creation dialog
```

### Main Components

#### 1. **AzureDevOpsRepositoryDetector**
- Automatically detects if the repository is from Azure DevOps
- Supports HTTPS and SSH URLs (dev.azure.com and visualstudio.com formats)
- Extracts Organization, Project, and Repository from Git remote URL
- Pattern matching for all known Azure DevOps formats

#### 2. **AzureDevOpsConfigService**
- Manages only the Personal Access Token (PAT)
- Uses auto-detection for organization, project, repository
- Securely saves PAT using `PasswordSafe`
- Persistence with `PersistentStateComponent`

#### 3. **AzureDevOpsApiClient**
- Communicates with Azure DevOps REST API v7.0
- Handles Basic Auth authentication
- Robust error handling

#### 4. **GitRepositoryService**
- Interacts with Git4Idea plugin
- Detects local and remote branches
- Automatically determines target branch (main/master)

#### 5. **CreatePullRequestAction**
- Action registered in VCS menu
- Shows dialog and handles creation
- Background task to not block UI
- Success/error notifications

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

## 🐛 Troubleshooting

### "This is not an Azure DevOps repository"
- Verify that the repository is cloned from Azure DevOps
- Check the remote URL: `git remote -v`
- The URL must match one of the supported formats:
  - `https://dev.azure.com/{org}/{project}/_git/{repo}`
  - `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`

### "Authentication failed (401)"
- Verify that the PAT is correct
- Check if the PAT has expired
- Make sure the PAT has the correct permissions

### "Resource not found (404)"
- The repository might not exist or the name is incorrect
- Verify on Azure DevOps that the repository exists
- Make sure you have access to the repository
- Check that the Git remote URL is correct

### "Insufficient permissions (403)"
- The PAT must have permissions:
  - Code: Read & Write
  - Pull Request: Read & Write

### "No Git repository found"
- Make sure the project has an initialized Git repository
- Verify that Git4Idea plugin is enabled
- Check that there is at least one remote configured: `git remote -v`

### "Plugin doesn't appear in VCS menu"
- Verify that the repository is cloned from Azure DevOps
- The action appears only for automatically detected Azure DevOps repositories
- Check the Git remote URL

### "Source and target branch cannot be the same"
- Select different branches for source and target
- Typically: feature branch → main/master

## 📄 License

This project is released under the MIT license.

## 👤 Author

**Paolo Bertinetti**
- GitHub: [@paol0b](https://github.com/paol0b)

## 🤝 Contributing

Contributions are welcome!

1. Fork the project
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## Roadmap

### MVP (Implemented ✅)
- [x] Azure DevOps configuration
- [x] Create Pull Request
- [x] Source/target branch selection
- [x] Auto-detect main/master
- [x] Secure credential storage
- [x] Success/error notifications
- [x] Link to created PR
- [x] Integrated diff viewer
- [x] Display list of active PRs

### Planned Features
- [ ] Approve/Reject PRs from the IDE
- [ ] Add comments to PRs
- [ ] Assign reviewers
- [ ] Multi-repository support
- [ ] PR description templates
- [ ] Draft PR support
- [ ] Autocomplete options

## ❓ FAQ

**Q: Does it support GitHub?**  
A: No, this plugin is specific to Azure DevOps. For GitHub, use the official plugin.

**Q: Does it work with GitLab/Bitbucket?**  
A: No, Azure DevOps only.

**Q: Which IDEs are supported?**  
A: All IntelliJ Platform-based IDEs (2025.1+): IntelliJ IDEA, Rider, PyCharm, WebStorm, etc.

**Q: Is the PAT secure?**  
A: Yes, it's saved using the IDE's PasswordSafe, which encrypts credentials.

**Q: Can I use it in multi-repository projects?**  
A: Currently supports the first repository found. Multi-repo support is on the roadmap.

---

**Happy coding! 🚀**
