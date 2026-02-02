plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "paol0b"
version = "2.2"

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
    implementation("com.google.code.gson:gson:2.11.0")
    // OkHttp for robust HTTP requests with native PATCH support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Git plugin dependency
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Version 2.2:
            - Fix comment change status
            - CommentToolWindow improvements
            Version 2.1:
            - Fix Get PRs return 404 on repo name with spaces
            - Improve refresh of PRs Window
            Version 2.0:
            - Global Accounts – Manage all Azure DevOps accounts in one place with status, expiration, and actions (add, remove, refresh, re-login).
            - Project Setup – Select an account per project with auto-detected organization and connection testing.
            - Auto Refresh – Tokens automatically refresh via Microsoft Entra ID (OAuth 2.0).
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
