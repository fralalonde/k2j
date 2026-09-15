plugins {
    kotlin("jvm") version "2.4.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        javaParameters.set(true)
    }
}
