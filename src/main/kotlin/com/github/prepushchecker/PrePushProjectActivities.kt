package com.github.prepushchecker

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Kotlin [ProjectActivity] bridges that delegate to the existing Java startup logic.
 *
 * IntelliJ 2026.1 no longer dispatches to Java implementations of the Kotlin
 * `suspend fun ProjectActivity.execute(project: Project)` interface (earlier IDEs only
 * logged a "Migrate ... to ProjectActivity" warning but still ran them). Implementing
 * the activity in Kotlin restores invocation across all supported IDE versions while
 * keeping the existing Java logic unchanged.
 */
class PrePushProjectActivities {

    class GitHookInstallerActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            GitHookInstaller.runStartup(project)
        }
    }

    class ExternalPushErrorLoaderActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            ExternalPushErrorLoader.runStartup(project)
        }
    }

    class PrePushLocalServerActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            PrePushLocalServer.runStartup(project)
        }
    }

    class CompilationWarmupServiceActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            CompilationWarmupService.runStartup(project)
        }
    }
}
