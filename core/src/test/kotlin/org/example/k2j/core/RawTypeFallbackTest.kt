package org.example.k2j.core

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [RawTypeFallback] — the compile gate's second chance.
 *
 * Two of the fixtures below are the **real** texts and the **real** diagnostics of the two units on
 * `target module` that named this family, copied out of the run's own failure dump (the
 * `build/k2j-failures-final` directory of `.failure.txt` sidecars, which is why the line and column
 * in the diagnostics still point at the right token of the text): `ActivityTaskItemContext` (shape
 * (a), the clash) and `AccessGateDevice` (shape (b), the incompatible supertypes *and* the refusal
 * the fallback must make of it, because neither interface javac named is in that unit's clause).
 *
 * Each test is an axis that fails when the behaviour is reverted:
 *  - the located declaration is rewritten **and nothing else is** (a line-level diff, a `>` count,
 *    and the text before/after the deleted range) — a transform that reformats, or that rewrites a
 *    second declaration, fails;
 *  - a unit that cannot be placed is **refused** (null), which is what leaves it byte-identical;
 *  - at the gate, a refused unit keeps the diagnostic of the *first* attempt and is not written,
 *    and a repaired unit is converted with the rewrite recorded in the manifest's warnings.
 */
class RawTypeFallbackTest {

    @TempDir
    lateinit var tmp: Path

    // -- the real module's texts ----------------------------------------------------------------

    /** `com.example.app.task.activity.ActivityTaskItemContext`, verbatim from the dump. */
    private val activityTaskItemContext = """
        package com.example.app.task.activity;

        import com.fasterxml.jackson.annotation.JsonSubTypes;
        import com.fasterxml.jackson.annotation.JsonTypeInfo;
        import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
        import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
        import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
        import com.example.app.work.ItemWorkContext;
        import com.example.app.work.WorkContextClass;
        import java.util.List;
        import org.jetbrains.annotations.NotNull;

        @JsonTypeInfo(
           use = Id.NAME,
           include = As.PROPERTY,
           property = "_class"
        )
        @JsonSubTypes({@Type(ActivityTaskItemList.class), @Type(ActivityTaskItem.class)})
        public interface ActivityTaskItemContext extends ItemWorkContext {
           @NotNull
           List<ActivityTaskItem> getItems();

           public static final class DefaultImpls {
              /** @deprecated */
              @Deprecated
              @NotNull
              public static WorkContextClass getContextClass(@NotNull ActivityTaskItemContext ${'$'}this) {
                 return ${'$'}this.getContextClass();
              }
           }
        }
    """.trimIndent() + "\n"

    /** The diagnostic the real run recorded for that unit — javac's own words, position included. */
    private val activityTaskItemContextDiagnostic =
        "ActivityTaskItemContext.java:21:27: getItems() in " +
            "com.example.app.task.activity.ActivityTaskItemContext clashes with getItems() in " +
            "com.example.app.work.ItemWorkContext\n" +
            "  return type java.util.List<com.example.app.task.activity.ActivityTaskItem> " +
            "is not compatible with java.util.List<com.example.app.item.ItemQuantityEntry>"

    /** `com.example.app.equipment.type.access.AccessGateDevice`, verbatim from the dump. */
    private val accessGateDevice = """
        package com.example.app.equipment.type.access;

        import com.example.app.device.actuator.GateActuatorVariant;
        import com.example.app.device.actuator.IActuatorDeviceProperties;
        import com.example.app.device.actuator.IGateDeviceVariant;
        import kotlin.enums.EnumEntries;
        import kotlin.enums.EnumEntriesKt;
        import org.jetbrains.annotations.NotNull;

        public enum AccessGateDevice implements IAccessActuatorVariant, IGateDeviceVariant {
           INSTANCE(GateActuatorVariant.INSTANCE, AccessGateProperties.class);
           @NotNull
           private final IGateDeviceVariant device;
           @NotNull
           private final Class<? extends IActuatorDeviceProperties> type;

           // ${'$'}FF: synthetic field
           private static final EnumEntries ${'$'}ENTRIES = EnumEntriesKt.enumEntries(${'$'}values());

           private AccessGateDevice(IGateDeviceVariant device, Class<? extends IActuatorDeviceProperties> type) {
              this.device = device;
              this.type = type;
           }

           @NotNull
           public IGateDeviceVariant getDevice() {
              return this.device;
           }
        }
    """.trimIndent() + "\n"

    private val accessGateDeviceDiagnostic =
        "AccessGateDevice.java:10:8: types com.example.app.device.IEquipmentDeviceVariant and " +
            "com.example.app.device.actuator.IGateDeviceVariant are incompatible;\n" +
            "  enum com.example.app.equipment.type.access.AccessGateDevice inherits unrelated " +
            "defaults for getBaseAlias() from types com.example.app.device.IEquipmentDeviceVariant " +
            "and com.example.app.device.actuator.IGateDeviceVariant"

    // -- shape (a): the method clash -------------------------------------------------------------

    @Test
    fun `the real clashing interface is repaired by making exactly the named return type raw`() {
        // The embedded text is verbatim: the position javac recorded in the real run is the position
        // of `getItems` in it.
        assertEquals(21 to 27, position(activityTaskItemContext, "getItems"))
        val repair = RawTypeFallback.repair(
            "ActivityTaskItemContext.java",
            activityTaskItemContext,
            listOf(activityTaskItemContextDiagnostic)
        )
        assertTrue(repair != null, "the real unit must be repairable: javac named the declaration in it")

        // The named declaration — and only it — lost the type arguments of its return type.
        assertEquals(
            listOf(
                "getItems() return type `List<ActivityTaskItem>` -> `List`"
            ),
            repair!!.notes,
            "the rewrite must be reported as exactly the named declaration"
        )
        assertTrue(
            repair.text.contains("   List getItems();"),
            "the return type javac named must be raw:\n${repair.text}"
        )
        assertFalse(
            repair.text.contains("List<ActivityTaskItem>"),
            "no other spelling of that type may be touched:\n${repair.text}"
        )
        // Everything else survives byte for byte — the annotations, the imports, the nested type and
        // the deprecated delegating member are all still there, in place.
        assertTrue(repair.text.contains("@NotNull\n   List getItems();"))
        assertTrue(repair.text.contains("public static WorkContextClass getContextClass(@NotNull ActivityTaskItemContext ${'$'}this)"))
        assertTrue(repair.text.contains("import com.fasterxml.jackson.annotation.JsonSubTypes;"))
    }

    @Test
    fun `shape a is a minimal single-declaration deletion, asserted against the text itself`() {
        val repair = RawTypeFallback.repair(
            "ActivityTaskItemContext.java",
            activityTaskItemContext,
            listOf(activityTaskItemContextDiagnostic)
        )!!
        val expected = activityTaskItemContext.replace("List<ActivityTaskItem> getItems()", "List getItems()")
        assertNotEquals(activityTaskItemContext, expected, "the fixture must actually contain the shape")
        assertEquals(
            expected,
            repair.text,
            "the repair must be the text with the named declaration's type arguments removed and " +
                "nothing else changed"
        )
        assertMinimal(activityTaskItemContext, repair.text, "getItems", 1)
    }

    // -- shape (b): the incompatible supertypes ---------------------------------------------------

    @Test
    fun `the named supertypes are made raw in the clause, and the clause only`() {
        val text = """
            package accept.rawtypes;

            import java.util.List;

            public abstract class ConflictingProvider implements Provider<Entry>, OtherProvider<Item> {
            }
        """.trimIndent() + "\n"
        val diagnostic =
            "ConflictingProvider.java:5:17: types OtherProvider<Item> and Provider<Entry> are incompatible;\n" +
                "  both define provide(), but with unrelated return types"

        val repair = RawTypeFallback.repair("ConflictingProvider.java", text, listOf(diagnostic))
        assertTrue(repair != null, "the unit's own clause names both interfaces with their arguments")
        assertEquals(
            listOf(
                "Provider made raw in the supertype clause: `Provider<Entry>` -> `Provider`",
                "OtherProvider made raw in the supertype clause: `OtherProvider<Item>` -> `OtherProvider`"
            ),
            repair!!.notes
        )
        assertEquals(
            text.replace("implements Provider<Entry>, OtherProvider<Item>", "implements Provider, OtherProvider"),
            repair.text
        )
        assertMinimal(text, repair.text, "implements", 2)
    }

    // -- the refusals ----------------------------------------------------------------------------

    @Test
    fun `the real incompatible-supertypes unit is refused because neither named interface is in its clause`() {
        assertNull(
            RawTypeFallback.repair(
                "AccessGateDevice.java",
                accessGateDevice,
                listOf(accessGateDeviceDiagnostic)
            ),
            "javac named `IEquipmentDeviceVariant` (a transitive supertype) and `IGateDeviceVariant` " +
                "(spelled without arguments), so there is nothing this unit's own clause can make raw"
        )
    }

    @Test
    fun `a named interface that is in the clause without arguments has nothing to strip`() {
        val text = """
            package accept.rawtypes;

            public enum StorageCategory implements IAssetCategory, IPositionCategory {
               INSTANCE;
            }
        """.trimIndent() + "\n"
        val diagnostic =
            "StorageCategory.java:3:8: types accept.rawtypes.IAssetCategory and " +
                "accept.rawtypes.IPositionCategory are incompatible;\n" +
                "  enum accept.rawtypes.StorageCategory inherits unrelated defaults for getTypeClass() " +
                "from types accept.rawtypes.IAssetCategory and accept.rawtypes.IPositionCategory"

        assertNull(
            RawTypeFallback.repair("StorageCategory.java", text, listOf(diagnostic)),
            "a refusal must be a refusal: making an already-argument-less supertype raw is a no-op"
        )
    }

    @Test
    fun `a method that is declared twice and not disambiguated by the position is refused`() {
        val text = """
            package accept.rawtypes;

            public interface Twice {
               List<Entry> getItems();

               interface Nested {
                  List<Entry> getItems();
               }
            }
        """.trimIndent() + "\n"
        // Position 0:0 is unusable, so the name alone has to be unambiguous — and it is not.
        val undecidable =
            "Twice.java:0:0: getItems() in accept.rawtypes.Twice clashes with getItems() in " +
                "accept.rawtypes.Wide"

        assertNull(RawTypeFallback.repair("Twice.java", text, listOf(undecidable)))
        // The same unit with javac's position is decided: the first declaration is the one named.
        val (line, column) = position(text, "getItems")
        val decided =
            "Twice.java:$line:$column: getItems() in accept.rawtypes.Twice clashes with getItems() in " +
                "accept.rawtypes.Wide"
        val repair = RawTypeFallback.repair("Twice.java", text, listOf(decided))
        assertTrue(repair != null, "javac's own position disambiguates the two declarations")
        assertTrue(repair!!.text.contains("   List getItems();"), repair.text)
        assertTrue(
            repair.text.contains("      List<Entry> getItems();"),
            "the nested declaration javac did not name must survive untouched:\n${repair.text}"
        )
    }

    @Test
    fun `a method declared in a nested type is refused even when javac's position names it`() {
        val text = """
            package accept.rawtypes;

            public interface Outer {
               interface Nested {
                  List<Entry> getItems();
               }
            }
        """.trimIndent() + "\n"
        val (line, column) = position(text, "getItems")
        val diagnostic =
            "Outer.java:$line:$column: getItems() in accept.rawtypes.Outer${'$'}Nested clashes with " +
                "getItems() in accept.rawtypes.Wide"

        assertNull(
            RawTypeFallback.repair("Outer.java", text, listOf(diagnostic)),
            "a nested type's declaration is not the unit's own: the fallback refuses it"
        )
    }

    @Test
    fun `a named method whose return type carries no type arguments is refused`() {
        val text = """
            package accept.rawtypes;

            public interface Barcodes {
               AssetBarcodeList getBarcodes();
            }
        """.trimIndent() + "\n"
        val (line, column) = position(text, "getBarcodes")
        val diagnostic =
            "Barcodes.java:$line:$column: getBarcodes() in accept.rawtypes.Barcodes clashes with " +
                "getBarcodes() in accept.rawtypes.IBarcodeAware\n" +
                "  return type accept.rawtypes.AssetBarcodeList is not compatible with " +
                "java.util.List<accept.rawtypes.IBarcode>"

        assertNull(
            RawTypeFallback.repair("Barcodes.java", text, listOf(diagnostic)),
            "there is nothing to make raw: the return type is already raw (the real ContainerInfo case)"
        )
    }

    @Test
    fun `one diagnostic outside the two families refuses the whole unit`() {
        val text = """
            package accept.rawtypes;

            public interface NarrowContext extends WideContext {
               List<Item> getItems();
            }
        """.trimIndent() + "\n"
        val clash =
            "NarrowContext.java:4:15: getItems() in accept.rawtypes.NarrowContext clashes with getItems() " +
                "in accept.rawtypes.WideContext"
        val unrelated = "NarrowContext.java:4:19: cannot find symbol\n  symbol:   class Item"

        assertNull(
            RawTypeFallback.repair("NarrowContext.java", text, listOf(clash, unrelated)),
            "a rewrite proven only against the other errors is proven against nothing"
        )
        assertNull(RawTypeFallback.repair("NarrowContext.java", text, emptyList()))
    }

    @Test
    fun `a family diagnostic that names no declaration of this unit is refused`() {
        val text = """
            package accept.rawtypes;

            public final class WorkRequestUpdatedEvent implements IWorkRequestEvent {
            }
        """.trimIndent() + "\n"
        val diagnostic =
            "WorkRequestUpdatedEvent.java:8:14: accept.rawtypes.WorkRequestUpdatedEvent is not abstract " +
                "and does not override abstract method getId() in accept.rawtypes.IWorkRequestEvent"

        assertNull(
            RawTypeFallback.repair("WorkRequestUpdatedEvent.java", text, listOf(diagnostic)),
            "the class does not declare getId(): there is no declaration in this unit to make raw"
        )
    }

    @Test
    fun `text that does not tokenize into a unit is refused rather than half-rewritten`() {
        assertNull(RawTypeFallback.repair("Broken.java", ") not java at all (", listOf("Broken.java:1:1: types accept.A and accept.B are incompatible")))
    }

    // -- the gate --------------------------------------------------------------------------------

    /** The parent interface, compiled into its own directory: the unit's clash is with that class file. */
    private val parentSource = """
        package accept.rawtypes;

        import java.util.List;

        public interface WideContext {
           List<Entry> getItems();
        }
    """.trimIndent() + "\n"

    /** The unit: the narrowed override that parses and that javac rejects. */
    private val narrowUnit = """
        package accept.rawtypes;

        import java.util.List;

        public interface NarrowContext extends WideContext {
           List<Item> getItems();
        }
    """.trimIndent() + "\n"

    @Test
    fun `at the gate, a repaired unit converts and the rewrite is in the manifest's warnings`() {
        val deps = parentDeps("repaired")

        // The gate's own diagnostic, before anything is rewritten. javac spells the two types fully
        // qualified here because the parent came off the classpath rather than out of this unit.
        val errors = JavacCompileChecker().check("NarrowContext.java", narrowUnit, listOf(deps))
        assertEquals(1, errors.size, errors.toString())
        assertTrue(
            errors.single().contains(
                "getItems() in accept.rawtypes.NarrowContext clashes with " +
                    "getItems() in accept.rawtypes.WideContext"
            ),
            "the fixture must reproduce the family: $errors"
        )
        assertTrue(
            errors.single().contains(
                "return type java.util.List<accept.rawtypes.Item> is not compatible with " +
                    "java.util.List<accept.rawtypes.Entry>"
            ),
            "javac's own return-type detail must be carried: $errors"
        )

        val outputRoot = tmp.resolve("out")
        val manifest = converter("accept.rawtypes.NarrowContext", narrowUnit).convert(
            ConversionRequest(
                classesRoot = classesRootFor("NarrowContext", "NarrowContext"),
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sourcesRootFor("NarrowContext")),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        assertTrue(manifest.success, "the repaired unit must compile: ${manifest.failures}")
        assertEquals(listOf("accept.rawtypes.NarrowContext"), manifest.converted.map { it.className })
        val written = Files.readString(outputRoot.resolve("accept/rawtypes/NarrowContext.java"))
        assertNotEquals(narrowUnit, written, "the unit must have been made raw to compile")
        assertEquals(narrowUnit.replace("List<Item> getItems()", "List getItems()"), written)
        assertEquals(1, manifest.warnings.size, manifest.warnings.toString())
        val warning = manifest.warnings.single()
        assertTrue(warning.startsWith("raw-type fallback: accept.rawtypes.NarrowContext: "), warning)
        assertTrue(warning.contains("getItems() return type `List<Item>` -> `List`"), warning)
        assertTrue(warning.contains("javac rejected the unit first: ${errors.single().substringBefore('\n')}"), warning)
    }

    @Test
    fun `at the gate, a refused unit keeps the first diagnostic and is not written`() {
        val deps = parentDeps("refused")
        // A second, unrelated defect: the fallback must not touch the unit at all, because a rewrite
        // proven against the clash alone would be a guess about this error.
        val unit = narrowUnit.replace("   List<Item> getItems();", "   List<Item> getItems();\n\n   Missing missing();")

        val errors = JavacCompileChecker().check("NarrowContext.java", unit, listOf(deps))
        assertTrue(errors.any { it.contains("cannot find symbol") }, errors.toString())

        val outputRoot = tmp.resolve("out-refused")
        val manifest = converter("accept.rawtypes.NarrowContext", unit).convert(
            ConversionRequest(
                classesRoot = classesRootFor("NarrowContext", "NarrowContext"),
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sourcesRootFor("NarrowContext")),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        assertFalse(manifest.success, "a refused unit must stay a failure")
        val failure = manifest.failures.single { it.phase == "COMPILE" }
        assertEquals("accept.rawtypes.NarrowContext", failure.className)
        assertEquals(
            errors.joinToString("; "),
            failure.message,
            "the diagnostic of the FIRST attempt must be carried, not a rewritten text's"
        )
        assertFalse(Files.exists(outputRoot.resolve("accept/rawtypes/NarrowContext.java")))
        assertTrue(manifest.warnings.none { it.startsWith("raw-type fallback:") }, manifest.warnings.toString())
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun classesRootFor(className: String, sourceName: String): Path {
        val classes = tmp.resolve("classes-$className")
        TestSupport.writeFacadeClassFile(classes, "accept/rawtypes", className, "$sourceName.kt")
        return classes
    }

    private fun sourcesRootFor(className: String): Path {
        val sources = tmp.resolve("src-$className")
        val kt = sources.resolve("accept/rawtypes/$className.kt")
        Files.createDirectories(kt.parent)
        Files.writeString(kt, "package accept.rawtypes\n\nclass $className\n")
        return sources
    }

    private class FixedDecompiler(private val units: Map<String, String>) : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> = units
    }

    private fun converter(className: String, text: String): K2j = K2j(
        surveyor = AsmSurveyor(),
        decompiler = FixedDecompiler(mapOf(className to text)),
        validator = JavacValidator(),
        writer = FileSystemWriter(),
        log = object : RunLog {
            override fun info(message: String) = println(message)
            override fun warn(message: String) = println("WARN: $message")
            override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
        }
    )

    /** Compiles one source file into [output], the way the pipeline's dependencies are built. */
    private fun compile(relativePath: String, source: String, output: Path, classpath: List<Path> = emptyList()) {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler() ?: error("no system javac")
        val diagnostics = javax.tools.DiagnosticCollector<JavaFileObject>()
        val unit = object : SimpleJavaFileObject(URI.create("string:///$relativePath"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): String = source
        }
        val options = mutableListOf("-proc:none", "-d", output.toString())
        if (classpath.isNotEmpty()) {
            options += "-cp"
            options += classpath.joinToString(java.io.File.pathSeparator) { it.toString() }
        }
        val ok = compiler.getTask(null, null, diagnostics, options, null, listOf(unit)).call()
        if (!ok) error("fixture compile failed: ${diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }}")
    }

    private fun compileJava(directory: Path, relativePath: String, source: String) {
        compile(relativePath, source, directory)
    }

    /** The unit's dependency tree, compiled into its own directory: element types, then the parent. */
    private fun parentDeps(name: String): Path {
        val deps = tmp.resolve("deps-$name")
        compile("accept/rawtypes/Entry.java", "package accept.rawtypes;\n\npublic class Entry {\n}\n", deps)
        compile(
            "accept/rawtypes/Item.java",
            "package accept.rawtypes;\n\npublic class Item extends Entry {\n}\n",
            deps,
            listOf(deps)
        )
        compile("accept/rawtypes/WideContext.java", parentSource, deps, listOf(deps))
        return deps
    }

    /**
     * The 1-based line and column of the [occurrence]-th spelling of [token] in [text]: what javac
     * reports for a declaration it names, computed from the text instead of by hand.
     */
    private fun position(text: String, token: String, occurrence: Int = 1): Pair<Int, Int> {
        var index = -1
        repeat(occurrence) { index = text.indexOf(token, index + 1) }
        check(index >= 0) { "`$token` does not occur ${occurrence} times in the fixture" }
        val line = text.substring(0, index).count { it == '\n' } + 1
        val column = index - (text.lastIndexOf('\n', index - 1) + 1) + 1
        return line to column
    }

    /**
     * The minimality assertion the corpus test shares: between [raw] and [repaired] exactly one line
     * differs, that line differs only by [deletions] removed type-argument lists, the text before and
     * after the deleted ranges is byte-identical, and the named declaration is on that line.
     */
    private fun assertMinimal(raw: String, repaired: String, named: String, deletions: Int) {
        val rawLines = raw.lines()
        val repairedLines = repaired.lines()
        assertEquals(rawLines.size, repairedLines.size, "no line may be added or removed")
        val changed = rawLines.zip(repairedLines).withIndex().filter { it.value.first != it.value.second }
        assertEquals(1, changed.size, "exactly one line may change, got: ${changed.map { it.index + 1 }}")
        val line = changed.single()
        assertTrue(line.value.first.contains(named), "the changed line must be the named declaration: ${line.value.first}")
        assertEquals(
            deletions,
            line.value.first.count { it == '<' } - line.value.second.count { it == '<' },
            "the changed line must lose exactly the named type-argument lists"
        )
        assertEquals(
            raw.count { it == '>' } - repaired.count { it == '>' },
            deletions,
            "no other type argument in the unit may be dropped"
        )
        // And the two texts agree everywhere the changed line did not: the prefix and the suffix of
        // the deleted spans are the same bytes.
        assertEquals(rawLines.size, repairedLines.size)
        for (index in rawLines.indices) {
            if (index == line.index) continue
            assertEquals(rawLines[index], repairedLines[index], "line ${index + 1} must not move")
        }
        assertTrue(repaired.length < raw.length, "a raw-type repair only ever removes text")
    }
}
