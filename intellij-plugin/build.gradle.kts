import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
        testFramework(TestFrameworkType.Platform)
        plugin("org.intellij.qodana:261.22158.299")

        // Add necessary plugin dependencies for compilation here, example:
       // bundledPlugin("org.intellij.qodana")
        // https://mvnrepository.com/artifact/com.charleskorn.kaml/kaml

    }
    implementation("org.yaml:snakeyaml:2.0")
    implementation("com.openai:openai-java:4.30.0")
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
}
