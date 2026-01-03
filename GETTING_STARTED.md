# Getting Started - Azure DevOps Integration

Welcome! This guide will help you set up the Azure DevOps plugin and start managing Pull Requests from your IDE.

## Installation

1. **Open your JetBrains IDE** (IntelliJ IDEA, WebStorm, PyCharm, etc.)
2. Go to **Settings** (Windows/Linux) or **Preferences** (Mac)
3. Navigate to **Plugins** → **Marketplace**
4. Search for "**Azure DevOps Integration**"
5. Click **Install** and restart your IDE

That's it! The plugin is now ready to use.

---

## Step 1: Clone a Repository from Azure DevOps

The plugin integrates directly into JetBrains' standard clone dialog:

1. Go to **File** → **New** → **Project from Version Control**
2. Click **Azure DevOps** (instead of Git)
3. **If not logged in yet:** Sign in when prompted
   - The OAuth login dialog appears
   - Your browser opens to authenticate
   - Sign in with your Microsoft account
   - Grant access and return to the IDE
4. **If already logged in:** The clone tool window shows immediately:
   - Your Azure DevOps organizations
   - Projects and repositories
5. **Browse and select** the repository you want to clone
6. **Choose the target directory** on your computer
7. Click **Clone**

The repository is cloned and ready to use. The plugin automatically detects your Azure DevOps configuration.

---

## Step 2: Open the Tool Windows

Once you have a repository open, you'll see two new tool windows on the **right side** of your IDE:

### **Azure DevOps PRs** (Pull Request Manager)
- View all pull requests
- Create new PRs
- Review changes
- Approve or complete PRs

### **PR Comments** (Comment Browser)
- See all comments on the PR
- Jump to the exact line in your code
- Filter comments by status

If you don't see these windows, go to **View** → **Tool Windows** and select them.

---

## Common Tasks

### Create a Pull Request

1. Make sure your changes are **pushed to a branch** on Azure DevOps:
   ```bash
   git push origin your-branch-name
   ```

2. Open the **"Azure DevOps PRs"** tool window (right sidebar)

3. Click the **"Create PR"** button (or use **VCS** → **Create Azure DevOps PR**)

4. Fill in the form:
   - **Source Branch:** Your feature branch (e.g., `feature/add-login`)
   - **Target Branch:** Usually `main` or `develop`
   - **Title:** Brief description of changes (e.g., "Add user login")
   - **Description:** More detailed explanation (optional but recommended)
   - **Reviewers:** Add people who should review your code

5. Click **Create Pull Request**

6. Your PR is now live! You can see it in the PR list.

### View Pull Requests

1. Open the **"Azure DevOps PRs"** tool window
2. PRs are automatically loaded and grouped by status:
   - **Active:** Open PRs waiting for review
   - **Completed:** Merged PRs
   - **Abandoned:** Closed PRs
   - **All:** Every PR in the repository

3. Click on a PR to see details like:
   - Description
   - Reviewers
   - Changed files
   - Commits

### Review a Pull Request

1. Click on a PR in the list to see its **changes** and **commits**
2. Click on files to see what was modified
3. Click **"Open in Browser"** to see detailed comments and discussions (if you need more advanced features)

### View Comments

1. Open the **"PR Comments"** tool window
2. See all comments on the current pull request
3. Click on any comment to jump to that exact line in the code
4. Use filters to show:
   - **All** comments
   - **Active** comments (discussions still happening)
   - **Resolved** comments (already fixed)

---

## Tips & Tricks

### 🔄 Auto-Refresh
The plugin automatically refreshes your PR list every 30 seconds. You don't need to manually refresh.

### 🔐 Multiple Accounts
Working with different organizations? Add multiple accounts in **Settings** → **Tools** → **Azure DevOps Accounts**. The plugin automatically picks the right account for your repository.

### ⌨️ Keyboard Shortcuts
- **Create PR:** Use VCS menu → **Create Azure DevOps PR**
- **Show PR Comments:** Use VCS menu → **Show PR Comments**

### 🎨 Dark Mode
The plugin automatically adapts to your IDE's color theme (light or dark).

### 🔗 SSH vs HTTPS
The plugin works with both SSH and HTTPS repository URLs. No special setup needed.

---

## Troubleshooting

### **"Account not found"**
- Make sure you've added the account in **Settings** → **Tools** → **Azure DevOps Accounts**
- The account must be for the same organization as your repository

### **"Failed to authenticate"**
- Make sure you signed in with the correct Microsoft account
- Check that your account has access to the Azure DevOps organization
- Try removing and re-adding your account

### **PR tool window shows "Configure Azure DevOps"**
- Go to **Settings** → **Tools** → **Azure DevOps Accounts** and add an account
- Make sure the account matches your repository's organization

### **Comments not loading**
- Open the **PR Comments** tool window
- Comments should load automatically when you open a PR
- Click the refresh button if needed

---

## What's Next?

Now that you're set up, you can:

✅ **Browse pull requests** without leaving your IDE  
✅ **Create new PRs** in seconds  
✅ **Review changes** directly in your editor  
✅ **Read comments** on the exact lines of code  
✅ **Manage PRs** - approve, complete, or abandon them  

Enjoy coding and managing your PRs efficiently! 🚀

---

## Need Help?

- 📖 See [Common Workflows](docs/USAGE_EXAMPLES.md) for step-by-step examples
- 🔐 See [Authentication Setup](docs/OAUTH_SETUP.md) for OAuth configuration
- 🐛 Found a bug? Visit [GitHub Issues](https://github.com/paol0b/AzureDevOps/issues)
