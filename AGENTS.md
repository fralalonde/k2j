# k2j build conventions (agent + human)

- Java toolchain for building k2j itself: `C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr` (JAVA_HOME).
- The vendored FernFlower jar is class-version 69 (Java 25). Anything that *runs* it — CLI, tests,
  Gradle daemon workers — must run on a Java 25 runtime. The main IDE's bundled JBR provides one:
  `C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr` (25.0.4, has javac).
  Do not confuse the two: build k2j with 21, execute the decompiler with 25.
- Windows: use `cmd.exe /c gradlew.bat ...` from git-bash; `./gradlew` does not exist here.
- The corpus project has no wrapper yet; borrow `D:/Work/k2j-ij/gradlew.bat -p .` or add one.
- FernFlower API (verified by javap against the vendored jar):
  `BaseDecompiler(IBytecodeProvider, IResultSaver, Map<String,Object>, IFernflowerLogger)` +
  `addSource(File)` per class file + `decompileContext()`.
  `saveClassEntry(path, archive, qualifiedName, entryName, content)` — content is the Java text,
  entryName is `Foo.java`. `saveClassFile` is the directory-output variant.
- Options of interest: `rbr` (remove bridges; default 1 — KEEP the default), `ran`/`rlo` etc. left
  at defaults. The viewer's `rbr=0` is the *cause* of bridge pollution, do not copy it.
- `kotlin/Metadata` annotation on a class file is the discriminator for Kotlin classes.
