package org.example.k2j.gradle

import java.io.File

/**
 * Finds a Java runtime able to execute the vendored decompiler (class-version 69 -> Java 25+).
 *
 * The previous default was one hard-coded absolute path to this developer's IDE JBR (an
 * `IntelliJ IDEA Ultimate/jbr` directory inside the user's local app data), which is
 * machine-specific and contributes nothing on any other host. Resolution is now an explicit,
 * ordered probe:
 *
 *  1. explicit override (`k2j { runtimeJavaHome = ... }`) - always honoured when it is a usable JDK;
 *  2. IDE JBR installations found generically under `%LOCALAPPDATA%` (Programs/IntelliJ IDEA*,
 *     JetBrains Toolbox), newest first;
 *  3. `JAVA_HOME`.
 *
 * A candidate is preferred when its `release` file reports a version >= [REQUIRED_MAJOR]; if none
 * is >= 25 the first usable candidate is returned and the task rejects it with a clear message.
 */
internal object JavaRuntime {

    /** Class-version 69 (the vendored FernFlower) needs a Java 25 runtime. */
    const val REQUIRED_MAJOR = 25

    /** `bin/java` or `bin/java.exe` of [home], or null when [home] is not a usable JDK/JRE. */
    fun javaExecutable(home: File): File? {
        val candidate = home.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        return if (candidate.isFile) candidate else null
    }

    /** Major version from the JDK's `release` file, or null when it cannot be determined. */
    fun majorVersion(home: File): Int? {
        val release = home.resolve("release")
        if (!release.isFile) return null
        val text = runCatching { release.readText() }.getOrNull() ?: return null
        val match = Regex("""JAVA_VERSION\s*=\s*"([^"]+)"""").find(text) ?: return null
        val parts = match.groupValues[1].split('.', '_', '+', '-')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        // Pre-JDK9 versions read 1.8.0_402.
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    /** JDK candidates from `%LOCALAPPDATA%`, IDE installs first, then JetBrains Toolbox (newest build first). */
    fun ideCandidates(env: Map<String, String> = System.getenv()): List<File> {
        val localAppData = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return emptyList()
        val programs = localAppData.resolve("Programs")
        val candidates = mutableListOf<File>()
        programs.listFiles { f: File -> f.isDirectory && f.name.startsWith("IntelliJ IDEA") }
            ?.sortedBy { it.name }
            ?.forEach { candidates += it.resolve("jbr") }
        val toolboxApps = localAppData.resolve("JetBrains").resolve("Toolbox").resolve("apps")
        toolboxApps.listFiles { f: File -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { app ->
                app.listFiles { f: File -> f.isDirectory && f.name.startsWith("ch-") }
                    ?.sortedBy { it.name }
                    ?.forEach { channel ->
                        channel.listFiles { f: File -> f.isDirectory }
                            ?.sortedByDescending { it.name }
                            ?.forEach { build -> candidates += build.resolve("jbr") }
                    }
            }
        return candidates.filter { it.isDirectory }.distinct()
    }

    /** Ordered probe; [override] wins unconditionally when it is a usable runtime. */
    fun probe(
        override: File? = null,
        env: Map<String, String> = System.getenv()
    ): File? {
        if (override != null && javaExecutable(override) != null) return override
        val candidates = buildList {
            addAll(ideCandidates(env))
            env["JAVA_HOME"]?.takeIf { it.isNotBlank() && override == null }?.let { add(File(it)) }
        }.distinct()
        val usable = candidates.filter { javaExecutable(it) != null }
        return usable.firstOrNull { (majorVersion(it) ?: 0) >= REQUIRED_MAJOR } ?: usable.firstOrNull()
    }

    /** Message used when no runtime at all could be resolved. */
    fun notFoundMessage(): String = buildString {
        append("k2j: no Java $REQUIRED_MAJOR+ runtime found to run the vendored decompiler ")
        append("(${DecompilerArtifact.PIN_DESCRIPTION}).")
        append("\nProbed, in order: k2j { runtimeJavaHome } -> IDE JBRs under %LOCALAPPDATA%\\Programs\\IntelliJ IDEA*\\jbr ")
        append("-> %LOCALAPPDATA%\\JetBrains\\Toolbox\\apps\\*\\ch-*\\*\\jbr -> JAVA_HOME.")
        append("\nSet k2j { runtimeJavaHome = file(\"<a JDK 25 home>\") } explicitly.")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
