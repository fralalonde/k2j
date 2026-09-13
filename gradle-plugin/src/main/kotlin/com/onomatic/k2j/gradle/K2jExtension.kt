package com.onomatic.k2j.gradle

import com.onomatic.k2j.core.Decompiler
import com.onomatic.k2j.core.JavaValidator
import com.onomatic.k2j.core.OutputWriter
import com.onomatic.k2j.core.Surveyor
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * User-facing configuration for the k2j conversion task.
 *
 * Property objects are created by Gradle's instrumentation; [K2jPlugin] fills in conventions.
 * The four core collaborators are pluggable via [surveyor]/[decompiler]/[validator]/[writer]:
 * tests substitute fakes, and the plugin supplies the real core implementations by default.
 *
 * [decompilerJar] and [runtimeJavaHome] are *overrides*: left unset, the task resolves the
 * vendored artifact from the plugin's own classpath ([DecompilerArtifact]) and probes for a
 * Java 25 runtime ([JavaRuntime]). Nothing here references a path belonging to one particular
 * developer's machine, and nothing references Gradle's transform cache.
 */
abstract class K2jExtension {

    /** Packages to restrict conversion to; empty means every Kotlin class under the classes dirs. */
    abstract val packages: ListProperty<String>

    /** Where generated `.java` files and the manifest are written. */
    abstract val outputRoot: DirectoryProperty

    /** Delete survey sources whose classes were all converted and validated. */
    abstract val deleteSources: Property<Boolean>

    /**
     * Override for the FernFlower jar. Unset means "use the vendored artifact that ships inside
     * the k2j plugin". Set it only to run a different build of the decompiler.
     */
    abstract val decompilerJar: RegularFileProperty

    /**
     * Expected SHA-256 of [decompilerJar]. When set, the resolved jar is verified against it and a
     * mismatch fails the task. The vendored artifact is always verified against
     * [VENDORED_DECOMPILER_JAR_SHA256], with or without this property.
     */
    abstract val decompilerJarSha256: Property<String>

    /**
     * Override for the Java 25 home used to fork the decompiler JVM. Unset means "probe for one"
     * (IDE JBR locations, then `JAVA_HOME`).
     */
    abstract val runtimeJavaHome: DirectoryProperty

    /** Core collaborator: enumerates conversion targets and deletion candidates. */
    abstract val surveyor: Property<Surveyor>

    /** Core collaborator: turns class files into Java text. */
    abstract val decompiler: Property<Decompiler>

    /** Core collaborator: parse-gates generated units. */
    abstract val validator: Property<JavaValidator>

    /** Core collaborator: owns all filesystem side effects. */
    abstract val writer: Property<OutputWriter>

    companion object {
        /** Classpath resource of the vendored FernFlower jar, shipped inside the k2j plugin jar. */
        const val VENDORED_DECOMPILER_JAR: String = DecompilerArtifact.RESOURCE_PATH

        /** SHA-256 of the vendored FernFlower jar — the canonical pin for the whole pipeline. */
        const val VENDORED_DECOMPILER_JAR_SHA256: String = DecompilerArtifact.SHA256

        /** Java major version required to execute the vendored decompiler (class-version 69). */
        const val REQUIRED_JAVA_MAJOR: Int = JavaRuntime.REQUIRED_MAJOR
    }
}
