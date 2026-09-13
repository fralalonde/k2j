plugins {
    kotlin("jvm")
    application
}

dependencies {
    // gradle.properties sets kotlin.stdlib.default.dependency=false, so the stdlib must be explicit.
    implementation(kotlin("stdlib"))
    // ASM for bytecode inspection and the @Metadata filter.
    implementation("org.ow2.asm:asm:9.8")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
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

application {
    mainClass.set("com.onomatic.k2j.core.MainKt")
}
