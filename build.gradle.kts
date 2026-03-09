plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "paol0b"
version = "3.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.13.2")
    // OkHttp for robust HTTP requests with native PATCH support
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
    
    intellijPlatform {
        intellijIdea("2025.3.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Git plugin dependency
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            <h2>Version 3.2 - Polish & UX Improvements</h2>
            
            <h3>Improvements</h3>
            <ul>
                <li><b>SVG Icons Consistency</b> – Updated icon dimensions to 16x16 across all resources for consistent visual design</li>
                <li><b>Commit Button</b> – Added commit button to streamline workflow</li>
                <li><b>PR ToolWindow Enhancement</b> – Improved Pull Request tool window UI and functionality</li>
            </ul>
            
            <hr>
            
            <h2>Version 3.1 - On-Premise & PAT Authentication</h2>
            
            <h3>New Features</h3>
            <ul>
                <li><b>Azure DevOps On-Premise Support</b> – Full integration with self-hosted Azure DevOps Server instances</li>
                <li><b>Personal Access Token (PAT) Authentication</b> – Add PAT accounts with automatic validation and permission verification</li>
            </ul>
            
            <h3>Improvements</h3>
            <ul>
                <li>Enhanced account authentication state tracking</li>
                <li>Better PAT validation with clear permission reporting (Clone, Pull Requests)</li>
                <li>Improved OAuth token refresh logic with fallback error handling</li>
            </ul>
            
            <h3>Bug Fixes</h3>
            <ul>
                <li>Fixed inconsistent PAT status display in account settings (now correctly shows Revoked for invalid tokens)</li>
                <li>Corrected PR review state persistence across sessions</li>
            </ul>
            
            <hr>
            
            <h2>🚀 Version 3.0 - The Complete PR Review & Pipeline Experience</h2>
            
            <h3>✨ Major Features</h3>
            <ul>
                <li><b>Full PR Review in IDE</b> – Review PRs with diff viewer, timeline, comments, and file tree—no browser needed</li>
                <li><b>PR Timeline</b> – Visualize PR history: created, updated, comments, approvals, status changes</li>
                <li><b>Inline Comments</b> – Add feedback directly on code lines</li>
                <li><b>Pipeline Visualization</b> – See stages, jobs, and logs in your IDE with live updates</li>
                <li><b>Pipeline Logs</b> – Stream logs with search and auto-refresh</li>
                <li><b>Better Clone Dialog</b> – Browse organizations and repos with tree view and search</li>
                <li><b>User Avatars</b> – See profile pictures for reviewers and commenters</li>
                <li><b>Enhanced PR Management</b> – Auto-complete with policies, smart branch handling</li>
            </ul>
            
            <h3>🔧 Technical Improvements</h3>
            <ul>
                <li>API client expanded from ~800 to 1,400+ lines; 20+ new endpoints</li>
                <li>Delta log streaming for 70%+ bandwidth savings on logs</li>
                <li>Smart avatar caching to reduce API calls</li>
                <li>Lazy-loaded UI for PRs with 100+ file changes</li>
                <li>OkHttp 5.3.2 for better PATCH support</li>
            </ul>
            
            <h3>🐛 Bug Fixes</h3>
            <ul>
                <li>Comment parsing in complex threaded discussions</li>
                <li>URL handling for repos with spaces</li>
                <li>Token refresh edge cases</li>
                <li>PR list flickering during updates</li>
            </ul>
            
            <p><a href="https://github.com/paol0b/AzureDevOps/blob/main/RELEASE_NOTES_3.0.md">📖 Read full release notes</a></p>
            
            <hr>
            
            <h2>Version 2.2</h2>
            <ul>
                <li>Fix comment status changes</li>
                <li>CommentToolWindow improvements</li>
            </ul>
            
            <h2>Version 2.1</h2>
            <ul>
                <li>Fix PR list 404 errors for repos with spaces</li>
                <li>Improve PR list refresh responsiveness</li>
            </ul>
            
            <h2>Version 2.0</h2>
            <ul>
                <li>OAuth 2.0 authentication (no app registration needed)</li>
                <li>Global account management with auto-refresh</li>
                <li>PR Comments Tool Window with filtering</li>
                <li>Auto-refresh PR list every 30 seconds</li>
            </ul>
            - Fallback Login – If refresh fails, users are prompted to re-authenticate.
            - Token Tracking – Expiration and last refresh times are stored and checked before each operation.
            Version 1.2:
            - Added Changes and Commits tabs to Create Pull Request dialog
            - Shows file changes grouped by directory with change type indicators [A]dded, [M]odified, [D]eleted
            - Displays all commits that will be included in the pull request
            - Changes and commits update automatically when selecting different branches
            - Added Clone Repository integration with Azure DevOps account management
            
            Version 1.1:
            - Added checkbox selection for files in PR review diff
            - Enhanced PR comments management with auto-refresh and feedback
            - Fixed all JetBrains API deprecations for IntelliJ 2025+
            - Added custom Azure DevOps icon
            - Tool window now always visible and positioned on the right
            - Improved PR review workflow with combined diff support
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
