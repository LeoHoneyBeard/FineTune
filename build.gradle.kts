plugins {
    kotlin("jvm") version "1.9.24" apply false
    id("org.jetbrains.compose") version "1.6.11" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("runDesktop") {
    group = "application"
    description = "Runs the desktop client."
    dependsOn(":app:run")
}

tasks.register("buildDesktop") {
    group = "build"
    description = "Builds the desktop client."
    dependsOn(":app:build")
}
