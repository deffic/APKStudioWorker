package online.apkstudio.worker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only Android project inspection. No Gradle scripts are executed and no
 * files are changed. The result describes prerequisites, not build success.
 * Kept free of Android framework types so it can be tested on a desktop JDK.
 */
final class BuildProfile {
    private static final int MAX_SCRIPT_BYTES = 256_000;
    private static final int MAX_SOURCE_FILES = 30_000;
    private static final int MAX_MODULES = 30;

    final List<String> modules;
    final int javaFiles;
    final int kotlinFiles;
    final int resourceFiles;
    final boolean kotlinPlugin;
    final boolean compose;
    final boolean gradleWrapper;
    final int minSdk;
    final int compileSdk;
    final String applicationId;
    final String namespace;

    private BuildProfile(List<String> modules, int javaFiles, int kotlinFiles, int resourceFiles,
                         boolean kotlinPlugin, boolean compose, boolean gradleWrapper,
                         int minSdk, int compileSdk, String applicationId, String namespace) {
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        this.javaFiles = javaFiles;
        this.kotlinFiles = kotlinFiles;
        this.resourceFiles = resourceFiles;
        this.kotlinPlugin = kotlinPlugin;
        this.compose = compose;
        this.gradleWrapper = gradleWrapper;
        this.minSdk = minSdk;
        this.compileSdk = compileSdk;
        this.applicationId = applicationId;
        this.namespace = namespace;
    }

    boolean needsKotlin() { return kotlinPlugin || kotlinFiles > 0 || compose; }

    static BuildProfile inspect(File project) throws IOException {
        if (project == null || !project.isDirectory()) {
            throw new IOException("Сначала импортируй ZIP Android-проекта.");
        }
        Path root = project.getCanonicalFile().toPath();
        File[] children = project.listFiles(File::isDirectory);
        List<File> modules = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if (name.startsWith(".") || name.equals("build") || name.equals("gradle")) continue;
                if (new File(child, "build.gradle.kts").isFile() || new File(child, "build.gradle").isFile()) {
                    if (modules.size() >= MAX_MODULES) throw new IOException("Слишком много модулей проекта.");
                    modules.add(child);
                }
            }
        }
        if (modules.isEmpty()) modules.add(project);

        int javaFiles = 0;
        int kotlinFiles = 0;
        int resources = 0;
        boolean kotlin = false;
        boolean compose = false;
        int minSdk = 0;
        int compileSdk = 0;
        String applicationId = "";
        String namespace = "";
        List<String> moduleNames = new ArrayList<>();

        for (File module : modules) {
            Path modulePath = module.getCanonicalFile().toPath();
            if (!modulePath.startsWith(root)) throw new IOException("Некорректная папка модуля.");
            moduleNames.add(module == project ? "." : module.getName());
            String script = readScript(module);
            kotlin |= containsAny(script, "org.jetbrains.kotlin.android", "kotlin(\"android\")",
                "kotlin-android", "org.jetbrains.kotlin.jvm");
            compose |= containsAny(script, "org.jetbrains.kotlin.plugin.compose", "androidx.compose:",
                "androidx.compose.", "compose = true", "compose true");
            if (minSdk == 0) minSdk = readNumber(script, "minSdk(?:Version)?\\s*(?:=\\s*|\\(\\s*|\\s+)(\\d+)");
            if (compileSdk == 0) compileSdk = readNumber(script, "compileSdk(?:Version)?\\s*(?:=\\s*|\\(\\s*|\\s+)(\\d+)");
            if (applicationId.isEmpty()) applicationId = readIdentifier(script, "applicationId\\s*(?:=\\s*|\\(\\s*|\\s+)[\"']([a-zA-Z][a-zA-Z0-9_.]+)[\"']");
            if (namespace.isEmpty()) namespace = readIdentifier(script, "namespace\\s*(?:=\\s*|\\(\\s*|\\s+)[\"']([a-zA-Z][a-zA-Z0-9_.]+)[\"']");

            File sourceRoot = new File(module, "src/main");
            if (!sourceRoot.isDirectory()) continue;
            // File.walk is intentionally bounded by depth, count, and project root.
            try (java.util.stream.Stream<Path> paths = Files.walk(sourceRoot.toPath(), 15)) {
                List<Path> files = paths.filter(Files::isRegularFile)
                    .limit(MAX_SOURCE_FILES + 1L).collect(Collectors.toList());
                if (files.size() > MAX_SOURCE_FILES) throw new IOException("Слишком много исходных файлов.");
                for (Path path : files) {
                    if (!path.toRealPath().startsWith(root)) throw new IOException("Файл выходит за пределы проекта.");
                    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (filename.endsWith(".java")) javaFiles++;
                    else if (filename.endsWith(".kt")) kotlinFiles++;
                    else if (path.toString().contains(File.separator + "res" + File.separator)) resources++;
                }
            }
        }
        if (javaFiles + kotlinFiles + resources > MAX_SOURCE_FILES) {
            throw new IOException("Слишком много исходных файлов.");
        }
        if (applicationId.isEmpty()) applicationId = namespace;
        if (namespace.isEmpty()) namespace = applicationId;
        return new BuildProfile(moduleNames, javaFiles, kotlinFiles, resources,
            kotlin, compose, new File(project, "gradlew").isFile(), minSdk, compileSdk,
            applicationId.isEmpty() ? "Не определён" : applicationId,
            namespace.isEmpty() ? "Не определён" : namespace);
    }

    String describe() {
        return "Проект: " + applicationId + "\n" +
            "Namespace для R.java: " + namespace + "\n" +
            "Модули: " + String.join(", ", modules) + "\n" +
            "Java: " + javaFiles + " · Kotlin: " + kotlinFiles + "\n" +
            "Ресурсы: " + resourceFiles + "\n" +
            "Kotlin-компилятор: " + (needsKotlin() ? "обязателен" : "не требуется") + "\n" +
            "Compose-компилятор: " + (compose ? "обязателен" : "не требуется") + "\n" +
            "minSdk: " + (minSdk == 0 ? "?" : minSdk) +
            " · compileSdk: " + (compileSdk == 0 ? "?" : compileSdk) + "\n" +
            "Gradle wrapper: " + (gradleWrapper ? "найден" : "не найден");
    }

    private static String readScript(File module) throws IOException {
        File kts = new File(module, "build.gradle.kts");
        File groovy = new File(module, "build.gradle");
        File script = kts.isFile() ? kts : groovy;
        if (!script.isFile()) return "";
        if (script.length() > MAX_SCRIPT_BYTES) throw new IOException("Слишком большой файл Gradle.");
        return new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static int readNumber(String text, String regex) {
        Matcher match = Pattern.compile(regex).matcher(text);
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static String readIdentifier(String text, String regex) {
        Matcher match = Pattern.compile(regex).matcher(text);
        return match.find() ? match.group(1) : "";
    }
}
