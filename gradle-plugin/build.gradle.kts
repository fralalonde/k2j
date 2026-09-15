plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

gradlePlugin {
    plugins {
        create("k2j") {
            id = "org.example.k2j"
            implementationClass = "org.example.k2j.gradle.K2jPlugin"
        }
    }
}
