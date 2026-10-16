import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "pl.adrros"
version = "0.8.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("platformLocalPath").orNull?.takeIf { file(it).exists() }
        if (localIde != null) local(localIde) else intellijIdeaCommunity("2026.1")
        bundledPlugins("org.jetbrains.plugins.github", "Git4Idea")
        bundledModules("intellij.platform.collaborationTools")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        // Hooking into the GitHub plugin's review UI means using its internal API on purpose; the verifier
        // still reports every such usage, it just does not fail the build on them.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
        ides { recommended() }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// The IDE distribution ships two plugins that both declare the content module `intellij.vcs.git.frontend`
// (Git and "Git Integration for JetBrains Client"). The running IDE picks one by product mode, the test
// runtime does not, and Git - with GitHub on top of it - stays unloaded. Disabling the client variant in
// the sandbox config resolves the conflict.
val frontendGitPlugin = "com.intellij.jetbrains.client.git"
tasks.withType<PrepareSandboxTask>().configureEach {
    doLast {
        sandboxConfigDirectory.get().asFile.resolve("disabled_plugins.txt").writeText(frontendGitPlugin + "\n")
    }
}
