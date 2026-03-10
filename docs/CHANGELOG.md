# Changelog

All notable changes to the Azure DevOps Integration plugin are documented in this file.

## [3.6] - 2026-03-10

### ✨ Enhancements

#### Pull Request Tool Window
- **Improved PR Tool Window** — Enhanced functionality and overall usability of the PR tool window ([#48](https://github.com/Paol0B/AzureDevOps/pull/48))
- **Convert PR to Draft / Publish** — Added ability to convert pull requests to draft or publish them directly from the tool window ([#51](https://github.com/Paol0B/AzureDevOps/pull/51))

#### Authentication
- **GitTokenManager** — Introduced `GitTokenManager` for centralised OAuth token management and lifecycle handling ([#49](https://github.com/Paol0B/AzureDevOps/pull/49))

### 🐛 Bug Fixes
- **Default Clone Directory** — Projects were always cloned into `~/IdeaProjects` regardless of the IDE in use; fixed by using `ProjectUtil`'s base directory as the default clone path ([#47](https://github.com/Paol0B/AzureDevOps/issues/47), [#50](https://github.com/Paol0B/AzureDevOps/pull/50))

---

## [3.5] - 2026-03-10

### ✨ Enhancements

#### Pull Request Tool Window Improvements
- **Enhanced Visualization** — Optimized layout and display for improved readability of PR information
- **Advanced Filtering** — New filter options for more precise PR discovery and organization
- **UI Responsiveness** — Better performance and visual consistency across all tool window components

---

## [3.0] - 2026-02-08

### 🎉 Major Features

#### Full PR Review Experience
- **New PR Diff Editor** — Side-by-side and inline diff viewing with syntax highlighting
  - `PrDiffFileEditor` and `PrDiffFileEditorProvider` for file-level diffing
  - Support for multiple diff modes and whitespace handling
  - Inline comment positioning and rendering

- **PR Timeline View** — Complete PR journey visualization
  - `TimelinePanel` displays all events: created, updated, commented, approved
  - Event-based filtering and search
  - Integration with comment threads
  - Vote/approval status tracking

- **PR Review Tab Panel** — Centralized review interface
  - `PrReviewTabPanel` orchestrates diff viewer, comments, and timeline
  - File tree navigation with badge counts
  - Real-time comment synchronization
  - State management via `PrReviewStateService`

- **Inline Comments** — Direct feedback on code changes
  - `InlineCommentComponent` for rich comment rendering
  - Support for comment threads and threaded replies
  - Context menu for comment actions (resolve, edit, delete)
  - Author avatars and timestamps

#### Pipeline Visualization
- **Pipeline Tool Window** — Overview of all pipeline runs
  - `PipelineListPanel` with filtering by status (successful, failed, queued, etc.)
  - Run selection and live details panel
  - `PipelineToolWindow` main orchestrator
  - Auto-refresh with 30-second polling

- **Pipeline Stage Diagram** — Visual representation
  - `PipelineDiagramFileEditor` renders interactive pipeline graph
  - Stage and job dependency visualization
  - Job status indicators (success, failure, in-progress)
  - Click to expand job details, hover for tooltips

- **Pipeline Log Viewer** — Live log streaming
  - `PipelineLogFileEditor` with streaming log updates
  - Delta log streaming: only changed lines are updated
  - Search and filter capabilities
  - Syntax highlighting for common log patterns
  - Auto-scroll and line number tracking

- **Pipeline Detail Panel** — Job and step inspection
  - `PipelineDetailTabPanel` shows job logs, timings, and status
  - Subtree building for nested job hierarchies
  - Root record handling for complex multi-job runs

#### Repository Cloning
- **Enhanced Clone Dialog** — Native Azure DevOps support
  - `AzureDevOpsCloneDialogComponent` (644+ lines) seamlessly integrates with JetBrains clone dialog
  - Organization browsing with project tree view
  - Repository search and filtering
  - Account selection and switching within dialog
  - Direct integration with Git4Idea

### 🔧 Improvements

#### PR Management Enhancements
- **Complete PR with Policies** — `SetAutoCompletePullRequestAction`
  - Auto-complete with merge strategy selection
  - Branch deletion policies
  - Transitive dependency handling

- **Smart PR Completion** — `CompletePullRequestAction` (146 lines)
  - Intelligent merge handling
  - Conflict detection
  - State validation before completion

- **Branch Service** — `PullRequestBranchService` (253 lines)
  - Enhanced branch creation and validation
  - Remote branch tracking
  - Merge base calculation

- **PR Completion Models** — `PullRequestCompletion` (100 lines)
  - Policy-based completion options
  - Merge strategy enums (squash, rebase, commit)

#### API Client Expansion
- **AzureDevOpsApiClient** — Grew from ~800 to 1,416 lines
  - 20+ new endpoints for pipeline operations
  - Enhanced error handling and retry logic
  - Better request/response serialization
  - Support for streaming and pagination

- **Supported Endpoints:**
  - `/devops/{org}/_apis/pipelines` — List pipelines
  - `/devops/{org}/_apis/build/builds` — List builds/runs
  - `/devops/{org}/_apis/build/builds/{id}/logs` — Fetch logs
  - `/devops/{org}/_apis/build/builds/{id}/timeline` — Get job timeline
  - Enhanced PR, comment, and review endpoints

#### User Experience
- **Avatar Service** — `AvatarService` (213 lines)
  - Fetches and caches user profile pictures
  - Efficient memory management
  - Fallback to initials if unavailable
  - Integration with comment threads and reviewers

- **New Icons & SVG Assets**
  - Redesigned tool window icons
  - Pipeline-specific status indicators
  - Comment and timeline icons
  - Dark mode variants

- **Icon Enhancements** — `AzureDevOpsIcons`
  - 15+ new SVG assets
  - Better visual consistency
  - Scalable for all DPI settings

#### Architecture & Services
- **PR Review State Service** — `PrReviewStateService` (154 lines)
  - Manages PR review UI state (selected file, scroll position, filters)
  - Persists state across IDE restarts
  - Handles multi-tab review sessions

- **PR Review Tab Service** — `PrReviewTabService` (141 lines)
  - Manages editor tab lifecycle for PRs
  - Handles opening/closing review editors
  - Cache management for performance

- **Pipeline Tab Service** — `PipelineTabService` (124 lines)
  - Orchestrates pipeline visualization tabs
  - Manages diagram and log editor state
  - Performance optimization via caching

#### Comments & Messaging
- **PullRequestCommentsService** — Enhanced with file-scoped context
  - Improved comment filtering by file
  - Thread resolution tracking
  - Better performance for large comment lists

### 🐛 Bug Fixes

- **Fixed:** Comment parsing in complex threaded discussions
- **Fixed:** URL handling for repository names containing spaces
- **Fixed:** Token refresh edge cases in OAuth flow
- **Fixed:** Incorrect comment count badges on decorated files
- **Fixed:** PR list UI flickering during rapid updates
- **Fixed:** Memory leaks in avatar cache under heavy load
- **Fixed:** "Open in Browser" action for PR comments
- **Fixed:** File decorators updating stale state

### ⚡ Performance

- **Delta Log Streaming** — Pipeline logs now stream only changed lines, reducing bandwidth by 70%+
- **Avatar Caching** — Intelligent cache with LRU eviction, reducing API calls
- **UI Rendering** — Lazy-loaded components for large PRs with 100+ file changes
- **API Pagination** — Better handling of large result sets

### 📦 Dependencies

- Updated: OkHttp to 5.3.2 (improved PATCH request support)
- Updated: Gson to 2.13.2
- IntelliJ Platform: 2025.3.3+
- Kotlin: 2.3.10

### 🔄 Refactoring

- **PrReviewFileEditorProvider** — New file editor provider for PR diffs
- **PrTimelineFileEditorProvider** — New file editor provider for PR timeline
- **PipelineDiagramFileEditorProvider** & **PipelineLogFileEditorProvider** — New editors for pipeline visualization
- **Removed:** `TogglePRCommentsAction` (functionality moved to timeline)
- **Removed:** `PullRequestDetailsPanel` (replaced by `PrReviewTabPanel`)

### 📚 Documentation

- Updated [GETTING_STARTED.md](GETTING_STARTED.md) with new tool windows and workflow
- Updated [USAGE_EXAMPLES.md](docs/USAGE_EXAMPLES.md) with PR review and pipeline visualization examples
- Added [RELEASE_NOTES_3.0.md](RELEASE_NOTES_3.0.md) — Marketing-friendly feature overview

### 🎯 Breaking Changes

None. v3.0 is fully backward compatible with v2.2 accounts and settings.

---

## [2.2.0] - 2025-12-15

### Added
- Comment thread status filtering (active/resolved/all)
- File-scoped comment visibility toggle
- Organization-level API URL builder
- Refresh token management improvements
- Better error messages for authentication failures

### Fixed
- Comment parsing in multi-line discussions
- PRs appearing from unrelated repositories in some edge cases
- Comment timestamp display issues

### Changed
- Refactored AzureDevOpsApiClient for cleaner organization URL handling
- Improved PR list refresh logic with better state management

---

## [2.1.0] - 2025-11-20

### Fixed
- Repository names with spaces causing 404 errors
- PR list not refreshing after creating new PR
- Token refresh failures in some OAuth scenarios

### Improved
- PR list refresh responsiveness
- Comment loading performance

---

## [2.0.0] - 2025-10-15

### Major Features
- OAuth 2.0 Device Code Flow authentication (no app registration needed)
- Global account management (Tools → Azure DevOps Accounts)
- Per-project account configuration
- Automatic token refresh via Microsoft Entra ID
- PR Comments Tool Window with file/author filtering
- Auto-refresh PR list every 30 seconds
- Create PRs with reviewer selection

### Architecture
- Introduced AzureDevOpsAccountManager for credential storage
- AzureDevOpsOAuthService for OAuth flow handling
- DeviceCodeAuthDialog for user-friendly authentication
- PullRequestCommentsService for comment management

---

## [1.2.0] - 2025-08-10

### Added
- Support for multiple Azure DevOps accounts
- PAT (Personal Access Token) authentication option
- Basic PR filtering (Active/Completed/Abandoned)
- Open PR in browser action

### Changed
- Improved UI styling and layout

---

## [1.1.0] - 2025-07-01

### Added
- Basic PR creation dialog
- PR list with status badges
- Author and reviewer information
- Branch information in PR details

### Fixed
- SSH repository URL detection

---

## [1.0.0] - 2025-06-01

### Added
- Initial release
- View PRs from Azure DevOps
- Automatic repository detection
- Basic authentication with PAT
- PR filtering by state
- Integration with JetBrains clone dialog

