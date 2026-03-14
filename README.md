<div align="center">

# Azure DevOps Integration for JetBrains IDEs

**The complete Azure DevOps experience without leaving your editor**

[![Plugin Release](https://img.shields.io/badge/version-3.6-blue?style=flat-square)](docs/RELEASE_NOTES_3.0.md)
[![JetBrains IDE](https://img.shields.io/badge/JetBrains%20IDE-2025.3%2B-blue?style=flat-square&logo=jetbrains)](https://www.jetbrains.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3%2B-blueviolet?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-Paol0B%2FAzureDevOps-black?style=flat-square&logo=github)](https://github.com/Paol0B/AzureDevOps)

*Manage pull requests • Review code • Visualize pipelines • Control your entire Azure DevOps workflow from JetBrains*

</div>

---

## What is This?

**Azure DevOps Integration** is a professional-grade JetBrains plugin that seamlessly brings Azure DevOps into your IDE. Stop context-switching between your editor and the web browser. Manage pull requests, review code, monitor build pipelines, handle complex authentication flows, and collaborate with your team—all without leaving your IDE.

Built by developers, for developers who demand a seamless development experience.

---

## Why You Need This

### The Problem
Working with Azure DevOps projects in JetBrains IDEs is fragmented:
- Jump between IDE and browser for PR management
- Manual repository cloning with copy-paste URLs
- Context lost when context-switching
- Browser-based OAuth requires third-party tools
- No native pipeline visibility in your editor

### The Solution
This plugin brings **complete Azure DevOps integration** directly into your IDE:
- **Native PR Management** – Create, review, comment, and merge without switching windows
- **Smart Cloning** – Browse organizations, projects, and repos in an intuitive tree view
- **Enterprise Authentication** – OAuth2 browser flow + PAT support, seamlessly integrated
- **Pipeline Visualization** – Monitor stages, jobs, and logs in real-time
- **One-Click Workflow** – Everything you need, nothing you don't
- **Production-Ready** – Used by teams managing complex Azure DevOps deployments at scale

---

## ✨ Features

### Pull Request Management
Complete PR lifecycle management without navigating away from your code:

- **Create PRs** with auto-populated source/target branches and smart reviewers
- **List & Filter** by status (Active, Completed, Abandoned, Draft)
- **Review Changes** with full diffs, timeline, commit history, and visual comparisons
- **Inline Comments** – View, create, and resolve comments directly in the editor
- **PR Details** – Reviewers, iterations, merge status, commit count, and more
- **One-Click Actions** – Draft/publish conversion, approve, complete, or abandon PRs
- **Multi-PR Support** – Manage multiple pull requests simultaneously in dedicated panels

### Pipeline Management
Monitor your CI/CD pipelines from the comfort of your IDE:

- **Real-Time Visualization** – See pipeline stages, jobs, and their status at a glance
- **Detailed Logs** – Access build logs and diagnostic information without browser
- **Stage Tracking** – Know exactly which stages passed, failed, or are running
- **Integration with PRs** – See related pipeline runs directly in PR details
- **Build Status** – Quick indicators for success/failure with links to logs

### Enterprise Authentication
Security and convenience, no trade-offs:

- **OAuth 2.0 Browser Flow** – Modern, browser-based authentication (like VS 2026)
  - Works with single sign-on (SSO)
  - No app registration required
  - Automatic token refresh and lifecycle management
  - Industry-standard security
  
- **Personal Access Token (PAT)** – For automated workflows and CI/CD scenarios
  - Secure storage in IDE
  - Easy token rotation
  - Fallback when OAuth isn't available

- **Token Manager** – Centralized GitTokenManager handles all credential operations
  - Automatic cleanup
  - Secure in-memory caching
  - Global credential scope

### Repository Operations
Manage your codebase with enterprise-grade controls:

- **Smart Clone Wizard** – Visual browser for organizations, projects, and repositories
- **Connection Auto-Detection** – Automatically detects Azure DevOps from Git URLs
- **Branch Management** – Full branch control with local/remote autocomplete
- **Multi-URL Support** – HTTPS and SSH Git URLs handled transparently
- **Multi-Organization** – Seamlessly switch between multiple Azure DevOps orgs
- **Quick Access** – VCS menu integration for intuitive discovery

### Developer Experience
Designed from the ground up for power users:

- **Dedicated Tool Windows** – Purpose-built panels for PRs, pipelines, and comments
- **Native Integration** – VCS menu integration, keyboard shortcuts, context menus
- **Smart Notifications** – Stay informed without interruptions
- **Auto-Population** – Context-aware defaults (current branch, reviewers, etc.)
- **Keyboard Navigation** – Full keyboard support for power users
- **Real-Time Updates** – Automatic refresh without manual polling

---

## Quick Start

### Installation

Choose your preferred JetBrains IDE (IntelliJ IDEA, WebStorm, PyCharm, RubyMine, PhpStorm, GoLand, CLion, etc.):

1. Open **Settings** (Windows/Linux) or **Preferences** (macOS)
2. Navigate to **Plugins** → **Marketplace**
3. Search: `Azure DevOps Integration`
4. Click **Install** → Restart your IDE
5. Done!

### Your First PR: The 60-Second Workflow

**Step 1 – Clone a Repository**
```
File → New → Project from Version Control → Azure DevOps
  ↓
Sign in (Browser OAuth or PAT)
  ↓
Pick Org → Project → Repository
  ↓
Clone complete, ready to code
```

**Step 2 – Create a Pull Request**
```bash
# Make your changes and commit
git add .
git commit -m "feat: add awesome feature"
git push origin feature/awesome

# In IDE: VCS → Create Azure DevOps PR
# - Fill title, description, reviewers
# - Submit
# Your PR is live
```

**Step 3 – Review & Collaborate**
```
Azure DevOps PRs Tool Window
  ↓
See all PRs, latest comments, review status
  ↓
Click to view diffs, approve, or merge
  ↓
All without leaving your IDE
```

---

## Documentation

Get started and master the plugin with our comprehensive guides:

| Document | Purpose |
|----------|---------|
| **[Getting Started](docs/GETTING_STARTED.md)** | Step-by-step setup and your first PR |
| **[Usage Examples](docs/USAGE_EXAMPLES.md)** | Real-world workflows and advanced scenarios |
| **[OAuth Quick Start](docs/OAUTH_QUICK_START.md)** | Configure browser-based OAuth2 authentication |
| **[OAuth Setup Guide](docs/OAUTH_SETUP.md)** | Detailed OAuth configuration |
| **[Device Code Flow](docs/DEVICE_CODE_FLOW.md)** | Alternative authentication for restricted networks |
| **[Changelog](docs/CHANGELOG.md)** | Full release history and improvements |
| **[Release Notes (v3.0)](docs/RELEASE_NOTES_3.0.md)** | Major version features and breaking changes |

### Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Kotlin** | 2.3.10 | Core plugin implementation |
| **IntelliJ Platform** | 2025.3.3 | IDE SDK and API |
| **Gson** | 2.13.2 | JSON serialization |
| **OkHttp3** | 5.3.2 | HTTP client (native PATCH support) |
| **Git4Idea** | Bundled | Git integration |


## Contributing

We welcome contributions! Whether it's bug fixes, feature ideas, or documentation improvements:

1. **Check Existing Issues** – See if your idea is being tracked
2. **Fork & Branch** – Create a feature branch from `main`
3. **Make Changes** – Follow existing code style (Kotlin conventions)
4. **Test Thoroughly** – Run `./gradlew test` before submitting
5. **Submit PR** – Include a clear description and test results
6. **Code Review** – We'll provide feedback and collaborate

### Development Best Practices
- Follow [Kotlin Style Guide](https://kotlinlang.org/docs/coding-conventions.html)
- Add tests for new features
- Update documentation for user-facing changes
- Keep commits atomic and well-described

---

## License

This project is licensed under the **MIT License** – see [LICENSE](LICENSE) for full terms.

### Attribution

Built with:
- [JetBrains IntelliJ Platform](https://github.com/JetBrains/intellij-community) – IDE SDK
- [Kotlin](https://kotlinlang.org/) – Programming language
- [Gson](https://github.com/google/gson) – JSON processing
- [OkHttp](https://square.github.io/okhttp/) – HTTP client

---

## Support & Community

- **Issues & Bug Reports** – [GitHub Issues](https://github.com/Paol0B/AzureDevOps/issues)
- **Feature Requests** – [GitHub Discussions](https://github.com/Paol0B/AzureDevOps/discussions)
- **Plugin Marketplace** – Reviews and ratings

---

## Roadmap

We're continuously improving! Watch the repository for updates. Key areas we're exploring:

- Enhanced code review UI with inline commenting
- Git worktree support for multi-branch workflows
- Webhooks and custom notifications
- Integration with other Azure services
- Performance optimizations for large organizations

---

<div align="center">

**Ready to streamline your Azure DevOps workflow?** [Install Now](https://plugins.jetbrains.com/)

Made with care by developers who use these tools every day.

⭐ If this plugin helps you, please consider giving it a star on GitHub!

</div>

