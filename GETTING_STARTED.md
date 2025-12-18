# Getting Started - Azure DevOps Integration Plugin

## 🚀 Quick Start Guide

---

## 📋 Step-by-Step Setup

### 1. Install the Plugin

1. Open your JetBrains IDE
2. Go to **File → Settings → Plugins** (Windows/Linux) or **IntelliJ IDEA → Preferences → Plugins** (macOS)
3. Search for "Azure DevOps Integration"
4. Click **Install** and restart the IDE

### 2. Authenticate with Azure DevOps (OAuth 2.0)

1. Go to **File → Settings → Tools → Azure DevOps Accounts**
2. Click the **Add** button
3. Enter your Azure DevOps organization URL (e.g., `https://dev.azure.com/yourorganization`)
4. Click **Sign in with Browser (OAuth)**
5. Your browser will open - sign in with your Microsoft account
6. Copy the device code shown in the dialog
7. Paste it in the browser and authorize the application
8. Once authenticated, the account will appear in the list

**Benefits:**
- ✅ Secure OAuth 2.0 authentication
- ✅ Automatic token refresh
- ✅ Works across all projects in the same organization
- ✅ No need to manage tokens manually

---

## 🎯 Using the Plugin

### Tool Windows

The plugin adds two tool windows on the right side of the IDE:

#### 1. **Azure DevOps PRs** 📋

View and manage all Pull Requests:

- **Features:**
  - List of all PRs (active, completed, abandoned)
  - PR details: title, description, reviewers, status
  - Diff viewer for PR changes
  - Create new Pull Requests
  - Add comments and reviews
  - Lazy loading: opens automatically when you click the tab

- **Actions:**
  - Click **+** to create a new PR
  - Click **Refresh** to update the PR list
  - Select a PR to see its details and changes

#### 2. **PR Comments** 💬

Navigate and manage PR comments:

- **Features:**
  - Modern card-based UI
  - Search by file name, author, or content
  - Filter comments: All, Active, Resolved, General
  - **Auto-show comments**: Enable checkbox to automatically show comments on files
  - Auto-refresh every 30 seconds (intelligent: updates only if there are changes)
  - Navigate to file and line with one click
  - Lazy loading: opens automatically when you click the tab

- **How to use:**
  1. Open the "PR Comments" tab
  2. Enable "Auto-show comments on files" checkbox (optional)
  3. Use search field to filter comments
  4. Click on a comment to navigate to the file
  5. Press Enter on a selected comment to open the full thread dialog

---

## 🔧 Features in Detail

### Clone a Repository
1. Go to 'Clone Repository' (tool built in)
2. Select for 'Version Control' AzureDevops
3. If you are not signed in, do sing in
4. Select the Repo you like it and clone! (You can use search bar for searching)

### Create a Pull Request

1. Make sure your changes are committed to a branch
2. Click the **+** button in the "Azure DevOps PRs" tool window
   - OR: Go to **Git → Azure DevOps → Create Pull Request**
3. Fill in:
   - **Title**: Descriptive PR title
   - **Description**: Details about your changes
   - **Target branch**: Usually `main` or `develop`
   - **Reviewers**: Add required or optional reviewers
4. Click **Create**

### View PR Comments in Editor

When a PR exists for your current branch:

1. Open any file that has comments
2. Comments will automatically appear as gutter icons
3. Click on the icon to see the comment thread
4. Reply directly from the editor

**Or use the Auto-show feature:**
1. Open "PR Comments" tab
2. Enable "Auto-show comments on files"
3. Comments will appear automatically on all files when you open them

### File Tree Decorations

Files and folders with PR comments show badges:

- **Orange badge**: Active comments
- **Green badge**: Resolved comments
- **Number**: Comment count
- **Hover**: See detailed tooltip with comment breakdown

### Review a Pull Request

1. Select a PR from the "Azure DevOps PRs" tool window
2. View the changes in the diff viewer
3. Click on a line to add a comment
4. Submit your review with approval/rejection

---

## 🔐 Managing Accounts

### View Your Accounts

**Settings → Tools → Azure DevOps Accounts**

Here you can:
- See all authenticated accounts
- View account status (Valid ✅ / Expired ⚠️ / Invalid ❌)
- Add new accounts
- Remove accounts
- Test account authentication

### Account Status

- **Valid** ✅: Token is active and working
- **Expired** ⚠️: Token expired but has refresh token (auto-refreshed when used)
- **Invalid** ❌: Authentication failed, needs re-authentication

### Multiple Organizations

You can add multiple Azure DevOps accounts for different organizations:

1. Add each organization as a separate account
2. The plugin automatically matches your repository to the correct account
3. No manual switching needed - it's automatic!

---

## 🎨 User Interface

### PR Comments Tab Features

- **Search bar**: Filter comments instantly as you type
- **Filter buttons**: 
  - **All**: Show all comments
  - **Active**: Only unresolved comments
  - **Resolved**: Only resolved comments
  - **General**: Only general PR comments (not tied to specific lines)
- **Auto-show checkbox**: Automatically display comments when opening files
- **Refresh button**: Manually refresh comments
- **Status bar**: Shows comment counts and last check time

### Comment Cards

Each comment displays:
- **File location**: `filename:line` or "General PR Comment"
- **Author and timestamp**: Who wrote it and when
- **Comment preview**: First 80 characters
- **Comment count**: Number of replies in thread
- **Visual indicators**: Icons for active/resolved/general comments

---


## 🔍 Troubleshooting

### Authentication Issues

**Problem**: "Authentication required" error

**Solution**:
1. Go to **Settings → Tools → Azure DevOps Accounts**
2. Check account status
3. If expired/invalid, click **Remove** and **Add** again
4. Complete OAuth authentication in browser

### Comments Not Showing

**Problem**: Comments don't appear in editor or tool window

**Solution**:
1. Check that you have an active PR for your current branch
2. Click **Refresh** in the PR Comments tool window
3. Enable "Auto-show comments on files" checkbox
4. Verify your account has access to the repository


### Performance Issues

**Problem**: IDE feels slow

**Solution**:
- Both tool windows use lazy loading - they only load when you open them
- Auto-refresh is intelligent and only updates when needed
- Disable "Auto-show comments" if not needed
- The plugin has minimal impact on IDE performance

---

## 💡 Tips & Tricks


### Best Practices

1. **Use OAuth authentication**: Secure and convenient OAuth 2.0 authentication
2. **Enable auto-show comments**: Great for active code reviews
3. **Use search and filters**: Quickly find specific comments
4. **Keep the plugin updated**: New features and improvements regularly

### Integration with Git

- The plugin automatically detects your current branch
- PRs are associated with branches automatically
- Comments sync in real-time with Azure DevOps

---

## 🆘 Support

### Documentation

- **OAuth Setup**: See `docs/OAUTH_SETUP.md`
- **Usage Examples**: See `docs/USAGE_EXAMPLES.md`

### Getting Help

If you encounter issues:

1. Check the IDE logs: **Help → Show Log in Explorer/Finder**
2. Look for errors with "AzureDevOps" in the log
3. Report issues on the plugin's GitHub repository
4. Include log snippets and steps to reproduce

---
**Happy coding! 🎉**
