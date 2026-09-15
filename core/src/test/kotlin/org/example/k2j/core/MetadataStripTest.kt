package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypeReference

private const val KOTLIN_METADATA = "Lkotlin/Metadata;"

/**
 * The kotlin.Metadata strip, asserted where it actually happens: on the bytes the decompiler is fed.
 *
 * Three layers of evidence:
 *  1. byte level — [MetadataStripper] only ever drops that one annotation, and hands an already
 *     metadata-free class back untouched;
 *  2. structural level — the rewritten bytes are read back with ASM and compared field by field
 *     against the original (members, signatures, class/field/method/parameter/type annotations with
 *     their values, instructions, stack map frames, debug info), and the JVM is asked to load the
 *     result;
 *  3. end to end — a real corpus unit comes back without the `@Metadata(...)` / `d1` / `d2` blob,
 *     and with the strip turned off the blob comes back, which is what proves the strip is the
 *     thing that removed it.
 */
class MetadataStripTest {

    // ---------------------------------------------------------------- fixtures and helpers

    private fun java25Home(): Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    private fun decompilerJar(): Path? = listOf(
        Path.of("D:/Work/k2j/gradle-plugin/src/main/resources/k2j/java-decompiler.jar"),
        Path.of(
            "D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar"
        )
    ).firstOrNull { Files.isRegularFile(it) }

    private fun corpus(): Path {
        val corpus = TestSupport.corpusClasses
        Assumptions.assumeTrue(corpus != null, "corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")
        return corpus!!
    }

    private fun newDecompiler(stripMetadata: Boolean = true): FernFlowerDecompiler {
        val jar = decompilerJar()
        val jbr = java25Home()
        Assumptions.assumeTrue(jar != null, "vendored java-decompiler.jar not found at the pinned path")
        Assumptions.assumeTrue(jbr != null, "Java 25 runtime (IDE JBR) not found at the default path")
        return FernFlowerDecompiler(jar!!, jbr!!, stripMetadata = stripMetadata)
    }

    private class ByteClassLoader : ClassLoader() {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun loadClass(bytes: ByteArray, binaryName: String): Class<*> = ByteClassLoader().define(binaryName, bytes)

    private fun hashTree(root: Path): Map<String, String> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList().associate { file ->
                file.toString() to digest.digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
            }
        }
    }

    // ------------------------------------------------------- 1. byte level, on the rewrite itself

    @Test
    fun `the strip drops only the kotlin Metadata annotation and leaves every other part of the class as it was`() {
        val original = annotatedFixture()
        val stripped = MetadataStripper.stripKotlinMetadata(original)

        assertTrue(MetadataStripper.hasKotlinMetadata(original), "the fixture must carry kotlin/Metadata")
        assertFalse(MetadataStripper.hasKotlinMetadata(stripped), "the rewritten class must not carry kotlin/Metadata")
        assertTrue(stripped.size < original.size, "the annotation and its d1/d2 arrays must be gone from the bytes")

        assertEquals(
            ClassStructure.of(original).withoutKotlinMetadata(),
            ClassStructure.of(stripped),
            "the rewritten class must differ from the original in the kotlin/Metadata annotation and nothing else"
        )
    }

    @Test
    fun `the rewritten fixture is structurally interesting so the comparison above cannot pass vacuously`() {
        val structure = ClassStructure.of(MetadataStripper.stripKotlinMetadata(annotatedFixture()))

        assertEquals("fixture/AnnotatedSample", structure.name)
        assertEquals("java/lang/Object", structure.superName)
        assertEquals(listOf("java/io/Serializable"), structure.interfaces)
        assertEquals("Lfixture/AnnotatedSample<TT;>;", structure.signature, "the generic signature must survive")
        assertTrue(structure.sourceFile!!.startsWith("AnnotatedSample.kt"), structure.sourceFile)
        assertTrue(structure.innerClasses.any { it.startsWith("fixture/AnnotatedSample\$Nested") }, structure.innerClasses.toString())
        assertTrue(structure.nestMembers.contains("fixture/AnnotatedSample\$Nested"), structure.nestMembers.toString())
        assertTrue(
            structure.fields.any { it.startsWith("values Ljava/util/List;") && it.contains("Ljava/util/List<Ljava/lang/String;>;") },
            structure.fields.toString()
        )
        assertTrue(structure.methods.any { it.startsWith("compute (Ljava/lang/String;I)") }, structure.methods.toString())
        assertTrue(structure.classAnnotations.any { it.startsWith("Ljava/lang/Deprecated;") }, "a non-Metadata class annotation must survive: ${structure.classAnnotations}")
        assertTrue(structure.classAnnotations.any { it.startsWith("Ljava/lang/SuppressWarnings;") }, "an invisible class annotation must survive: ${structure.classAnnotations}")
        assertFalse(structure.classAnnotations.any { it.startsWith(KOTLIN_METADATA) })
        assertTrue(structure.classAnnotations.any { it.contains("since=9") }, "annotation values must be decoded: ${structure.classAnnotations}")
        assertTrue(structure.parameterAnnotations.any { it.startsWith("compute p=0 Ljava/lang/Deprecated;") }, structure.parameterAnnotations.toString())
        assertTrue(structure.methodAnnotations.any { it.startsWith("compute Ljava/lang/Deprecated;") }, structure.methodAnnotations.toString())
        assertTrue(structure.typeAnnotations.any { it.contains("Ljava/lang/AssertionError;") }, structure.typeAnnotations.toString())
        assertTrue(structure.code.any { it.contains("frame ") }, "a stack map frame must be present in the fixture: ${structure.code}")
        assertTrue(
            structure.code.any { it.contains("methodInsn=${Opcodes.INVOKEVIRTUAL}") },
            "the fixture's code must be recorded: ${structure.code}"
        )
    }

    @Test
    fun `an already metadata free class is handed back as the very same array`() {
        // A javac-produced class: real bytecode, no kotlin.Metadata anywhere.
        val dir = Files.createTempDirectory("k2j-java-class")
        val classFile = TestSupport.compileJavaClass(dir, "javafix", "PlainJava")
        val bytes = Files.readAllBytes(classFile)
        assertFalse(MetadataStripper.hasKotlinMetadata(bytes), "javac output must not carry kotlin/Metadata")

        val result = MetadataStripper.stripKotlinMetadata(bytes)
        assertSame(bytes, result, "a metadata-free class must not be re-encoded at all")
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `a class that only mentions the descriptor in its constant pool is re-encoded without being changed in meaning`() {
        // The fast path keys on the descriptor bytes, which a plain string constant can also contain:
        // that class is routed through ASM although it declares no such annotation. Meaning must not move.
        val original = classWithDescriptorStringConstant()
        assertFalse(MetadataStripper.hasKotlinMetadata(original), "this fixture must not declare the annotation")

        val rewritten = MetadataStripper.stripKotlinMetadata(original)
        assertNotSame(original, rewritten, "the fast path cannot prove absence here, so ASM rewrites it")
        assertEquals(ClassStructure.of(original), ClassStructure.of(rewritten), "the rewrite must be meaning-preserving")
        assertFalse(MetadataStripper.hasKotlinMetadata(rewritten))
        loadClass(rewritten, "fixture.DescriptorMention")
    }

    @Test
    fun `nested and inner classes carry the annotation too and every class file is stripped`() {
        val corpus = corpus()
        val relatives = listOf(
            "accept/nested/Outer\$Nested.class",
            "accept/nested/Outer\$Inner.class",
            "accept/nested/Outer\$makeLocal\$Local.class",
            "accept/nested/Outer.class",
            "accept/inheritance/AbstractBase.class",
            "accept/enums/PlainEnum.class"
        )
        for (relative in relatives) {
            val file = corpus.resolve(relative)
            Assumptions.assumeTrue(Files.isRegularFile(file), "corpus class not built: $file")
            val original = Files.readAllBytes(file)
            assertTrue(MetadataStripper.hasKotlinMetadata(original), "$relative must carry kotlin/Metadata")
            val stripped = MetadataStripper.stripKotlinMetadata(original)
            assertFalse(MetadataStripper.hasKotlinMetadata(stripped), "$relative must come out without it")
            assertEquals(
                ClassStructure.of(original).withoutKotlinMetadata(),
                ClassStructure.of(stripped),
                "$relative must be unchanged apart from the annotation"
            )
        }
    }

    // ------------------------------------------- 2. structural read-back of the rewritten bytes

    @Test
    fun `the rewritten class resolves, loads and keeps its members`() {
        val original = annotatedFixture()
        val stripped = MetadataStripper.stripKotlinMetadata(original)

        // Constant pool resolves: this_class, super_class and the interfaces all read back (a broken
        // pool would throw here or come back wrong).
        val reader = ClassReader(stripped)
        assertEquals(ClassReader(original).className, reader.className, "the pool must still resolve this_class")
        assertEquals(ClassReader(original).superName, reader.superName, "the pool must still resolve super_class")
        assertArrayEquals(ClassReader(original).interfaces, reader.interfaces)

        val loaded = loadClass(stripped, "fixture.AnnotatedSample")
        assertEquals("fixture.AnnotatedSample", loaded.name)
        assertTrue(loaded.declaredFields.any { it.name == "values" }, loaded.declaredFields.map { it.name }.toString())
        assertTrue(
            loaded.declaredMethods.any { it.name == "compute" && it.parameterCount == 2 },
            loaded.declaredMethods.map { it.name }.toString()
        )
        assertEquals(
            listOf("java.lang.Deprecated"),
            loaded.declaredAnnotations.map { it.annotationClass.java.name }.sorted(),
            "the surviving class annotation must be readable, and kotlin.Metadata must not be there"
        )
    }

    // ------------------------------------------------------------- 3. end to end, forked JVM

    @Test
    fun `the generated text of a real corpus unit carries no Metadata blob, no d1 and no d2`() {
        val corpus = corpus()
        val decompiler = newDecompiler()

        val result = decompiler.decompile(
            listOf(
                corpus.resolve("accept/plain/Single.class"),
                corpus.resolve("accept/nested/Outer.class"),
                corpus.resolve("accept/nested/Outer\$Nested.class"),
                corpus.resolve("accept/nested/Outer\$Inner.class")
            ),
            emptyList()
        )
        assertTrue(result.isNotEmpty(), "the corpus unit must decompile")

        for ((className, text) in result) {
            assertFalse(text.contains("@Metadata"), "$className must not carry @Metadata: ${text.take(400)}")
            assertFalse(text.contains("kotlin.Metadata"), "$className must not mention kotlin.Metadata: ${text.take(400)}")
            assertFalse(text.contains("d1 = {"), "$className must not carry the d1 blob: ${text.take(400)}")
            assertFalse(text.contains("d2 = {"), "$className must not carry the d2 blob: ${text.take(400)}")
        }

        // Positive control: the unit is still the unit.
        val single = result.getValue("accept.plain.Single")
        assertTrue(single.contains("public final class Single"), single)
        assertTrue(single.contains("getValue()"), single)
        assertTrue(single.contains("answer()"), single)
        val outer = result.getValue("accept.nested.Outer")
        assertTrue(outer.contains("class Nested"), outer)
        assertTrue(outer.contains("class Inner"), outer)
    }

    @Test
    fun `disabling the strip brings the Metadata annotation and its d1 and d2 arrays back`() {
        val corpus = corpus()
        val single = corpus.resolve("accept/plain/Single.class")
        val strippedText = newDecompiler().decompile(listOf(single), emptyList()).getValue("accept.plain.Single")
        val keptText = newDecompiler(stripMetadata = false).decompile(listOf(single), emptyList()).getValue("accept.plain.Single")

        assertFalse(strippedText.contains("@Metadata"), strippedText.take(400))
        assertTrue(keptText.contains("@Metadata"), "with the strip off the annotation must come back: ${keptText.take(400)}")
        assertTrue(keptText.contains("d1 = {"), keptText.take(400))
        assertTrue(keptText.contains("d2 = {"), keptText.take(400))
        assertTrue(keptText.contains("import kotlin.Metadata;"), keptText.take(400))
    }

    @Test
    fun `the forked command carries the ASM classpath always and the off switch only when the strip is off`() {
        val on = newDecompiler()
        val onCommand = on.buildCommand(
            Path.of("C:/jbr/bin/java.exe"), Path.of("D:/cache/dec.jar"), Path.of("D:/core/classes"), Path.of("D:/tmp/report.tsv")
        )
        assertEquals(5, onCommand.size, "argv stays java -cp <cp> <runner> <report>: $onCommand")
        assertFalse(onCommand.any { it.startsWith("-D") }, "nothing to disable when the strip is on: $onCommand")
        val onClasspath = onCommand[onCommand.indexOf("-cp") + 1].split(java.io.File.pathSeparator)
        assertEquals(3, onClasspath.size, "decompiler jar, runner entry and the ASM artifact: $onClasspath")
        assertEquals(on.asmJar().toString(), onClasspath[2], "the third entry must be the ASM artifact itself")
        assertTrue(Files.isRegularFile(on.asmJar()), "the ASM artifact must exist: ${on.asmJar()}")

        val off = newDecompiler(stripMetadata = false)
        val offCommand = off.buildCommand(
            Path.of("C:/jbr/bin/java.exe"), Path.of("D:/cache/dec.jar"), Path.of("D:/core/classes"), Path.of("D:/tmp/report.tsv")
        )
        assertEquals(6, offCommand.size, "the off switch is one extra argv entry: $offCommand")
        assertTrue(
            offCommand.contains("-D${MetadataStripper.ENABLED_PROPERTY}=false"),
            "the fork must be told the strip is off: $offCommand"
        )
        val offClasspath = offCommand[offCommand.indexOf("-cp") + 1].split(java.io.File.pathSeparator)
        assertEquals(3, offClasspath.size, "ASM travels with the fork either way: $offClasspath")
        assertTrue(
            offCommand.indexOf("-D${MetadataStripper.ENABLED_PROPERTY}=false") < offCommand.indexOf("-cp"),
            "the JVM option must precede -cp, or the JVM treats it as the main class: $offCommand"
        )
    }

    @Test
    fun `the strip is on unless the property says otherwise`() {
        val key = MetadataStripper.ENABLED_PROPERTY
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertTrue(MetadataStripper.enabledFromProperties(), "the strip is on by default")
            System.setProperty(key, "false")
            assertFalse(MetadataStripper.enabledFromProperties(), "false turns it off")
            System.setProperty(key, "FALSE")
            assertFalse(MetadataStripper.enabledFromProperties(), "the comparison is case-insensitive")
            System.setProperty(key, "true")
            assertTrue(MetadataStripper.enabledFromProperties(), "true keeps it on")
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    fun `the rewrite is in memory only - the classes root is not touched by a decompilation`() {
        val corpus = corpus()
        val before = hashTree(corpus)
        assertTrue(before.isNotEmpty(), "the corpus must have class files to hash")

        newDecompiler().decompile(
            listOf(corpus.resolve("accept/plain/Single.class"), corpus.resolve("accept/nested/Outer\$Nested.class")),
            emptyList()
        )

        assertEquals(before, hashTree(corpus), "no class file under the classes root may change, appear or disappear")
    }

    // ---------------------------------------------------------------------------- test fixtures

    /** Builds a class shaped like Kotlin output, carrying a second annotation of each kind. */
    private fun annotatedFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            "fixture/AnnotatedSample",
            "Lfixture/AnnotatedSample<TT;>;",
            "java/lang/Object",
            arrayOf("java/io/Serializable")
        )
        writer.visitSource("AnnotatedSample.kt", null)
        writer.visitNestMember("fixture/AnnotatedSample\$Nested")
        writer.visitInnerClass(
            "fixture/AnnotatedSample\$Nested", "fixture/AnnotatedSample", "Nested", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
        )

        // The blob under test, shaped like kotlinc emits it: mv is an int array, d1/d2 are strings.
        val metadata = writer.visitAnnotation(KOTLIN_METADATA, true)
        metadata.visit("k", 1)
        metadata.visit("xi", 48)
        metadata.visitArray("mv").apply {
            visit(null, 2); visit(null, 4); visit(null, 0); visitEnd()
        }
        metadata.visitArray("d1").apply {
            visit(null, "\u0000\u0012Value"); visitEnd()
        }
        metadata.visitArray("d2").apply {
            visit(null, "Lfixture/AnnotatedSample;"); visit(null, "values"); visitEnd()
        }
        metadata.visitEnd()

        writer.visitAnnotation("Ljava/lang/Deprecated;", true).visit("since", "9")
        writer.visitAnnotation("Ljava/lang/SuppressWarnings;", false).visitArray("value").apply {
            visit(null, "unchecked"); visitEnd()
        }

        writer.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
            "values",
            "Ljava/util/List;",
            "Ljava/util/List<Ljava/lang/String;>;",
            null
        )!!.apply {
            visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd()
            visitAnnotation("Lfixture/annotations/Unresolved;", false).visitEnd()
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "compute",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            arrayOf("java/io/IOException")
        ).apply {
            visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd()
            // A visible type annotation on the return type: survives the strip like any other.
            visitTypeAnnotation(
                TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value,
                null,
                "Ljava/lang/AssertionError;",
                true
            )?.visitEnd()
            visitParameterAnnotation(0, "Ljava/lang/Deprecated;", true).visitEnd()
            visitParameterAnnotation(1, "Lfixture/annotations/Unresolved;", false).visitEnd()
            visitParameter("text", 0)
            visitParameter("count", 0)
            visitCode()
            val elseLabel = Label()
            visitVarInsn(Opcodes.ILOAD, 2)
            visitJumpInsn(Opcodes.IFLE, elseLabel)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitLabel(elseLabel)
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitLdcInsn("n=")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A class whose constant pool holds the metadata descriptor as a plain string, but no annotation. */
    private fun classWithDescriptorStringConstant(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "fixture/DescriptorMention", null, "java/lang/Object", null
        )
        writer.visitSource("DescriptorMention.kt", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "describe", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(KOTLIN_METADATA)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}

// ------------------------------------------------------------------ structural read-back machinery

/**
 * Everything of a class file ASM can read back, as comparable text: the header, the members with
 * their signatures, class/field/method/parameter/type annotations with their values, the source,
 * inner-class and nest attributes, and the full code (instructions, stack map frames, line numbers,
 * local variables) of every method. Reading the rewritten bytes into this structure and comparing it
 * with the original is what pins "nothing but the annotation changed".
 */
private data class ClassStructure(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val version: Int,
    val access: Int,
    val signature: String?,
    val sourceFile: String?,
    val outerClass: String?,
    val nestHost: String?,
    val nestMembers: List<String>,
    val innerClasses: List<String>,
    val attributes: List<String>,
    val fields: List<String>,
    val methods: List<String>,
    val classAnnotations: List<String>,
    val methodAnnotations: List<String>,
    val parameterAnnotations: List<String>,
    val typeAnnotations: List<String>,
    val code: List<String>
) {
    /** The same structure with the kotlin.Metadata class annotation taken out. */
    fun withoutKotlinMetadata(): ClassStructure =
        copy(classAnnotations = classAnnotations.filterNot { it.startsWith(KOTLIN_METADATA) })

    companion object {
        /** Reads [bytes] with ASM and reduces them to comparable text; throws if the bytes are broken. */
        fun of(bytes: ByteArray): ClassStructure {
            val record = Record()
            ClassReader(bytes).accept(record, 0)
            return ClassStructure(
                name = record.name,
                superName = record.superName,
                interfaces = record.interfaces,
                version = record.version,
                access = record.access,
                signature = record.signature,
                sourceFile = record.sourceFile,
                outerClass = record.outerClass,
                nestHost = record.nestHost,
                nestMembers = record.nestMembers,
                innerClasses = record.innerClasses,
                attributes = record.attributes,
                fields = record.fields,
                methods = record.methods,
                classAnnotations = record.classAnnotations,
                methodAnnotations = record.methodAnnotations,
                parameterAnnotations = record.parameterAnnotations,
                typeAnnotations = record.typeAnnotations,
                code = record.code
            )
        }
    }
}

/** Collects every visited value as text; the annotation text lands in [sink] on visitEnd. */
private class AnnotationRecord(private val sink: MutableList<String>, private val label: String) : AnnotationVisitor(Opcodes.ASM9) {
    private val text = StringBuilder(label)

    override fun visit(name: String?, value: Any?) {
        text.append(" ${name ?: "<array>"}=$value")
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        text.append(" $name=$descriptor.$value")
    }

    override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
        text.append(" $name=@$descriptor")
        return AnnotationRecord(mutableListOf(), "")
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        text.append(" $name[")
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                text.append("$value,")
            }

            override fun visitEnd() {
                text.append("]")
            }
        }
    }

    override fun visitEnd() {
        sink += text.toString()
    }
}

private class Record : ClassVisitor(Opcodes.ASM9) {
    var name = ""
    var superName: String? = null
    var interfaces: List<String> = emptyList()
    var version = 0
    var access = 0
    var signature: String? = null
    var sourceFile: String? = null
    var outerClass: String? = null
    var nestHost: String? = null
    val nestMembers = mutableListOf<String>()
    val innerClasses = mutableListOf<String>()
    val attributes = mutableListOf<String>()
    val fields = mutableListOf<String>()
    val methods = mutableListOf<String>()
    val classAnnotations = mutableListOf<String>()
    val methodAnnotations = mutableListOf<String>()
    val parameterAnnotations = mutableListOf<String>()
    val typeAnnotations = mutableListOf<String>()
    val code = mutableListOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.version = version
        this.access = access
        this.name = name
        this.signature = signature
        this.superName = superName
        this.interfaces = interfaces?.toList() ?: emptyList()
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFile = "$source debug=$debug"
    }

    override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
        outerClass = "$owner $name $descriptor"
    }

    override fun visitNestHost(nestHost: String) {
        this.nestHost = nestHost
    }

    override fun visitNestMember(nestMember: String) {
        nestMembers += nestMember
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        innerClasses += "$name outer=$outerName inner=$innerName access=$access"
    }

    override fun visitAttribute(attribute: Attribute) {
        attributes += attribute.type
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
        AnnotationRecord(classAnnotations, "$descriptor visible=$visible")

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
        val line = StringBuilder("$name $descriptor signature=$signature access=$access value=$value")
        fields += line.toString()
        val index = fields.size - 1
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                AnnotationRecord(fields, "$index:$descriptor visible=$visible")

            override fun visitAttribute(attribute: Attribute) {
                fields[index] = fields[index] + " attribute=${attribute.type}"
            }
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val methodName = name
        methods += "$methodName $descriptor signature=$signature access=$access exceptions=${exceptions?.toList()}"
        return object : MethodVisitor(Opcodes.ASM9) {
            private val labels = HashMap<Label, Int>()

            private fun id(label: Label): Int = labels.getOrPut(label) { labels.size }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                AnnotationRecord(methodAnnotations, "$methodName $descriptor visible=$visible")

            override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor =
                AnnotationRecord(parameterAnnotations, "$methodName p=$parameter $descriptor visible=$visible")

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: org.objectweb.asm.TypePath?,
                descriptor: String,
                visible: Boolean
            ): AnnotationVisitor = AnnotationRecord(typeAnnotations, "$methodName typeRef=$typeRef $descriptor visible=$visible")

            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
                code += "$methodName frame type=$type local=${local?.toList()} stack=${stack?.toList()}"
            }

            override fun visitInsn(opcode: Int) {
                code += "$methodName insn=$opcode"
            }

            override fun visitIntInsn(opcode: Int, operand: Int) {
                code += "$methodName intInsn=$opcode $operand"
            }

            override fun visitVarInsn(opcode: Int, variable: Int) {
                code += "$methodName varInsn=$opcode $variable"
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                code += "$methodName typeInsn=$opcode $type"
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                code += "$methodName fieldInsn=$opcode $owner.$name:$descriptor"
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                code += "$methodName methodInsn=$opcode $owner.$name$descriptor itf=$isInterface"
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any
            ) {
                code += "$methodName indy=$name$descriptor $bootstrapMethodHandle ${bootstrapMethodArguments.toList()}"
            }

            override fun visitJumpInsn(opcode: Int, label: Label) {
                code += "$methodName jump=$opcode L${id(label)}"
            }

            override fun visitLabel(label: Label) {
                code += "$methodName label=L${id(label)}"
            }

            override fun visitLdcInsn(value: Any) {
                code += "$methodName ldc=$value"
            }

            override fun visitIincInsn(variable: Int, increment: Int) {
                code += "$methodName iinc=$variable $increment"
            }

            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
                code += "$methodName tableSwitch=$min..$max default=L${id(dflt)} ${labels.map { id(it) }}"
            }

            override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
                code += "$methodName lookupSwitch default=L${id(dflt)} ${keys.toList()} ${labels.map { id(it) }}"
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                code += "$methodName multiANewArray=$descriptor $numDimensions"
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                code += "$methodName tryCatch=L${id(start)}..L${id(end)} handler=L${id(handler)} type=$type"
            }

            override fun visitLocalVariable(
                name: String,
                descriptor: String,
                signature: String?,
                start: Label,
                end: Label,
                index: Int
            ) {
                code += "$methodName localVar=$name:$descriptor signature=$signature ${id(start)}..${id(end)} index=$index"
            }

            override fun visitLineNumber(line: Int, start: Label) {
                code += "$methodName line=$line at=L${id(start)}"
            }

            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                code += "$methodName maxs=$maxStack,$maxLocals"
            }

            override fun visitAttribute(attribute: Attribute) {
                code += "$methodName attribute=${attribute.type}"
            }
        }
    }
}
