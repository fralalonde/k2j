package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * [InterfaceDefaultSynthesis] against class files this test writes itself, so the shapes the real
 * corpus cannot produce — the `IFace$DefaultImpls` static delegation of a JVM-default-disabled Kotlin
 * build, a holder that is not there at all, and a `Signature` the pass cannot render — are exercised
 * on the same code the pipeline runs.
 *
 * Every refusal is asserted as **byte identity**: the pass is only allowed to change a unit it can
 * prove, and a unit it cannot prove must be handed to the gate exactly as it arrived.
 */
class InterfaceDefaultSynthesisTest {

    @TempDir
    lateinit var tmp: Path

    private val classesRoot: Path get() = tmp.resolve("classes")

    private val kotlinStdlib: Path? = TestSupport.cachedJar("kotlin-stdlib-2.4.0.jar")
    private val annotations: Path? = TestSupport.cachedJar("annotations-13.0.jar")

    // -- the fixtures ------------------------------------------------------------------------------

    /**
     * `host/Host`: an interface with an abstract `label()` and the `Host$DefaultImpls` holder an
     * older Kotlin build generates beside it.
     */
    private fun writeHost(withHolder: Boolean): Path {
        val iface = writeClass("host/Host", Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE, "java/lang/Object") { writer ->
            if (withHolder) writer.visitInnerClass("host/Host\$DefaultImpls", "host/Host", "DefaultImpls", Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL)
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT, "label", "()Ljava/lang/String;", null, null
            ).visitEnd()
        }
        if (withHolder) {
            writeClass(
                "host/Host\$DefaultImpls",
                Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
                "java/lang/Object"
            ) { writer ->
                writer.visitInnerClass(
                    "host/Host\$DefaultImpls", "host/Host", "DefaultImpls",
                    Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL
                )
                writer.visitMethod(
                    Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
                    "label",
                    "(Lhost/Host;)Ljava/lang/String;",
                    null,
                    null
                ).apply {
                    visitCode()
                    visitLdcInsn("default")
                    visitInsn(Opcodes.ARETURN)
                    visitMaxs(1, 1)
                    visitEnd()
                }
            }
        }
        return iface
    }

    /**
     * `host/Impl`: implements [Host] and delegates `label()` statically into the holder — the shape a
     * Kotlin build with the JVM-default mode disabled emits.
     */
    private fun writeImpl(
        holder: String,
        signature: String? = null,
        interfaces: List<String> = listOf("host/Host")
    ): Path = writeClass(
        "host/Impl",
        Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
        "java/lang/Object",
        interfaces
    ) { writer ->
        writer.visitMethod(Opcodes.ACC_PUBLIC, "label", "()Ljava/lang/String;", signature, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, holder, "label", "(Lhost/Host;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
    }

    private fun writeClass(
        internalName: String,
        access: Int,
        superName: String?,
        interfaces: List<String> = emptyList(),
        build: (ClassWriter) -> Unit
    ): Path {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, access, internalName, null, superName, interfaces.toTypedArray())
        build(writer)
        writer.visitEnd()
        val target = classesRoot.resolve("$internalName.class")
        Files.createDirectories(target.parent)
        Files.write(target, writer.toByteArray())
        return target
    }

    private val textOfImpl = "package host;\n\npublic final class Impl implements Host {\n}"

    private fun compile(text: String): List<String> {
        val classpath = listOfNotNull(kotlinStdlib, annotations)
        Assumptions.assumeTrue(classpath.size == 2, "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache")
        return TestSupport.compileJavaSources(mapOf("host/Impl.java" to text), listOf(classesRoot) + classpath)
    }

    // -- the `IFace$DefaultImpls` shape ------------------------------------------------------------

    @Test
    fun `a static delegation into the interface's DefaultImpls holder is appended and compiles`() {
        writeHost(withHolder = true)
        val impl = writeImpl("host/Host\$DefaultImpls")

        val synthesized = InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl)

        assertTrue(
            synthesized.contains(
                "   public java.lang.String label() {\n      return host.Host.DefaultImpls.label(this);\n   }\n"
            ),
            "the member must delegate into the holder the bytecode called:\n$synthesized"
        )
        assertEquals(
            "package host;\n\npublic final class Impl implements Host {\n\n" +
                "   public java.lang.String label() {\n      return host.Host.DefaultImpls.label(this);\n   }\n}",
            synthesized,
            "the unit is the generated text with the member appended: nothing else may move"
        )
        assertEquals(emptyList<String>(), JavacValidator().validate("Impl.java", synthesized), synthesized)
        assertEquals(emptyList<String>(), compile(synthesized), "the appended member must compile")
    }

    @Test
    fun `an interface with no DefaultImpls refuses the unit byte for byte`() {
        writeHost(withHolder = false)
        val impl = writeImpl("host/Host\$DefaultImpls")

        assertSame(textOfImpl, InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl))
    }

    @Test
    fun `a Signature with a type variable refuses the unit byte for byte`() {
        writeHost(withHolder = true)
        val impl = writeImpl("host/Host\$DefaultImpls", signature = "()TT;")

        assertSame(textOfImpl, InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl))
    }

    @Test
    fun `a class that already declares the member is returned as the same instance`() {
        writeHost(withHolder = true)
        val impl = writeImpl("host/Host\$DefaultImpls")
        val declared = textOfImpl.replace("{\n}", "{\n   public java.lang.String label() {\n      return \"impl\";\n   }\n}")

        assertSame(declared, InterfaceDefaultSynthesis.synthesize(impl, classesRoot, declared))
    }

    @Test
    fun `a class with no interface at all is returned as the same instance`() {
        val lonely = writeClass(
            "host/Lonely",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
            "java/lang/Object"
        ) { writer ->
            writer.visitMethod(Opcodes.ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null).apply {
                visitCode()
                visitLdcInsn("x")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        val text = "package host;\n\npublic class Lonely {\n}"

        assertSame(text, InterfaceDefaultSynthesis.synthesize(lonely, classesRoot, text))
    }

    @Test
    fun `a class file that cannot be read is returned as the same instance`() {
        writeHost(withHolder = true)
        val missing = classesRoot.resolve("host/Absent.class")

        assertSame(textOfImpl, InterfaceDefaultSynthesis.synthesize(missing, classesRoot, textOfImpl))
    }

    @Test
    fun `the pass is idempotent`() {
        writeHost(withHolder = true)
        val impl = writeImpl("host/Host\$DefaultImpls")

        val once = InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl)
        assertSame(once, InterfaceDefaultSynthesis.synthesize(impl, classesRoot, once))
    }

    @Test
    fun `a nested holder is spelled as a member of its interface`() {
        writeHost(withHolder = true)
        val impl = writeImpl("host/Host\$DefaultImpls")

        val synthesized = InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl)
        assertTrue(
            synthesized.contains("host.Host.DefaultImpls.label(this)"),
            "the nested holder must be spelled as a member class:\n$synthesized"
        )
        assertTrue(
            !synthesized.contains("Host\$DefaultImpls"),
            "a `\$` in a rendered name would not resolve from Java source:\n$synthesized"
        )
    }

    @Test
    fun `a parameterised, non-void method keeps its parameters and return type`() {
        writeClass(
            "host/Calc",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE,
            "java/lang/Object"
        ) { writer ->
            writer.visitInnerClass("host/Calc\$DefaultImpls", "host/Calc", "DefaultImpls", Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC)
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT, "add", "(IJ)J", null, null
            ).visitEnd()
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT, "describe", "(Ljava/lang/String;)Ljava/lang/String;", null, null
            ).visitEnd()
        }
        writeClass(
            "host/Calc\$DefaultImpls",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object"
        ) { writer ->
            writer.visitInnerClass("host/Calc\$DefaultImpls", "host/Calc", "DefaultImpls", Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC)
            writer.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "add", "(Lhost/Calc;IJ)J", null, null).apply {
                visitCode()
                visitInsn(Opcodes.LCONST_0)
                visitInsn(Opcodes.LRETURN)
                visitMaxs(2, 4)
                visitEnd()
            }
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "describe", "(Lhost/Calc;Ljava/lang/String;)Ljava/lang/String;", null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        }
        val impl = writeClass(
            "host/CalcImpl",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object",
            listOf("host/Calc")
        ) { writer ->
            writer.visitMethod(Opcodes.ACC_PUBLIC, "add", "(IJ)J", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ILOAD, 1)
                visitVarInsn(Opcodes.LLOAD, 2)
                visitMethodInsn(Opcodes.INVOKESTATIC, "host/Calc\$DefaultImpls", "add", "(Lhost/Calc;IJ)J", false)
                visitInsn(Opcodes.LRETURN)
                visitMaxs(4, 4)
                visitEnd()
            }
            writer.visitMethod(
                Opcodes.ACC_PUBLIC, "describe", "(Ljava/lang/String;)Ljava/lang/String;", null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC, "host/Calc\$DefaultImpls", "describe", "(Lhost/Calc;Ljava/lang/String;)Ljava/lang/String;", false
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
        }
        val text = "package host;\n\npublic final class CalcImpl implements Calc {\n}"

        val synthesized = InterfaceDefaultSynthesis.synthesize(impl, classesRoot, text)

        assertTrue(
            synthesized.contains("   public long add(int arg0, long arg1) {\n      return host.Calc.DefaultImpls.add(this, arg0, arg1);\n   }\n"),
            "a primitive parameter and a long return must survive the rendering:\n$synthesized"
        )
        assertTrue(
            synthesized.contains("   public java.lang.String describe(java.lang.String arg0) {\n      return host.Calc.DefaultImpls.describe(this, arg0);\n   }\n"),
            synthesized
        )
        assertEquals(emptyList<String>(), JavacValidator().validate("CalcImpl.java", synthesized), synthesized)
        val classpath = listOfNotNull(kotlinStdlib, annotations)
        Assumptions.assumeTrue(classpath.size == 2, "kotlin-stdlib / annotations not in the Gradle module cache")
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(mapOf("host/CalcImpl.java" to synthesized), listOf(classesRoot) + classpath),
            "the appended members must compile against the class files"
        )
    }

    @Test
    fun `a signature every declaration agrees with is kept, and a narrowed one is erased`() {
        // `Agreed`: the interface and the bridge both spell `List<String>`, so the generic spelling is
        // written; `Narrowed`: the interface spells `List<Object>` where the bridge spells
        // `List<String>`, which Java's invariant generics refuse — so the erasure is written instead.
        writeInterfaceWithDefault("Agreed", "Ljava/lang/String;", "Ljava/lang/String;")
        writeInterfaceWithDefault("Narrowed", "Ljava/lang/Object;", "Ljava/lang/String;")

        val agreed = writeClass(
            "host/AgreedImpl",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object",
            listOf("host/Agreed")
        ) { writer ->
            writer.visitMethod(
                Opcodes.ACC_PUBLIC, "label", "()Ljava/util/List;",
                "()Ljava/util/List<Ljava/lang/String;>;", null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESTATIC, "host/Agreed\$DefaultImpls", "label", "(Lhost/Agreed;)Ljava/util/List;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        val narrowed = writeClass(
            "host/NarrowedImpl",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object",
            listOf("host/Narrowed")
        ) { writer ->
            writer.visitMethod(
                Opcodes.ACC_PUBLIC, "label", "()Ljava/util/List;",
                "()Ljava/util/List<Ljava/lang/String;>;", null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC, "host/Narrowed\$DefaultImpls", "label", "(Lhost/Narrowed;)Ljava/util/List;", false
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }

        val agreedText = InterfaceDefaultSynthesis.synthesize(
            agreed, classesRoot, "package host;\n\npublic final class AgreedImpl implements Agreed {\n}"
        )
        assertTrue(
            agreedText.contains("   public java.util.List<java.lang.String> label() {"),
            "a spelling every declaration agrees with must be written as it stands:\n$agreedText"
        )

        val narrowedText = InterfaceDefaultSynthesis.synthesize(
            narrowed, classesRoot, "package host;\n\npublic final class NarrowedImpl implements Narrowed {\n}"
        )
        assertTrue(
            narrowedText.contains("   public java.util.List label() {"),
            "a narrowed spelling is exactly what javac refuses, so the erasure must be written:\n$narrowedText"
        )
        val classpath = listOfNotNull(kotlinStdlib, annotations)
        Assumptions.assumeTrue(classpath.size == 2, "kotlin-stdlib / annotations not in the Gradle module cache")
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(
                mapOf(
                    "host/AgreedImpl.java" to agreedText,
                    "host/NarrowedImpl.java" to narrowedText
                ),
                listOf(classesRoot) + classpath
            ),
            "both spellings must compile"
        )
    }

    /** An interface with one `List`-returning abstract method, its holder, and matching signatures. */
    private fun writeInterfaceWithDefault(name: String, declarationElement: String, bridgeElement: String) {
        writeClass(
            "host/$name",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE,
            "java/lang/Object"
        ) { writer ->
            writer.visitInnerClass("host/$name\$DefaultImpls", "host/$name", "DefaultImpls", Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC)
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT, "label", "()Ljava/util/List;",
                "()Ljava/util/List<$declarationElement>;", null
            ).visitEnd()
        }
        writeClass(
            "host/$name\$DefaultImpls",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object"
        ) { writer ->
            writer.visitInnerClass("host/$name\$DefaultImpls", "host/$name", "DefaultImpls", Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC)
            writer.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "label", "(Lhost/$name;)Ljava/util/List;",
                "(Lhost/$name;)Ljava/util/List<$bridgeElement>;", null
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
    }

    @Test
    fun `a body that is not a pure delegation is never touched`() {
        writeHost(withHolder = true)
        val impl = writeClass(
            "host/Impl",
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
            "java/lang/Object",
            listOf("host/Host")
        ) { writer ->
            writer.visitMethod(Opcodes.ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESTATIC, "host/Host\$DefaultImpls", "label", "(Lhost/Host;)Ljava/lang/String;", false)
                visitInsn(Opcodes.DUP) // an extra instruction: not a pure delegation
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
        }

        assertSame(textOfImpl, InterfaceDefaultSynthesis.synthesize(impl, classesRoot, textOfImpl))
    }
}
