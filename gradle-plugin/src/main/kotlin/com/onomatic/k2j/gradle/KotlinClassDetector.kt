package com.onomatic.k2j.gradle

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Cheap, dependency-free check for "is this a Kotlin class file?".
 *
 * The discriminator is the `kotlin/Metadata` annotation, exactly as core's `AsmSurveyor` uses.
 * Core keeps its ASM based implementation private and `org.ow2.asm:asm` is an `implementation`
 * dependency of `:core`, so it is not on this plugin's compile classpath; rather than widen the
 * plugin's dependency surface just to sniff a constant-pool entry, this reads the class file's
 * constant pool directly (no framework, ~40 lines, bounded by the pool count).
 *
 * Only the constant pool is visited: the `Lkotlin/Metadata;` annotation descriptor is a `Utf8`
 * entry there, so no full class parse (and no debug/attributes walk) is required.
 */
internal object KotlinClassDetector {

    private const val METADATA_DESCRIPTOR = "kotlin/Metadata"

    private const val CLASS_MAGIC = 0xCAFEBABE.toInt()

    // JVM constant-pool tags (JVMS 4.4).
    private const val TAG_UTF8 = 1
    private const val TAG_INTEGER = 3
    private const val TAG_FLOAT = 4
    private const val TAG_LONG = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_CLASS = 7
    private const val TAG_STRING = 8
    private const val TAG_FIELDREF = 9
    private const val TAG_METHODREF = 10
    private const val TAG_INTERFACE_METHODREF = 11
    private const val TAG_NAME_AND_TYPE = 12
    private const val TAG_METHOD_HANDLE = 15
    private const val TAG_METHOD_TYPE = 16
    private const val TAG_DYNAMIC = 17
    private const val TAG_INVOKE_DYNAMIC = 18
    private const val TAG_MODULE = 19
    private const val TAG_PACKAGE = 20

    /** True when [file] is a readable class file carrying a `kotlin/Metadata` constant-pool entry. */
    fun hasKotlinMetadata(file: File): Boolean =
        try {
            file.inputStream().use { hasKotlinMetadata(it) }
        } catch (t: IOException) {
            false
        }

    /**
     * True when the class file in [input] carries a `kotlin/Metadata` entry.
     * Unreadable or non-class input is reported as `false`, never as an exception: a directory of
     * mixed files must not blow up the classification.
     */
    fun hasKotlinMetadata(input: InputStream): Boolean {
        val data = DataInputStream(input)
        return try {
            if (data.readInt() != CLASS_MAGIC) return false
            data.readUnsignedShort() // minor_version
            val major = data.readUnsignedShort() // major_version
            if (major < 45) return false
            val poolCount = data.readUnsignedShort()
            var index = 1
            while (index < poolCount) {
                when (val tag = data.readUnsignedByte()) {
                    TAG_UTF8 -> {
                        val length = data.readUnsignedShort()
                        val bytes = ByteArray(length)
                        data.readFully(bytes)
                        // The pool entry is the annotation *descriptor* (`Lkotlin/Metadata;`), but
                        // match the bare name too so either shape is recognised.
                        if (String(bytes, Charsets.UTF_8).contains(METADATA_DESCRIPTOR)) return true
                    }
                    TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> data.skipBytes(2)
                    TAG_METHOD_HANDLE -> data.skipBytes(3)
                    TAG_INTEGER, TAG_FLOAT, TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF,
                    TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> data.skipBytes(4)
                    TAG_LONG, TAG_DOUBLE -> {
                        // Eight bytes and *two* pool slots.
                        data.skipBytes(8)
                        index++
                    }
                    else -> return false // Unknown tag: give up conservatively.
                }
                index++
            }
            false
        } catch (t: EOFException) {
            false
        } catch (t: IOException) {
            false
        }
    }
}
