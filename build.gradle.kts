import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "app.morphe"
version = "0.9.0"

val studioPath = providers.gradleProperty("studioPath")
    .orElse(providers.environmentVariable("ANDROID_STUDIO_HOME"))

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(studioPath.orNull ?: error("Set ANDROID_STUDIO_HOME or pass -PstudioPath=/path/to/android-studio"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Bundled)
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.jar {
    from("LICENSE") {
        into("META-INF")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}
