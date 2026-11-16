plugins {
    id("java")
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier("1.410")
    }
    testImplementation("junit:junit:4.13.2")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    processResources {
        val pluginVersion = providers.gradleProperty("pluginVersion")
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("**/version.properties") { expand("version" to pluginVersion.get()) }
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
            // recommended() resolves IC, which stops at 2025.2, but Marketplace also verifies the unified
            // IDEA 2025.3+ builds. Point at a local install to cover them without a large download:
            //   ./gradlew verifyPlugin -PverifyLocalIde="/Applications/IntelliJ IDEA.app"
            providers.gradleProperty("verifyLocalIde").orNull?.let { local(it) }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
