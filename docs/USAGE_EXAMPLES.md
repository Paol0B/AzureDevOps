# Common Workflows & Examples

This guide shows you how to do common tasks with the Azure DevOps plugin. Each section has step-by-step instructions.

---

## Cloning Your First Repository

### The Standard Way Using JetBrains Clone Dialog

**Steps:**

1. **Open the clone dialog**
   - Go to **File** → **New** → **Project from Version Control**

2. **Select Azure DevOps**
   - Click **Azure DevOps** (instead of Git)
   - This is where the plugin integrates with JetBrains

3. **Authenticate if needed**
   - If you see "Sign in with Browser" button:
     - Click it
     - Your browser opens
     - Sign in with your **Microsoft account**
     - Grant access to the plugin
     - Return to the IDE
   - If already logged in, skip to next step

4. **Browse repositories**
   - The clone tool window shows:
     - Your Azure DevOps organizations
     - Projects in each organization
     - Repositories in each project
   - Use the search bar to find a specific repo

5. **Select and clone**
   - Click on the repository you want
   - Choose a **target directory** on your computer
   - Click **Clone**

6. **Done!**
   - The repository is cloned
   - The IDE opens it automatically
   - You can now use the Azure DevOps PR tool windows

---

### Scenario: Feature branch to main

You've finished working on a new feature and want to create a PR for review.

**Setup:**
- Your branch: `feature/user-authentication`
- Target branch: `main`

**Steps:**

1. **Push your changes**
   ```bash
   git push origin feature/user-authentication
   ```

2. **Open the PR creation dialog**
   - Option A: Click the **+** button in the "Azure DevOps PRs" tool window
   - Option B: Use menu **VCS** → **Create Azure DevOps PR**

3. **Fill in the PR details**
   - **Source Branch:** `feature/user-authentication` (should be auto-selected)
   - **Target Branch:** `main` (should be auto-selected)
   - **Title:** `Add user authentication system`
   - **Description:**
     ```
     ## Changes
     - Added login form UI
     - Implemented OAuth 2.0 integration
     - Added unit tests for auth service
     - Updated documentation
     
     ## Testing
     - Tested on Chrome, Firefox, Safari
     - All unit tests passing
     - Manual testing completed
     
     Closes #123
     ```
   - **Reviewers:** Add teammates who should review (required vs optional)

4. **Click "Create Pull Request"**

5. **Done!** Your PR is now created. You can see it in the PR list.

---

## Reviewing a Pull Request

### Scenario: A colleague wants you to review their code

**Steps:**

1. **Find the PR you need to review**
   - Open the "Azure DevOps PRs" tool window
   - Look in the **Active** section
   - Click on the PR title

2. **See what changed**
   - View the list of changed files
   - Click on any file to see what lines were added/modified/deleted
   - Look for the status badge: [A] = Added, [M] = Modified, [D] = Deleted

3. **Read the PR description**
   - Understand what the PR is trying to do
   - Check if it solves the mentioned issue

4. **Check the commits**
   - Click on the "Commits" tab
   - See the commit messages
   - Verify the work makes sense

5. **View comments**
   - Open the "PR Comments" tool window
   - See if others have already commented
   - Read previous discussion threads

6. **Approve or request changes**
   - Click **"Approve"** button if you're satisfied
   - Click **"Open in Browser"** if you need to leave detailed comments
   - Or click **"Abandon"** if the PR should be closed

---

## Finding a Specific Comment

### Scenario: You remember a comment but forgot which file it's on

**Steps:**

1. **Open the "PR Comments" tool window**
   - Look for the PR Comments tab on the right side
   - Comments should load automatically

2. **Search for the comment**
   - Use the search field at the top
   - Type keywords you remember (author name, part of the comment text, etc.)
   - Results update as you type

3. **Filter by status (optional)**
   - Click **"Active"** to see only open discussions
   - Click **"Resolved"** to see only closed discussions
   - Click **"All"** to see everything

4. **Click on the comment**
   - The IDE jumps to the exact file and line
   - The comment opens in an inline dialog

5. **Reply to the comment**
   - Type your response
   - Press Enter to save

---

## Switching Between PRs

### Scenario: You're reviewing multiple PRs and need to switch between them

**Steps:**

1. **Open the "Azure DevOps PRs" tool window**

2. **Click on a different PR**
   - The details panel updates
   - The file changes update
   - Comments refresh automatically

3. **Notice the comments update**
   - The "PR Comments" tool window automatically shows comments from the new PR
   - No manual refresh needed

---

## Checking PR Status

### Scenario: You want to know the current status of your PR

**Steps:**

1. **Open the "Azure DevOps PRs" tool window**

2. **Find your PR**
   - Look in the **Active** section if it's not merged yet
   - Look in the **Completed** section if it's merged
   - Look in the **Abandoned** section if it was closed

3. **Check the status badge**
   - **Active** = Still waiting for review
   - **Completed** = Merged successfully
   - **Abandoned** = Closed without merging

4. **See who approved/rejected**
   - Click on the PR to see reviewer details
   - Look at the reviewers list
   - Check their approval status

---

## Merging a PR

### Scenario: Your PR has been approved and is ready to merge

**Steps:**

1. **Open the PR in the tool window**
   - Click on your PR in the "Azure DevOps PRs" tool window

2. **Make sure it's approved**
   - Check the reviewers list
   - Everyone should have approved (if required)

3. **Click "Complete PR"** button
   - A dialog might appear asking about the merge strategy
   - Choose your merge strategy (usually "Squash" or "Commit")
   - The PR is merged!

4. **Update your local code**
   ```bash
   git checkout main
   git pull origin main
   ```

---

## Abandoning a PR

### Scenario: The PR is no longer needed and should be closed

**Steps:**

1. **Open the PR in the tool window**
   - Click on the PR you want to close

2. **Click "Abandon PR"** button
   - Confirm when asked
   - The PR is now marked as Abandoned

3. **Optionally add a comment**
   - Add a note explaining why you're abandoning it
   - This helps your team understand the decision

---

## Working With Multiple Organizations

### Scenario: You work with code in multiple Azure DevOps organizations

**Setup:**
- Organization A: `https://dev.azure.com/companyA`
- Organization B: `https://dev.azure.com/companyB`

**Steps:**

1. **Add both organizations**
   - Go to **Settings** → **Tools** → **Azure DevOps Accounts**
   - Click **Add** (repeat for each organization)
   - Sign in to each one

2. **Switch repositories**
   - When you switch projects in your IDE
   - The plugin automatically detects which organization you're in
   - It uses the correct account automatically
   - No manual switching needed!

---

## Filtering PRs by Status

### Scenario: You only want to see PRs you created

**Steps:**

1. **Open the "Azure DevOps PRs" tool window**

2. **Use the filter buttons**
   - **Active** - Open PRs (default)
   - **Completed** - Merged PRs
   - **Abandoned** - Closed PRs
   - **All** - Every PR in the repo
   - **My** - Only PRs you created

3. **The list updates**
   - Only PRs matching the filter are shown

---

## Creating a Draft PR

### Scenario: You want to get early feedback but the PR isn't ready yet

**Steps:**

1. **Open the PR creation dialog**
   - Click **+** in the "Azure DevOps PRs" tool window
   - Or use **VCS** → **Create Azure DevOps PR**

2. **Fill in the details normally**
   - Title, description, source/target branches
   - Add reviewers as usual

3. **Create the PR**
   - The PR is created
   - You can mark it as Draft in Azure DevOps (open in browser)
   - Reviewers get notified but know it's not ready yet

4. **Update when ready**
   - Keep pushing commits to the same branch
   - Comments will appear in the PR
   - When ready, click "Mark as Ready" in the browser

---

## Adding Required Reviewers

### Scenario: Certain team members must approve before merging

**Steps:**

1. **Open the PR creation dialog**

2. **Add reviewers**
   - Type a name in the reviewers field
   - A list of available reviewers appears
   - Select the person's name

3. **Mark as required**
   - When adding, specify if they're **required** or **optional**
   - Required reviewers must approve before the PR can be merged
   - Optional reviewers are nice to have

4. **Create the PR**
   - Reviewers are automatically notified
   - The PR shows their approval status

---

## Tips & Best Practices

### Writing Good PR Titles
- ✅ DO: `Add user login functionality`
- ❌ DON'T: `Fix stuff` or `Update code`

### Writing Good PR Descriptions
- ✅ Include **What** changed and **Why**
- ✅ Add **Testing** info - how did you test this?
- ✅ Reference related **Issues** - use `Fixes #123`
- ✅ Use **Markdown** for formatting

### Reviewing Code
- ✅ Read the entire description first
- ✅ Understand the intent, not just the code
- ✅ Ask questions if something's unclear
- ✅ Be respectful and constructive
- ❌ Don't nit-pick about style if that's auto-formatted

### Commit Messages
Use descriptive commit messages:
```
✅ Good:
- "Add authentication module"
- "Fix null pointer in user service"
- "Refactor database connection logic"

❌ Bad:
- "fix"
- "update"
- "asdf"
```

---

## Keyboard Shortcuts

### Create PR
- Use **VCS** menu → **Create Azure DevOps PR**
- Or click the **+** button in the tool window

### Show Comments
- Use **VCS** menu → **Show PR Comments**

### Open in Browser
- Click the **"Open in Browser"** link in the PR details

---

## Troubleshooting Common Issues

### "No pull requests found"
- Check if you've pushed your branch to Azure DevOps
- Click **Refresh** in the tool window
- Make sure the branch exists in the remote

### "Can't create PR - branch doesn't exist"
- Push your branch first: `git push origin your-branch`
- Wait a few seconds
- Try creating the PR again

### "Reviewers not showing up"
- Click **Refresh** in the tool window
- Make sure the reviewers are part of your organization
- Check if they have access to the repository

### "Can't merge - not approved"
- Check the reviewers list
- Make sure all required reviewers have approved
- Ask reviewers to approve if they haven't yet

---

## Next Steps

- 📖 See [Getting Started](../GETTING_STARTED.md) for setup
- 🔐 See [Authentication](OAUTH_SETUP.md) for account help
- 🐛 Found a bug? [Report it on GitHub](https://github.com/paol0b/AzureDevOps/issues)

Happy reviewing! 🚀
