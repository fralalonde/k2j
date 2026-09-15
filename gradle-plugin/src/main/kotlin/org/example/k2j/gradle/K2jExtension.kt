package org.example.k2j.gradle

import org.example.k2j.core.Decompiler
import org.example.k2j.core.JavaValidator
import org.example.k2j.core.OutputWriter
import org.example.k2j.core.Surveyor
import org.gradle.api.file.ConfigurableFileCollection
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
     * Where the generated text of every unit that fails a phase is written — one `.java` per class
     * plus a `.failure.txt` sidecar carrying the phase and the reason. Unset (the default) dumps
     * nothing. Set it when a run reports classes the parse gate rejected: the manifest names the
     * class and the error position, and this directory holds the text those positions refer to.
     */
    abstract val dumpFailures: DirectoryProperty

    /**
     * Compile every generated unit with `javax.tools` before writing it, and report a unit javac
     * rejects as a per-class failure (with javac's own file/line/column/message) instead of writing
     * it. Default `false`: the gate stays a parse, which is much cheaper and catches syntax only.
     * Turn it on when the output must build — it is the only gate that sees a semantic defect such as
     * the wildcard-capture error Kotlin's `Map<K, out V>` produces.
     */
    abstract val compileCheck: Property<Boolean>

    /**
     * Extra dependencies for the [compileCheck] gate. The task already compiles against the module's
     * own compile classpath; this adds jars or directories to it (the generated unit's siblings come
     * from the classes root and are added by core). Unset means "nothing beyond the module's own".
     */
    abstract val compileClasspath: ConfigurableFileCollection

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
