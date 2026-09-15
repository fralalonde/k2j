package org.example.k2j.gradle

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The vendored FernFlower artifact — a real file shipped inside the plugin jar, not a path into
 * somebody's Gradle transform cache.
 *
 * The decompiler is `java-decompiler.jar` from IntelliJ IDEA 2026.2.1 (class-version 69, so it is
 * only loadable by a Java 25 runtime). It lives on the plugin's own classpath as the resource
 * [RESOURCE_PATH] and is pinned by [SHA256]; the checksum is re-verified at execution time so a
 * corrupt or substituted artifact fails loudly instead of producing silently different output.
 *
 * Canonical pin (also the value a core/ CLI default should adopt):
 * `java-decompiler.jar` (IntelliJ IDEA 2026.2.1), SHA-256
 * `c93a37aeac40b9017838e005fce21230c82ddbb7fbb83b07da9d3f52a10fee94`.
 */
internal object DecompilerArtifact {

    /** Classpath resource holding the vendored jar (inside the plugin jar / resources dir). */
    const val RESOURCE_PATH = "k2j/java-decompiler.jar"

    /** SHA-256 of the vendored `java-decompiler.jar` (IntelliJ IDEA 2026.2.1). */
    const val SHA256 = "c93a37aeac40b9017838e005fce21230c82ddbb7fbb83b07da9d3f52a10fee94"

    /** Human-readable pin description, used in failure messages. */
    const val PIN_DESCRIPTION = "java-decompiler.jar from IntelliJ IDEA 2026.2.1 (class-version 69, needs Java 25)"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) >= 0) {
                    // digest is updated by DigestInputStream
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fails with a `k2j:`-prefixed message when [file] does not match [expectedSha256]. */
    fun verify(file: File, expectedSha256: String) {
        if (!file.isFile) {
            throw GradleException("k2j: decompiler jar not found: ${file.absolutePath}")
        }
        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw GradleException(
                "k2j: decompiler jar checksum mismatch for ${file.absolutePath}\n" +
                    "  expected sha256: $expectedSha256\n" +
                    "  actual   sha256: $actual\n" +
                    "The pinned artifact is $PIN_DESCRIPTION.\n" +
                    "Reinstall the k2j plugin, or override both k2j { decompilerJar = ... } and " +
                    "k2j { decompilerJarSha256 = ... } if you really mean to run a different build."
            )
        }
    }

    /**
     * Resolves the vendored jar to a real file.
     *
     * When the plugin is loaded from a directory (Gradle's `pluginUnderTestMetadata`, IDE run) the
     * resource already is a file and is used directly. When it is inside the plugin jar it is
     * extracted into [cacheDir], keyed by the pinned checksum. Either way the result is verified.
     */
    fun resolveBundled(cacheDir: File): File {
        val url = DecompilerArtifact::class.java.getResource("/$RESOURCE_PATH")
            ?: throw GradleException(
                "k2j: the vendored decompiler jar is missing from the plugin classpath " +
                    "(resource '$RESOURCE_PATH'). The k2j plugin jar looks incomplete - " +
                    "reinstall the plugin, or point k2j { decompilerJar = file(...) } at a java-decompiler.jar."
            )
        val resolved = when (url.protocol) {
            "file" -> File(url.toURI())
            else -> extract(url.openStream(), cacheDir)
        }
        verify(resolved, SHA256)
        return resolved
    }

    private fun extract(source: java.io.InputStream, cacheDir: File): File {
        val targetDir = cacheDir.resolve("k2j/vendored")
        Files.createDirectories(targetDir.toPath())
        val target = targetDir.resolve("java-decompiler-${SHA256.take(16)}.jar")
        if (target.isFile && runCatching { sha256(target) == SHA256 }.getOrDefault(false)) {
            return target
        }
        val temp = Files.createTempFile(targetDir.toPath(), "java-decompiler", ".jar.tmp")
        try {
            source.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            move(temp, target.toPath())
        } catch (t: IOException) {
            Files.deleteIfExists(temp)
            throw GradleException(
                "k2j: could not extract the vendored decompiler jar to ${target.absolutePath}: ${t.message}", t
            )
        }
        return target
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: IOException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
