import java.io.File;
import java.nio.file.Files;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

public class RbrExperiment {
    public static void main(String[] args) throws Exception {
        File decompilerJar = new File("D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar");
        File fixtureClasses = new File("D:/Work/k2j/corpus/app/build/classes/kotlin/main");
        File outDir = new File("build/rbr-experiment");
        deleteRecursively(outDir);
        outDir.mkdirs();

        if (!decompilerJar.exists()) throw new IllegalStateException("decompiler jar missing");
        if (!fixtureClasses.exists()) throw new IllegalStateException("fixture classes missing");

        java.util.List<String> candidates = new java.util.ArrayList<>();
        Files.walk(fixtureClasses.toPath())
            .filter(p -> p.toString().endsWith(".class"))
            .sorted()
            .forEach(p -> candidates.add(p.toAbsolutePath().toString()));
        System.out.println("input classes: " + candidates.size());

        Pattern bridgeMarker = Pattern.compile("// \\$FF: bridge method");
        Pattern objectGet = Pattern.compile("public Object get\\(\\)");
        Pattern covariantGet = Pattern.compile("public IEntityId getId\\(\\)");

        for (String rbr : new String[] {"1", "0"}) {
            java.util.Map<String, Object> options = new java.util.HashMap<>();
            options.put("rbr", rbr);
            File sink = new File(outDir, "rbr" + rbr);
            sink.mkdirs();
            int[] classCount = {0};
            org.jetbrains.java.decompiler.main.extern.IResultSaver saver =
                new org.jetbrains.java.decompiler.main.extern.IResultSaver() {
                    @Override public void saveFolder(String path) {}
                    @Override public void copyFile(String source, String target, String entry) {}
                    @Override public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
                        classCount[0]++;
                        File target = new File(sink, entryName);
                        if (target.getParentFile() != null) target.getParentFile().mkdirs();
                        write(target, content);
                    }
                    @Override public void createArchive(String path, String archive, Manifest manifest) {}
                    @Override public void saveDirEntry(String path, String archive, String entry) {}
                    @Override public void copyEntry(String source, String path, String archive, String entry) {}
                    @Override public void saveClassEntry(String path, String archive, String qualifiedName, String entryName, String content) {
                        classCount[0]++;
                        File target = new File(sink, entryName);
                        if (target.getParentFile() != null) target.getParentFile().mkdirs();
                        write(target, content);
                    }
                    @Override public void closeArchive(String path, String archive) {}
                };
            org.jetbrains.java.decompiler.main.extern.IBytecodeProvider provider =
                (externalPath, internalPath) -> Files.readAllBytes(new File(externalPath).toPath());
            org.jetbrains.java.decompiler.main.extern.IFernflowerLogger logger =
                new org.jetbrains.java.decompiler.main.extern.IFernflowerLogger() {
                    @Override public void writeMessage(String message, org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity severity) {
                        if (severity == org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity.ERROR) {
                            System.out.println("[" + rbr + "] FF-ERROR: " + message);
                        }
                    }
                    @Override public void writeMessage(String message, org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity severity, Throwable t) {
                        if (severity == org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity.ERROR) {
                            System.out.println("[" + rbr + "] FF-ERROR: " + message + " (" + t.getMessage() + ")");
                        }
                    }
                };
            org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler decompiler =
                new org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler(provider, saver, options, logger);
            for (String candidate : candidates) {
                decompiler.addSource(new File(candidate));
            }
            decompiler.decompileContext();

            int bridgeMarkers = 0, objectGetBridges = 0, covariantBridges = 0;
            Files.walk(sink.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        String text = new String(Files.readAllBytes(p));
                        synchronized (RbrExperiment.class) {
                            counts[0] += (int) bridgeMarker.matcher(text).results().count();
                            counts[1] += (int) objectGet.matcher(text).results().count();
                            counts[2] += (int) covariantGet.matcher(text).results().count();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            System.out.println("rbr=" + rbr + ": classes=" + classCount[0]
                + " bridgeMarkers=" + counts[0]
                + " objectGetBridges=" + counts[1]
                + " covariantBridges=" + counts[2]);
        }
    }

    static int[] counts = new int[3];

    static void write(File target, String content) {
        try {
            Files.write(target.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
