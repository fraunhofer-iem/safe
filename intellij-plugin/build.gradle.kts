import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "de.fraunhofer.iem"
version = "1.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        bundledPlugin("com.intellij.java")
        plugin("org.intellij.qodana:261.22158.299")

        // Add necessary plugin dependencies for compilation here, example:
       // bundledPlugin("org.intellij.qodana")
        // https://mvnrepository.com/artifact/com.charleskorn.kaml/kaml

    }
    implementation("org.yaml:snakeyaml:2.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // The IntelliJ Platform Gradle plugin's `prepareTest` injects scaffolding that pulls
    // in JUnit 4 classes; expose them so the test executor can start, even though our own
    // tests use JUnit Jupiter.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    //implementation("org.jetbrains.qodana:plugin:2025.1.1")
   // implementation("org.intellij.markdown:markdown:0.5.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }

        changeNotes = """
      Initial version
    """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    test {
        useJUnitPlatform()
    }
}
