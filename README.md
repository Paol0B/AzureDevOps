<div align="center">

# Azure DevOps Integration for JetBrains IDEs

**Pull requests, work items, pipelines, code reviews — without leaving your editor.**

[![Version](https://img.shields.io/badge/version-4.0-blue?style=flat-square)](docs/CHANGELOG.md)
[![JetBrains IDE](https://img.shields.io/badge/JetBrains-2025.3%2B-blue?style=flat-square&logo=jetbrains)](https://www.jetbrains.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-blueviolet?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-green?style=flat-square)](LICENSE)

</div>

---

A JetBrains plugin that brings Azure DevOps into your IDE. Manage the full pull request lifecycle, track work items on a Kanban board, monitor pipeline runs, review code with inline comments, and keep an eye on build status — all from one place. No browser tabs, no context switching.

Works with Azure DevOps Services (cloud) and Azure DevOps Server (on-premise).

---

## Pull Request Management

Everything you need to handle PRs without opening a browser.

- **Create pull requests** from a dedicated panel with diff preview, branch auto-detection, and reviewer selection
- **Browse and filter** by status — Active, Completed, Abandoned, Draft
- **Review code** with full file diffs, commit history, and iteration tracking
- **Inline comments** — read, write, reply, and resolve comment threads directly on diff lines
- **PR timeline** — follow the full history of a PR: creation, updates, votes, status changes, comments
- **Actions** — approve, complete, abandon, set auto-complete, convert to draft or publish, all from the IDE
- **Multi-PR workflow** — open multiple reviews side by side in editor tabs

## PR Metrics Dashboard

Visualize pull request activity across your team.

- Bar charts and donut charts breaking down PR volume, review times, and outcomes
- Leaderboard ranking contributors by review activity
- Metric cards with key indicators at a glance

## Work Items

Full work item management built into the IDE.

- **List view** with filtering by type (Bug, Task, User Story, Epic), state, assignment, area path, and free-text search
- **Kanban board** — visual board with columns per state, color-coded cards showing type, priority, and assignee
- **Sprint view** — browse work items grouped by iteration
- **Detail panel** — description, acceptance criteria, comments, history, and related items
- **Create and edit** work items with all standard fields
- **Comments** — read and post work item comments inline
- **Branch integration** — create a branch from a work item with automatic naming, and auto-populate commit messages with the linked work item ID when committing on that branch
- **State management** — change work item state from context menus

## Pipelines

Monitor CI/CD runs and access logs without switching to the web.

- Pipeline list with stage, job, and step visualization
- Filter by pipeline name, status, branch, and date range
- Stream build logs with search and auto-refresh
- Stage-level status tracking — see exactly what passed, failed, or is running

## Status Bar

A widget in the IDE status bar shows the current build status and key metrics for your repository at a glance.

## Authentication

Supports two authentication methods, both for cloud and on-premise instances:

- **OAuth 2.0 browser flow** — sign in with your Microsoft account. Works with SSO, no app registration required, tokens refresh automatically.
- **Personal Access Token (PAT)** — for environments where OAuth isn't available or for automation scenarios. Tokens are validated on entry with clear permission reporting.

Credentials are stored in the IDE's built-in password manager. Multiple accounts and organizations are supported with automatic detection based on the current repository.

## Repository Cloning

Browse your Azure DevOps organizations, projects, and repositories in a tree view. Select a repo, pick a directory, and clone — the plugin handles authentication and Git credential setup automatically.

---

## Getting Started

1. Install the plugin from **Settings > Plugins > Marketplace** (search "Azure DevOps Integration")
2. Restart your IDE
3. Go to **Settings > Tools > Azure DevOps Accounts** and add your account (OAuth or PAT)
4. Open a project cloned from Azure DevOps — the plugin detects the connection automatically
5. Use the **Azure DevOps PRs**, **Azure DevOps Pipelines**, and **Azure DevOps Work Items** tool windows

Detailed guides:

| Guide | |
|---|---|
| [Getting Started](docs/GETTING_STARTED.md) | First-time setup and walkthrough |
| [OAuth Setup](docs/OAUTH_SETUP.md) | Browser-based authentication configuration |
| [Usage Examples](docs/USAGE_EXAMPLES.md) | Common workflows step by step |
| [Changelog](docs/CHANGELOG.md) | Full release history |

---

## Tech Stack

| Component | Version | Purpose |
|---|---|---|
| Kotlin | 2.3.10 | Plugin implementation |
| IntelliJ Platform SDK | 2025.3.3 | IDE integration |
| OkHttp | 5.3.2 | HTTP client with native PATCH support |
| Gson | 2.13.2 | JSON serialization |
| Git4Idea | Bundled | Git operations |

---

## Issues and Feedback

Found a bug or have an idea? Open an issue on [GitHub](https://github.com/Paol0B/AzureDevOps/issues).

Bug reports with reproduction steps are the fastest way to get things fixed. Feature requests are welcome — they shape the roadmap.

---

## License

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for details.

---

<div align="center">

**[Install from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/28889-azure-devops-integration?noRedirect=true)**

</div>
