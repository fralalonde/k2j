package com.onomatic.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Survey tests against the corpus classes (present per the task) plus synthetic ASM-built fixtures
 * for rules the corpus does not exercise.
 */
class AsmSurveyorTest {

    private fun surveyOf(classesRoot: Path, packages: List<String> = emptyList(), sourceRoots: List<Path> = emptyList()): SurveyResult =
        AsmSurveyor().survey(
            ConversionRequest(
                classesRoot = classesRoot,
                classpath = emptyList(),
                outputRoot = classesRoot.resolveSibling("k2j-out"),
                packages = packages,
                sourceRoots = sourceRoots
            )
        )

    @org.junit.jupiter.api.Test
    fun `survey accepts only Kotlin classes`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")
        val result = surveyOf(corpus!!)
        val names = result.targets.map { it.className }
        org.junit.jupiter.api.Assertions.assertTrue("accept.plain.Single" in names, "expected accept.plain.Single in $names")
        org.junit.jupiter.api.Assertions.assertTrue("accept.bridged.BridgeImpl" in names, "expected accept.bridged.BridgeImpl in $names")
        for (target in result.targets) {
            org.junit.jupiter.api.Assertions.assertFalse(target.className.contains('$'), "inner class leaked as a separate target: ${target.className}")
        }
    }

    @org.junit.jupiter.api.Test
    fun `survey rejects a Java-compiled class`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        // The corpus classes are all Kotlin; prove the exclusion mechanism on a mixed root.
        val mixed = Files.createTempDirectory("k2j-mixed")
        copyTree(corpus!!, mixed)
        TestSupport.compileJavaClass(mixed, "javafix", "PlainJava")
        val mixedResult = surveyOf(mixed)
        val javaEntry = mixedResult.excluded.firstOrNull { it.className == "javafix.PlainJava" }
        org.junit.jupiter.api.Assertions.assertNotNull(javaEntry, "PlainJava must be excluded")
        org.junit.jupiter.api.Assertions.assertEquals("no kotlin/Metadata annotation", javaEntry!!.reason)
        org.junit.jupiter.api.Assertions.assertTrue(mixedResult.targets.none { it.className == "javafix.PlainJava" })
    }

    @org.junit.jupiter.api.Test
    fun `package filter works`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!, packages = listOf("accept.plain"))
        val names = result.targets.map { it.className }
        org.junit.jupiter.api.Assertions.assertEquals(listOf("accept.plain.Single", "accept.plain.SingleContract"), names)
        org.junit.jupiter.api.Assertions.assertTrue(result.excluded.any { it.reason == "outside requested packages" })
    }

    @org.junit.jupiter.api.Test
    fun `facade XxxKt maps to Xxx dot kt`() {
        // Synthetic fixture: a Kotlin-looking facade class `com.demo.WidgetsKt` with no SourceFile
        // attribute, next to a source root holding `com/demo/Widgets.kt`.
        val classes = Files.createTempDirectory("k2j-facade-classes")
        val sources = Files.createTempDirectory("k2j-facade-sources")
        Files.createDirectories(sources.resolve("com/demo"))
        Files.writeString(sources.resolve("com/demo/Widgets.kt"), "package com.demo\n")
        TestSupport.writeFacadeClassFile(classes, "com/demo", "WidgetsKt", sourceFile = null)
        val result = surveyOf(classes, sourceRoots = listOf(sources))
        val mapping = result.sources.singleOrNull()
        org.junit.jupiter.api.Assertions.assertNotNull(mapping, "WidgetsKt must map via the facade rule")
        org.junit.jupiter.api.Assertions.assertEquals("Widgets.kt", mapping!!.source.fileName.name)
        org.junit.jupiter.api.Assertions.assertEquals("com.demo.WidgetsKt", result.targets.single().className)
    }

    @org.junit.jupiter.api.Test
    fun `classes with no matching source root simply have no source mapping`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!, sourceRoots = listOf(Path.of("D:/nonexistent-root-xyz")))
        org.junit.jupiter.api.Assertions.assertTrue(result.targets.isNotEmpty())
        org.junit.jupiter.api.Assertions.assertTrue(result.sources.isEmpty(), "no source root matches; mappings must be empty, not a failure")
    }

    @org.junit.jupiter.api.Test
    fun `source mapping groups every class of a unit by its SourceFile`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!, sourceRoots = listOf(TestSupport.corpusSources))
        val bySource = result.sources.associate { it.source.fileName.name to it.classFiles.map { f -> f.fileName.name }.sorted() }
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("BridgeBox.class", "BridgeImpl.class"),
            bySource["Bridge.kt"], "Bridge.kt group: ${bySource["Bridge.kt"]}"
        )
        // DEFECT B: IEntityId must be in the group even though it is memberless; a missing member
        // here is what let the file be deleted while IEntityId.java was never written.
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("ActivityId.class", "CancelActivityLike.class", "IActivityCommand.class", "IEntityId.class"),
            bySource["CancelActivityLike.kt"], "CancelActivityLike.kt group: ${bySource["CancelActivityLike.kt"]}"
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Single.class", "SingleContract.class"),
            bySource["Single.kt"], "Single.kt group: ${bySource["Single.kt"]}"
        )
        // DEFECT A: every nested/inner/local/anonymous class file belongs to the outer source group.
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Outer\$Inner.class", "Outer\$Nested.class", "Outer\$makeAnon\$1.class", "Outer\$makeLocal\$Local.class", "Outer.class"),
            bySource["Outer.kt"], "Outer.kt group: ${bySource["Outer.kt"]}"
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Holder.class", "TypeAliasesKt.class"),
            bySource["TypeAliases.kt"], "TypeAliases.kt group: ${bySource["TypeAliases.kt"]}"
        )
    }

    @org.junit.jupiter.api.Test
    fun `nested classes are grouped into the outer unit target`() {
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!, packages = listOf("accept.nested"))
        val targets = result.targets.map { it.className }
        org.junit.jupiter.api.Assertions.assertEquals(listOf("accept.nested.Outer"), targets, "one target per top-level class; got $targets")
        val outer = result.targets.single()
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Outer\$Inner.class", "Outer\$Nested.class", "Outer\$makeAnon\$1.class", "Outer\$makeLocal\$Local.class", "Outer.class"),
            outer.classFiles.map { it.fileName.name }.sorted(),
            "the outer target must carry every sibling class file so the decompiler emits the nested types"
        )
    }

    @org.junit.jupiter.api.Test
    fun `memberless classes are conversion targets`() {
        // DEFECT B (inverted): an empty interface/facade still produces a class file, and FernFlower
        // decompiles it to `public interface IEntityId {}` etc. Excluding it left the deletion guard
        // blind, so the source was deleted while the generated Java did not compile.
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!)
        val names = result.targets.map { it.className }
        org.junit.jupiter.api.Assertions.assertTrue("accept.covariant.IEntityId" in names, "memberless interface must convert; got $names")
        org.junit.jupiter.api.Assertions.assertTrue("accept.types.Holder" in names, "memberless interface must convert; got $names")
        org.junit.jupiter.api.Assertions.assertTrue("accept.types.TypeAliasesKt" in names, "empty facade must convert; got $names")
        org.junit.jupiter.api.Assertions.assertTrue("accept.mixed.MixedKt" in names, "empty facade must convert; got $names")
        org.junit.jupiter.api.Assertions.assertTrue(
            result.excluded.none { it.reason == "no members to decompile" },
            "no class may be excluded for having no members any more"
        )
    }

    @org.junit.jupiter.api.Test
    fun `typealias sources carry a deletion blocker`() {
        // DEFECT C: the generated Java has no member for a typealias, so the source must never be
        // deletable — even though its class files all convert.
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val result = surveyOf(corpus!!, sourceRoots = listOf(TestSupport.corpusSources))
        val byName = result.sources.associateBy { it.source.fileName.name }

        val typeAliases = byName.getValue("TypeAliases.kt")
        org.junit.jupiter.api.Assertions.assertTrue(
            typeAliases.deletionBlockers.any { it.contains("typealias 'Text'") },
            "TypeAliases.kt must be blocked by typealias Text: ${typeAliases.deletionBlockers}"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            typeAliases.deletionBlockers.any { it.contains("typealias 'Classes'") },
            "TypeAliases.kt must be blocked by typealias Classes: ${typeAliases.deletionBlockers}"
        )
        val mixed = byName.getValue("Mixed.kt")
        org.junit.jupiter.api.Assertions.assertTrue(
            mixed.deletionBlockers.any { it.contains("typealias 'MixedAlias'") },
            "Mixed.kt must be blocked by typealias MixedAlias: ${mixed.deletionBlockers}"
        )
        // A source with no aliases has no blocker.
        org.junit.jupiter.api.Assertions.assertTrue(byName.getValue("Bridge.kt").deletionBlockers.isEmpty())
        org.junit.jupiter.api.Assertions.assertTrue(byName.getValue("Single.kt").deletionBlockers.isEmpty())
    }

    @org.junit.jupiter.api.Test
    fun `ambiguous source mapping fails closed`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        // DEFECT F: with two --source-root values that both hold a copy of the source, the class
        // cannot be attributed to one root; picking the first would risk deleting the wrong file.
        val corpus = TestSupport.corpusClasses
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "corpus classes not built")
        val rootA = tmp.resolve("rootA")
        val rootB = tmp.resolve("rootB")
        for (root in listOf(rootA, rootB)) {
            val dir = root.resolve("accept/plain")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("Single.kt"), "package accept.plain\n")
        }

        val result = surveyOf(corpus!!, sourceRoots = listOf(rootA, rootB))
        val ambiguity = result.excluded.firstOrNull { it.className == "accept.plain.Single" }
        org.junit.jupiter.api.Assertions.assertNotNull(ambiguity, "Single.class must be excluded as ambiguous; got ${result.excluded}")
        org.junit.jupiter.api.Assertions.assertTrue(
            ambiguity!!.reason.startsWith("ambiguous source mapping"),
            "reason must record the ambiguity: ${ambiguity.reason}"
        )
        // No mapping may attribute Single.class, and every candidate source must be a blocker.
        org.junit.jupiter.api.Assertions.assertTrue(
            result.sources.none { it.classFiles.any { f -> f.fileName.name == "Single.class" } },
            "no mapping may claim Single.class"
        )
        val singleCandidates = result.sources.filter { it.source.fileName.name == "Single.kt" }
        org.junit.jupiter.api.Assertions.assertEquals(2, singleCandidates.size, "both candidate roots must be listed")
        org.junit.jupiter.api.Assertions.assertTrue(
            singleCandidates.all { it.deletionBlockers.any { b -> b.startsWith("ambiguous source mapping") } },
            "every ambiguous candidate must be blocked: $singleCandidates"
        )
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.asSequence().forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
    }
}
