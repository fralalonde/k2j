package org.example.k2j.core

import java.nio.file.Path

/**
 * Owns every filesystem side effect: writing generated units, the manifest, and — only when the
 * manifest proves it safe — deleting survey sources. Callers never write or delete directly.
 */
interface OutputWriter {
    fun write(outputs: Map<String, String>, outputRoot: Path): List<ConvertedClass>
    fun writeManifest(manifest: ConversionManifest, outputRoot: Path): Path
    fun deleteVerifiedSources(sources: List<Path>): List<String>
}
