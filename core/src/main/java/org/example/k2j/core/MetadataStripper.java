package org.example.k2j.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Removes the {@code kotlin.Metadata} annotation from a class file <em>in memory</em>, and only that
 * annotation.
 *
 * <p>Why here, and why the bytes: the decompiler is fed class bytes, so the only place a decoration
 * can happen without touching the filesystem is on the way in. FernFlower's
 * {@code IBytecodeProvider} is exactly that seam (see {@link FernFlowerRunner#main}), and the bytes
 * it hands back are read from disk, rewritten here, and returned — nothing is written back to the
 * classes root, and the class files on disk keep their metadata.
 *
 * <p>Why strip at all: the decompiled unit used to open with a {@code @Metadata(mv = ..., d1 =
 * {...}, d2 = {...})} blob — a Kotlin-compiler artifact with no Java meaning, whose {@code d1}/{@code
 * d2} string arrays carry the whole original Kotlin signature table. It is noise in the generated
 * Java (and the one thing the generated text imported from {@code kotlin.Metadata}).
 *
 * <p>How the rest of the class is kept intact:
 * <ul>
 *   <li>{@link ClassReader#accept} is called with flags {@code 0}, so nothing is skipped — debug
 *       info, frames and unknown attributes all travel through.
 *   <li>The {@link ClassWriter} is built <em>on the reader</em> ({@code new ClassWriter(reader, 0)})
 *       with no {@code COMPUTE_MAXS}/{@code COMPUTE_FRAMES}: the constant pool is copied wholesale
 *       and every method is copied verbatim (the visit flags are never set because no
 *       {@link ClassVisitor} method is overridden other than {@code visitAnnotation}), so no frame,
 *       no max stack/local, no instruction offset and no member order is recomputed.
 *   <li>A class whose bytes never mention the descriptor is returned as the very same array — an
 *       already metadata-free class is not even re-encoded.
 * </ul>
 */
public final class MetadataStripper {

    /** The descriptor the whole feature keys on. */
    public static final String KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;";

    /**
     * System property read by the forked JVM that runs the decompiler: set to {@code false} to hand
     * FernFlower the untouched bytes. Exists so the strip is provably the thing that removes the
     * annotation (see {@code MetadataStripTest}) and as an escape hatch in the field.
     */
    public static final String ENABLED_PROPERTY = "k2j.stripKotlinMetadata";

    private static final byte[] DESCRIPTOR_BYTES =
            KOTLIN_METADATA_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII);

    private MetadataStripper() {
    }

    /** Whether the forked decompiler should strip; on unless {@link #ENABLED_PROPERTY} is false. */
    public static boolean enabledFromProperties() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /**
     * Returns {@code classBytes} without its {@code kotlin.Metadata} annotation, or the very same
     * array when the class does not carry it. Never writes anything anywhere.
     */
    public static byte[] stripKotlinMetadata(byte[] classBytes) {
        Objects.requireNonNull(classBytes, "classBytes");
        // Cheap pre-check: no mention of the descriptor means no annotation to drop, so the class
        // file is handed over byte-for-byte (no re-encoding at all).
        if (!mentionsDescriptor(classBytes)) {
            return classBytes;
        }
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new MetadataDroppingClassVisitor(writer), 0);
        return writer.toByteArray();
    }

    /** True when the class declares the annotation (used by tests and diagnostics). */
    public static boolean hasKotlinMetadata(byte[] classBytes) {
        Objects.requireNonNull(classBytes, "classBytes");
        boolean[] found = {false};
        new ClassReader(classBytes).accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (KOTLIN_METADATA_DESCRIPTOR.equals(descriptor)) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean mentionsDescriptor(byte[] classBytes) {
        outer:
        for (int i = 0; i <= classBytes.length - DESCRIPTOR_BYTES.length; i++) {
            for (int j = 0; j < DESCRIPTOR_BYTES.length; j++) {
                if (classBytes[i + j] != DESCRIPTOR_BYTES[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Drops exactly one thing: a class-level {@code Lkotlin/Metadata;} annotation. Returning
     * {@code null} from {@code visitAnnotation} is ASM's contract for "not interested in this
     * annotation" — verified against the pinned ASM 9.8 with a metadata verdict that contains arrays
     * ({@code mv}, {@code d1}, {@code d2}) so a value-skipping bug cannot pass silently.
     *
     * <p>Member annotations, parameter annotations and every other class annotation are forwarded
     * untouched; the class-level descriptor is the only one compared, so a user annotation that
     * merely happens to be named {@code Metadata} elsewhere is not touched.
     */
    private static final class MetadataDroppingClassVisitor extends ClassVisitor {

        MetadataDroppingClassVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (KOTLIN_METADATA_DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            return super.visitAnnotation(descriptor, visible);
        }
    }
}
