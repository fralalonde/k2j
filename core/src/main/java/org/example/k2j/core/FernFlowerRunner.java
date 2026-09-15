package org.example.k2j.core;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs inside the forked Java 25+ JVM with the vendored FernFlower jar on the classpath.
 *
 * Written against reflection + Proxy only, so this class compiles on the Java 21 build toolchain
 * even though the FernFlower jar is class-version 69 and can never be a compile dependency.
 *
 * Protocol: argv[0] = report file — nothing else. Everything else travels over stdin as two labelled
 * sections, one path per line, so the class list never touches the command line (Windows caps it at
 * ~32k characters, which real translations exceed):
 *
 * <pre>
 *   #CLASSES
 *   D:\...\A.class
 *   D:\...\A$Nested.class
 *   #LIBRARIES
 *   D:\...\kotlin-stdlib.jar
 *   #END
 * </pre>
 *
 * Writes escaped {@code className \t javaText} lines plus a final
 * {@code #RESULT\t<warnings>\t<failures>} line to the report file. The two summary fields are
 * escaped with the same {@link #escape(String)} as the payload, so a log message containing a tab or
 * newline cannot blank a field or inject a bogus class entry when the parent splits on tab.
 *
 * <p>Every class the decompiler asks for is handed over with its {@code kotlin.Metadata} annotation
 * removed in memory ({@link MetadataStripper}); the class files on disk are never touched. Set
 * {@code -Dk2j.stripKotlinMetadata=false} to feed the bytes through unchanged.
 */
public final class FernFlowerRunner {
    static final String CLASSES_MARKER = "#CLASSES";
    static final String LIBRARIES_MARKER = "#LIBRARIES";
    static final String END_MARKER = "#END";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: FernFlowerRunner <report-file>  (class files and libraries arrive on stdin)");
        }
        Path reportFile = Path.of(args[0]);

        List<Path> classFiles = new ArrayList<>();
        List<Path> libraries = new ArrayList<>();
        readInput(classFiles, libraries);

        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, String> results = new LinkedHashMap<>();

        ClassLoader loader = FernFlowerRunner.class.getClassLoader();

        // On by default: the forked JVM drops kotlin.Metadata from every class it is handed (see
        // MetadataStripper). -Dk2j.stripKotlinMetadata=false turns it off, which is how the tests
        // prove the strip — and not something else — is what removes the annotation.
        boolean stripMetadata = MetadataStripper.enabledFromProperties();
        if (stripMetadata) {
            // Fail here, with a message that names the cause, rather than as a bare
            // NoClassDefFoundError thrown from inside getBytecode three classes deep.
            try {
                Class.forName("org.objectweb.asm.ClassReader", false, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "ASM is not on the forked decompiler classpath, so kotlin.Metadata cannot be "
                                + "stripped; FernFlowerDecompiler must put the ASM jar on -cp "
                                + "(or run with -D" + MetadataStripper.ENABLED_PROPERTY + "=false)",
                        e);
            }
        }

        // --- IBytecodeProvider: reads the class bytes itself ---
        Class<?> bytecodeProviderClass = loader.loadClass("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider");
        Object bytecodeProvider = Proxy.newProxyInstance(loader, new Class<?>[]{bytecodeProviderClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] mArgs) throws Throwable {
                        if (method.getName().equals("getBytecode")) {
                            String externalPath = (String) mArgs[0];
                            byte[] classBytes;
                            try (InputStream in = Files.newInputStream(Path.of(externalPath))) {
                                classBytes = in.readAllBytes();
                            } catch (Exception e) {
                                throw new RuntimeException("cannot read " + externalPath, e);
                            }
                            // In-memory decoration only: the class file on disk is read, the copy
                            // handed to FernFlower has its kotlin.Metadata annotation dropped, and
                            // nothing is written back to the classes root.
                            return stripMetadata
                                    ? MetadataStripper.stripKotlinMetadata(classBytes)
                                    : classBytes;
                        }
                        return null;
                    }
                });

        // --- IResultSaver: collect class text keyed by qualifiedName ---
        Class<?> resultSaverClass = loader.loadClass("org.jetbrains.java.decompiler.main.extern.IResultSaver");
        Object resultSaver = Proxy.newProxyInstance(loader, new Class<?>[]{resultSaverClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] mArgs) {
                        String name = method.getName();
                        Object[] a = mArgs == null ? new Object[0] : mArgs;
                        if (name.equals("saveClassFile")) {
                            // (path, qualifiedName, entryName, content, mapping)
                            results.put((String) a[1], (String) a[3]);
                        } else if (name.equals("saveClassEntry")) {
                            // (path, archiveName, qualifiedName, entryName, content)
                            results.put((String) a[2], (String) a[4]);
                        }
                        return null;
                    }
                });

        // --- IFernflowerLogger: capture warnings ---
        Class<?> loggerClass = loader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger");
        Class<?> severityClass = loader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger$Severity");
        Object warnSeverity = Enum.valueOf((Class<? extends Enum>) severityClass.asSubclass(Enum.class), "WARN");
        // IFernflowerLogger is an abstract class, so a compile-time-free subclass is generated at
        // runtime with the JDK Compiler API (the forked runtime is a full JBR with javac).
        Object fernflowerLogger = compileLoggerSubclass(loader, loggerClass, severityClass, warnSeverity, warnings);

        // --- BaseDecompiler ---
        Class<?> decompilerClass = loader.loadClass("org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler");
        // rbr=1 (default) removes bridge methods; dgs=1 re-emits generic signatures. Without dgs the
        // output erases every type argument (Class<? extends Foo> comes back as raw Class), which is a
        // source-quality regression across the whole corpus. Both are FernFlower's own defaults for the
        // standalone CLI; the Kotlin bytecode viewer disables rbr for fidelity, we deliberately do not.
        Object decompiler = decompilerClass
                .getConstructor(bytecodeProviderClass, resultSaverClass, Map.class, loggerClass)
                .newInstance(bytecodeProvider, resultSaver, Map.of("rbr", "1", "dgs", "1"), fernflowerLogger);

        Method addSource = decompilerClass.getMethod("addSource", java.io.File.class);
        Method addLibrary = decompilerClass.getMethod("addLibrary", java.io.File.class);

        List<Path> sorted = new ArrayList<>(classFiles);
        sorted.sort(Comparator.comparing(Object::toString));
        for (Path classFile : sorted) {
            addSource.invoke(decompiler, classFile.toFile());
        }
        List<Path> sortedLibs = new ArrayList<>(libraries);
        sortedLibs.sort(Comparator.comparing(Object::toString));
        for (Path lib : sortedLibs) {
            addLibrary.invoke(decompiler, lib.toFile());
        }
        try {
            decompilerClass.getMethod("decompileContext").invoke(decompiler);
        } catch (java.lang.reflect.InvocationTargetException e) {
            failures.add("decompileContext: " + e.getCause());
        }

        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, String> e : results.entrySet()) {
            report.append(escape(e.getKey())).append('\t').append(escape(e.getValue())).append('\n');
        }
        // Escape the summary fields exactly like the payload: a warning or failure message may itself
        // contain the tab/newline the parent splits on.
        report.append("#RESULT\t").append(escape(String.join(" ;; ", warnings)))
              .append('\t').append(escape(String.join(" ;; ", failures))).append('\n');
        Files.writeString(reportFile, report.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reads the two stdin sections into [classFiles] and [libraries]. Lines before the first marker
     * are treated as class files for robustness; {@code #END} stops reading.
     */
    static void readInput(List<Path> classFiles, List<Path> libraries) throws java.io.IOException {
        List<Path> current = classFiles;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals(LIBRARIES_MARKER)) {
                    current = libraries;
                    continue;
                }
                if (trimmed.equals(CLASSES_MARKER)) {
                    current = classFiles;
                    continue;
                }
                if (trimmed.equals(END_MARKER)) {
                    break;
                }
                current.add(Path.of(trimmed));
            }
        }
    }

    /**
     * IFernflowerLogger is an abstract class, so a compile-time-free subclass is generated at runtime
     * with the JDK Compiler API (the forked runtime is a full JBR with javac).
     */
    private static Object compileLoggerSubclass(ClassLoader loader, Class<?> loggerClass,
                                                Class<?> severityClass, Object warnSeverity,
                                                List<String> warnings) throws Exception {
        String pkg = "org.example.k2j.core";
        String className = "GeneratedFernflowerLogger";
        String qualified = pkg + "." + className;
        String source = """
                package %s;

                import java.util.List;
                import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;

                public final class %s extends IFernflowerLogger {
                    private final List<String> warnings;

                    public %s(List<String> warnings) {
                        this.warnings = warnings;
                    }

                    @Override
                    public void writeMessage(String message, Severity severity) {
                        if (severity == Severity.WARN) {
                            warnings.add(message);
                        }
                    }

                    @Override
                    public void writeMessage(String message, Severity severity, Throwable t) {
                        if (severity == Severity.WARN) {
                            warnings.add(message + ": " + t);
                        }
                    }
                }
                """.formatted(pkg, className, className);

        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("forked runtime has no javac; cannot generate the logger subclass");
        }
        java.util.List<javax.tools.JavaFileObject> units = List.of(new javax.tools.SimpleJavaFileObject(
                java.net.URI.create("string:///" + qualified.replace('.', '/') + ".java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public String getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        });
        java.nio.file.Path outDir = Files.createTempDirectory("k2j-logger-gen");
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diag = new javax.tools.DiagnosticCollector<>();
        javax.tools.StandardJavaFileManager fm = compiler.getStandardFileManager(diag, null, null);
        boolean ok = compiler.getTask(null, fm, diag, List.of("-d", outDir.toString()), null, units).call();
        fm.close();
        if (!ok) {
            throw new IllegalStateException("logger subclass generation failed: " + diag.getDiagnostics());
        }
        ClassLoader child = new java.net.URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()}, loader);
        Class<?> generated = child.loadClass(qualified);
        return generated.getConstructor(List.class).newInstance(warnings);
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
