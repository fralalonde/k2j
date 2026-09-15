package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** End-to-end: survey → decompile → normalise → validate → write → manifest deletion proof. */
class K2jEndToEndTest {

    @TempDir
    lateinit var tmp: Path

    private val jarPath: Path =
        Path.of("D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar")

    private val jbrPath: Path =
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")

    private val jarReady: Boolean get() = Files.isRegularFile(jarPath)

    private val jbrReady: Boolean get() = Files.isRegularFile(jbrPath.resolve("bin/java.exe"))

    private fun assumeReady() {
        val corpus = TestSupport.corpusClasses
        Assumptions.assumeTrue(corpus != null, "corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")
        Assumptions.assumeTrue(jarReady, "vendored java-decompiler.jar not found at $jarPath")
        Assumptions.assumeTrue(jbrReady, "Java 25 runtime (IDE JBR) not found at $jbrPath")
    }

    private fun k2jWith(decompiler: Decompiler): K2j = K2j(
        surveyor = AsmSurveyor(),
        decompiler = decompiler,
        validator = JavacValidator(),
        writer = FileSystemWriter(),
        log = object : RunLog {
            override fun info(message: String) = println(message)
            override fun warn(message: String) = println("WARN: $message")
            override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
        }
    )

    /** Drops an output, simulating a class that could not be converted into a written unit. */
    private class DroppingDecompiler(
        private val delegate: Decompiler,
        private val drop: Set<String>
    ) : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> =
            delegate.decompile(classFiles, classpath).filterKeys { it !in drop }
    }

    @Test
    fun `end-to-end convert on corpus converts every unit and refuses typealias sources`() {
        assumeReady()
        val corpus = TestSupport.corpusClasses!!
        val outputRoot = tmp.resolve("out")
        val converter = k2jWith(FernFlowerDecompiler(jarPath, jbrPath))
        val manifest = converter.convert(
            ConversionRequest(
                classesRoot = corpus,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )

        assertTrue(manifest.success, "expected success, failures: ${manifest.failures}")
        val survey = converter.lastResult!!.survey
        // Derived from the survey, not a hardcoded roster: the corpus grows (the enum/object/data
        // class/annotation fixtures were added for the target module defects), and the invariant these
        // fixtures exist for is "every surveyed class converts", which the survey states before the
        // conversion runs. Memberless interfaces/facades are conversion targets (defect B): IEntityId,
        // Holder, TypeAliasesKt and MixedKt all decompile to empty but valid Java types.
        assertEquals(
            survey.targets.map { it.className }.sorted(),
            manifest.converted.map { it.className }.sorted(),
            "every surveyed class must convert"
        )
        // ...and the classes the original roster named must still be in there: a corpus fixture that
        // silently stopped converting would satisfy the assertion above only with the survey agreeing.
        val convertedNames = manifest.converted.map { it.className }.toSet()
        for (name in listOf(
            "accept.bridged.BridgeBox",
            "accept.bridged.BridgeImpl",
            "accept.covariant.ActivityId",
            "accept.covariant.CancelActivityLike",
            "accept.covariant.IActivityCommand",
            "accept.covariant.IEntityId",
            "accept.mixed.Mixed",
            "accept.mixed.MixedKt",
            "accept.nested.Outer",
            "accept.plain.Single",
            "accept.plain.SingleContract",
            "accept.types.Holder",
            "accept.types.TypeAliasesKt"
        )) {
            assertTrue(name in convertedNames, "$name must still convert; got $convertedNames")
        }

        // Deliberate update for the new fixtures: every corpus source is deletable except the two that
        // declare a typealias (no member in the Java output). Derived from the survey's own source
        // list, with the two blockers named explicitly.
        val expectedDeletable = survey.sources
            .map { it.source.fileName.name }
            .filterNot { it in setOf("Mixed.kt", "TypeAliases.kt") }
            .sorted()
        val deletableNames = manifest.deletableSources.map { Path.of(it).fileName.name }.sorted()
        assertEquals(expectedDeletable, deletableNames)

        val decisions = manifest.sources.associateBy { Path.of(it.source).fileName.name }
        assertFalse(decisions.getValue("TypeAliases.kt").deletable, "typealias-bearing source must not be deletable")
        assertTrue(
            decisions.getValue("TypeAliases.kt").reason.contains("typealias 'Text'"),
            "reason must name the unrepresented declaration: ${decisions.getValue("TypeAliases.kt").reason}"
        )
        assertFalse(decisions.getValue("Mixed.kt").deletable, "typealias-bearing source must not be deletable")
        assertTrue(decisions.getValue("Mixed.kt").reason.contains("typealias 'MixedAlias'"))

        // Every source that was not deleted carries a reason.
        for (d in manifest.sources) {
            assertTrue(d.reason.isNotBlank(), "every source decision needs a reason: $d")
        }

        // The full class-file group of a source is what the guard rests on (defect B).
        val cancelGroup = survey.sources.single { it.source.fileName.name == "CancelActivityLike.kt" }
            .classFiles.map { it.fileName.name }.sorted()
        assertEquals(
            listOf("ActivityId.class", "CancelActivityLike.class", "IActivityCommand.class", "IEntityId.class"),
            cancelGroup,
            "the source group must list every class file, memberless ones included"
        )
        val outerGroup = survey.sources.single { it.source.fileName.name == "Outer.kt" }
            .classFiles.map { it.fileName.name }.sorted()
        assertEquals(
            listOf("Outer\$Inner.class", "Outer\$Nested.class", "Outer\$makeAnon\$1.class", "Outer\$makeLocal\$Local.class", "Outer.class"),
            outerGroup,
            "the source group must list every nested class file"
        )
        assertTrue(Files.isRegularFile(outputRoot.resolve("accept/covariant/IEntityId.java")))
    }

    @Test
    fun `delete-sources on a copy deletes only fully represented sources`() {
        // Acceptance 3/4/5, run on a COPY of the corpus sources — the real corpus is never touched.
        assumeReady()
        val corpus = TestSupport.corpusClasses!!
        val copyRoot = tmp.resolve("copy/src/main/kotlin")
        copyTree(TestSupport.corpusSources, copyRoot)
        val outputRoot = tmp.resolve("del-out")
        // Read the roster before the run: `deleteSources = true` removes the deletable ones from disk.
        val everySource = Files.walk(copyRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it.fileName.name }.sorted().toList()
        }

        val manifest = k2jWith(FernFlowerDecompiler(jarPath, jbrPath)).convert(
            ConversionRequest(
                classesRoot = corpus,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(copyRoot),
                deleteSources = true
            )
        )

        assertTrue(manifest.success, "failures: ${manifest.failures}")
        val deletedNames = manifest.deletedSources.map { Path.of(it).fileName.name }.sorted()
        // Deliberately derived, not hardcoded: the corpus now carries enum/object/data class/annotation
        // fixtures too, and every one of their sources is fully represented in the Java output. The two
        // that are not — the typealias-bearing sources — are named as the exclusions.
        assertEquals(
            everySource.filterNot { it in setOf("Mixed.kt", "TypeAliases.kt") },
            deletedNames,
            "every fully represented source must be deleted, and only those"
        )

        // The typealias-bearing sources survive, with a recorded reason in the manifest.
        for (kept in listOf("Mixed.kt", "TypeAliases.kt")) {
            assertTrue(Files.isRegularFile(copyRoot.resolve(relativeSource(kept))), "$kept must not be deleted")
            val decision = manifest.sources.single { Path.of(it.source).fileName.name == kept }
            assertFalse(decision.deletable)
            assertTrue(decision.reason.contains("typealias"), "reason must explain the refusal: $decision")
        }
        assertTrue(Files.notExists(copyRoot.resolve("accept/nested/Outer.kt")), "Outer.kt must be deleted")

        // The nested/inner types really are in the generated text — the deletion proof is on the text.
        val outerJava = Files.readString(outputRoot.resolve("accept/nested/Outer.java"))
        assertTrue(outerJava.contains("class Nested"), "generated Outer.java must contain the nested class")
        assertTrue(outerJava.contains("class Inner"), "generated Outer.java must contain the inner class")
        assertTrue(outerJava.contains("class Local"), "generated Outer.java must contain the local class")
        val mixedJava = Files.readString(outputRoot.resolve("accept/mixed/Mixed.java"))
        assertTrue(mixedJava.contains("class Nested") && mixedJava.contains("class Inner"))
    }

    @Test
    fun `a source is not deletable while one of its classes has no written output`() {
        // DEFECT B: CancelActivityLike.kt's group includes IEntityId; if no IEntityId.java is written
        // the deletion guard must block the file — the old group-omission let it be deleted.
        assumeReady()
        val corpus = TestSupport.corpusClasses!!
        val real = FernFlowerDecompiler(jarPath, jbrPath)

        val droppedOut = tmp.resolve("dropped")
        val dropped = k2jWith(DroppingDecompiler(real, setOf("accept.covariant.IEntityId"))).convert(
            ConversionRequest(
                classesRoot = corpus,
                classpath = emptyList(),
                outputRoot = droppedOut,
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )
        val decision = dropped.sources.single { Path.of(it.source).fileName.name == "CancelActivityLike.kt" }
        assertFalse(decision.deletable, "must not be deletable while IEntityId has no written output")
        assertTrue(decision.reason.contains("IEntityId.class"), "reason must name the missing class: ${decision.reason}")
        assertTrue(dropped.failures.any { it.className == "accept.covariant.IEntityId" }, "a zero-output class is a failure")
        assertFalse(Files.exists(droppedOut.resolve("accept/covariant/IEntityId.java")))

        // Safe counterpart: with the real decompiler IEntityId.java IS written, so the source is
        // legitimately deletable and the guard is proof, not luck.
        val safeOut = tmp.resolve("safe")
        val safe = k2jWith(real).convert(
            ConversionRequest(
                classesRoot = corpus,
                classpath = emptyList(),
                outputRoot = safeOut,
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )
        assertTrue(Files.isRegularFile(safeOut.resolve("accept/covariant/IEntityId.java")))
        val safeDecision = safe.sources.single { Path.of(it.source).fileName.name == "CancelActivityLike.kt" }
        assertTrue(safeDecision.deletable, "with IEntityId.java written the source is deletable: ${safeDecision.reason}")
    }

    @Test
    fun `decompiler warnings reach the manifest and the run log`() {
        // DEFECT D: warnings were collected then dropped, so the manifest's warnings array was
        // always empty. Feed a unit whose siblings are withheld: FernFlower logs "Nested class ...
        // missing!" and that must arrive in the manifest (and the run log) rather than vanish.
        assumeReady()
        val corpus = TestSupport.corpusClasses!!
        val logged = mutableListOf<String>()
        val converter = K2j(
            surveyor = OuterOnlySurveyor(AsmSurveyor(), "accept.nested.Outer"),
            decompiler = FernFlowerDecompiler(jarPath, jbrPath),
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = object : RunLog {
                override fun info(message: String) = Unit
                override fun warn(message: String) { logged += message }
                override fun error(message: String, cause: Throwable?) = Unit
            }
        )
        val manifest = converter.convert(
            ConversionRequest(
                classesRoot = corpus,
                classpath = emptyList(),
                outputRoot = tmp.resolve("warn-out"),
                packages = listOf("accept.nested"),
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )
        assertTrue(
            manifest.warnings.any { it.contains("missing", ignoreCase = true) },
            "warnings must reach the manifest; got ${manifest.warnings}"
        )
        assertTrue(
            logged.any { it.contains("missing", ignoreCase = true) },
            "warnings must reach the run log; got $logged"
        )
    }

    /** Withholds a unit's sibling class files, simulating a survey that only saw the outer class. */
    private class OuterOnlySurveyor(private val delegate: Surveyor, private val className: String) : Surveyor {
        override fun survey(request: ConversionRequest): SurveyResult {
            val result = delegate.survey(request)
            return result.copy(
                targets = result.targets.map {
                    if (it.className == className) it.copy(classFiles = listOf(it.classFile)) else it
                }
            )
        }
    }

    private fun relativeSource(name: String): String = when (name) {
        "Single.kt" -> "accept/plain/Single.kt"
        "Bridge.kt" -> "accept/bridged/Bridge.kt"
        "CancelActivityLike.kt" -> "accept/covariant/CancelActivityLike.kt"
        "TypeAliases.kt" -> "accept/types/TypeAliases.kt"
        "Mixed.kt" -> "accept/mixed/Mixed.kt"
        "Outer.kt" -> "accept/nested/Outer.kt"
        else -> error("unknown fixture $name")
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
