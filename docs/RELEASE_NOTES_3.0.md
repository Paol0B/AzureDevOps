# 🚀 Azure DevOps Integration v3.0
## *The Complete PR Review & Pipeline Experience*

---

## What's New?

v3.0 is a **major evolution** of the plugin. We've completely rethought how you review code and manage pipelines in your IDE. This is the release that finally makes Azure DevOps feel *native* to JetBrains.

<table>
<tr>
<td width="50%">

### ⏱️ **Before v3.0**
- Jump to browser to review PRs
- Click back and forth for context
- No inline comment support
- Pipeline details hidden in web
- Fragmented experience

</td>
<td width="50%">

### ✨ **With v3.0**
- Review complete PRs in IDE
- Everything you need on screen
- Inline commenting native
- Pipeline insights at hand
- Seamless workflow

</td>
</tr>
</table>

---

## 🎯 The Big Features

### 1️⃣ Full Pull Request Review (The Game-Changer)

You asked for it. We built it. **Review PRs entirely in your IDE without switching tabs.**

#### What you get:
- **Diff Viewer** → Side-by-side or inline diff with syntax highlighting
- **Timeline** → Understand the PR's journey: updates, comments, approvals, in order
- **Comments Panel** → Read & reply without leaving your editor
- **File Tree** → Navigate changed files like your project navigator
- **Inline Comments** → Add feedback directly on specific lines

#### The magic:
Jump to any line, see comments in context, understand the PR history—all while your hands stay on the keyboard. **No browser. No tab switching. Pure flow.**

---

### 2️⃣ Pipeline Visualization (Finally Readable)

Your pipelines shouldn't require a browser tab. **See stages, jobs, and logs from your IDE.**

#### Dashboard View:
- **Pipeline Explorer** → All runs in one place, filtered by status
- **Stage Diagram** → Visual representation of your pipeline flow
- **Job Details** → Click any job to see timing, status, logs
- **Live Logs** → Stream logs with search and syntax highlighting
- **Auto-Refresh** → Stays up-to-date without manual refresh

#### Win:
Stop alt-tabbing to the Azure DevOps portal. Your pipelines are now *inside* your IDE. Watch builds complete while coding.

---

### 3️⃣ Smarter Clone Dialog

Cloning from Azure DevOps should be as easy as GitHub. **Now it is.**

#### Experience:
- Browse organizations → projects → repositories in tree view
- Search repositories by name instantly  
- See repo descriptions inline
- One-click clone with proper setup
- Switch accounts without leaving the dialog

#### Benefit:
Onboarding new projects takes seconds, not minutes.

---

### 4️⃣ Enhanced PR Management

#### Complete PRs Intelligently:
- **Auto-Complete Settings** → Set policies (squash, merge, delete branch)
- **One-Click Actions** → Complete, abandon, or approve from tool window
- **Smart Branch Handling** → Sync branch state automatically
- **Status Tracking** → Know exactly where your PR stands

#### What Changed:
No more half-finished actions. PR management is now as native as Git operations.

---

### 5️⃣ Visual Polish & User Avatars

#### The Details:
- **User Avatars** → See who reviewed and approved with actual profile pics
- **Redesigned Icons** → Fresh, modern icon set for all tool windows
- **Better Visual Hierarchy** → Important info stands out
- **Responsive Layout** → Adapts to your workspace

#### Why It Matters:
Great design makes tools disappear. You just *work*.


## 🔧 Technical Goodies

For the detail-oriented:

- ✅ **PR Review Tab Service** — Manages PR review editor lifecycle
- ✅ **Pipeline Tab Service** — Handles pipeline visualization state
- ✅ **Avatar Caching** — Efficient profile picture management
- ✅ **Delta Log Streaming** — Only updates changed log lines (bandwidth saver)
- ✅ **Subtree Building** — Intelligent pipeline graph rendering
- ✅ **File-Scoped Comments** — Thread comments intelligently by file

---

## 🎓 How to Get Started

### Fresh Install?
Head to **File → New → Project from Version Control → Azure DevOps** and sign in. Everything just works.

### Upgrading from 2.2?
Simply install v3.0. Your accounts and settings carry over. 

**New Tool Windows to Explore:**
1. **"Azure DevOps Pipelines"** (left sidebar) — Pipeline visualization
2. **"PR Timeline"** (editor tabs) — When you open a PR review

---


## 🐛 Bug Fixes (v2.2 → v3.0)

- ✅ Fixed: Comment parsing in complex PR threads
- ✅ Fixed: URL handling for repos with spaces
- ✅ Fixed: Token expiration edge cases
- ✅ Fixed: File decorator showing incorrect comment counts
- ✅ Fixed: PR list flickering on rapid updates
- ✅ Improved: Memory usage for large pipeline runs

---

## 📚 Documentation

Deep dive into each feature:
- 📖 [Getting Started Guide](GETTING_STARTED.md)
- 🔐 [Authentication Setup](docs/OAUTH_SETUP.md)
- 📋 [Common Workflows](docs/USAGE_EXAMPLES.md)
- ⚙️ [OAuth Device Code Flow Technical](docs/DEVICE_CODE_FLOW.md)

---

## 🗣️ What's Coming?

Paol0B is already cooking the next features:
- 📊 PR metrics & trends dashboard

---

## 💚 Thank You

v3.0 exists because of feedback from developers like you. Special thanks to everyone who reported bugs, suggested features, and championed this plugin in your teams.

### Found an issue? 
📫 **Keep the feedback coming** → [GitHub Issues](https://github.com/paol0b/AzureDevOps/issues)

### Love it? 
⭐ **Let others know** → [Rate on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/your-plugin-id)

---

## 📋 Compatibility

- ✅ IntelliJ IDEA Community & Ultimate 2025.1+
- ✅ WebStorm, PyCharm, PhpStorm, etc. (all JetBrains IDEs)
- ✅ macOS, Windows, Linux
- ✅ Azure DevOps Cloud & Server (2019+)

---

<div align="center">

### **v3.0 is Ready. Let's Ship It! 🚀**

*Made with ❤️ by [Paolo Bertinetti](https://github.com/paol0b)*

</div>
