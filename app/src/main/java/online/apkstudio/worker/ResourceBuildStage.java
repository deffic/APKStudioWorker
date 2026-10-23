package online.apkstudio.worker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * First REAL stage of direct APK compilation: AAPT2 compile + link.
 * Produces an UNSIGNED, RESOURCE-ONLY APK and generated R.java, never a deployable APK.
 * Does not execute Gradle scripts or alter the imported source tree.
 *
 * Pure Java with injected process runner to verify all commands on a host JDK.
 */
final class ResourceBuildStage {
    private static final int MAX_RES_FILES = 4000;
    private static final long MAX_TOTAL_RES_BYTES = 200_000_000L;
    private static final int COMPILE_TIMEOUT_SECONDS = 30;
    private static final int LINK_TIMEOUT_SECONDS = 120;

    interface Runner {
        String run(List<String> command, File workingDirectory, int timeoutSeconds) throws Exception;
    }

    static final class Result {
        final File resourceApk;
        final File generatedJava;
        final int compiledResourceCount;
        final String log;

        Result(File resourceApk, File generatedJava, int count, String log) {
            this.resourceApk = resourceApk;
            this.generatedJava = generatedJava;
            this.compiledResourceCount = count;
            this.log = log;
        }

        String summary() {
            return "AAPT2 скомпилировал ресурсов: " + compiledResourceCount +
                ". Создан неподписанный APK с ресурсами и R.java. " +
                "DEX и полноценная APK-сборка ещё требуются.";
        }
    }

    private ResourceBuildStage() { }

    static Result compileAndLink(File projectRoot, File aapt2, File androidJar,
                                 File work, Runner runner) throws Exception {
        if (projectRoot == null || !projectRoot.isDirectory()) throw new IOException("Нет исходников проекта.");
        if (aapt2 == null || !aapt2.isFile() || !aapt2.canExecute()) {
            throw new IOException("Проверенный исполняемый AAPT2 не найден в APK Studio.");
        }
        if (androidJar == null || !androidJar.isFile()) {
            throw new IOException("Совместимая Android-платформа для ресурсов не найдена.");
        }
        File source = new File(projectRoot, "app/src/main");
        File manifest = new File(source, "AndroidManifest.xml");
        if (!manifest.isFile()) throw new IOException("В модуле app отсутствует AndroidManifest.xml.");

        Path root = projectRoot.getCanonicalFile().toPath();
        if (!manifest.getCanonicalFile().toPath().startsWith(root) ||
            Files.isSymbolicLink(manifest.toPath())) {
            throw new IOException("Манифест находится вне исходников проекта.");
        }
        File resDir = new File(source, "res");
        List<File> resources = resources(resDir, root);

        // Build outputs must never overwrite the imported project's source files.
        Path output = work.getCanonicalFile().toPath();
        if (output.startsWith(root) || root.startsWith(output)) {
            throw new IOException("Временная папка сборки должна находиться вне каталога проекта.");
        }
        if (work.exists()) throw new IOException("Папка этой сборки уже существует.");
        File compiled = new File(work, "compiled");
        File generated = new File(work, "generated");
        if (!compiled.mkdirs() || !generated.mkdirs()) {
            throw new IOException("Не удалось создать временные каталоги сборки.");
        }

        StringBuilder log = new StringBuilder();
        for (File resource : resources) {
            List<String> command = List.of(aapt2.getAbsolutePath(), "compile", resource.getAbsolutePath(),
                "-o", compiled.getAbsolutePath());
            appendLog(log, runner.run(command, work, COMPILE_TIMEOUT_SECONDS));
        }
        List<File> flats = listFlats(compiled);
        if (resources.size() > 0 && flats.isEmpty()) {
            throw new IOException("AAPT2 не создал скомпилированные ресурсы (*.flat).");
        }

        File resourceApk = new File(work, "resources-only.apk");
        List<String> command = new ArrayList<>();
        Collections.addAll(command, aapt2.getAbsolutePath(), "link", "-o",
            resourceApk.getAbsolutePath(), "-I", androidJar.getAbsolutePath(),
            "--manifest", manifest.getAbsolutePath(), "--java", generated.getAbsolutePath());
        for (File flat : flats) {
            command.add("-R");
            command.add(flat.getAbsolutePath());
        }
        appendLog(log, runner.run(command, work, LINK_TIMEOUT_SECONDS));
        if (!resourceApk.isFile() || resourceApk.length() == 0) {
            throw new IOException("AAPT2 не создал архив ресурсов после link.");
        }
        try (Stream<Path> stream = Files.walk(generated.toPath())) {
            if (stream.noneMatch(path -> path.getFileName().toString().equals("R.java"))) {
                throw new IOException("AAPT2 не создал R.java для исходников приложения.");
            }
        }
        return new Result(resourceApk, generated, resources.size(), log.toString());
    }

    private static List<File> resources(File resDir, Path root) throws IOException {
        if (!resDir.exists()) return List.of();
        if (!resDir.isDirectory() || Files.isSymbolicLink(resDir.toPath())) {
            throw new IOException("Некорректная папка res.");
        }
        List<File> files = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> stream = Files.walk(resDir.toPath(), 3)) {
            for (Path path : stream.sorted().collect(Collectors.toList())) {
                if (Files.isSymbolicLink(path)) throw new IOException("Ссылка внутри ресурсов запрещена.");
                if (!path.toRealPath().startsWith(root)) throw new IOException("Ресурс находится вне проекта.");
                if (!Files.isRegularFile(path)) continue;
                Path relative = resDir.toPath().relativize(path);
                if (relative.getNameCount() != 2 ||
                    relative.getName(0).toString().startsWith(".") ||
                    relative.getFileName().toString().startsWith(".")) {
                    throw new IOException("Недопустимая структура ресурсов: " + relative);
                }
                files.add(path.toFile());
                totalBytes += Files.size(path);
                if (files.size() > MAX_RES_FILES || totalBytes > MAX_TOTAL_RES_BYTES) {
                    throw new IOException("Ресурсов слишком много для сборки на телефоне.");
                }
            }
        }
        return files;
    }

    private static List<File> listFlats(File compiled) throws IOException {
        List<File> flats = new ArrayList<>();
        try (Stream<Path> stream = Files.list(compiled.toPath())) {
            for (Path path : stream.sorted(Comparator.naturalOrder()).collect(Collectors.toList())) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".flat")) {
                    flats.add(path.toFile());
                }
            }
        }
        return flats;
    }

    private static void appendLog(StringBuilder log, String message) {
        if (message == null || message.isEmpty() || log.length() >= 15_000) return;
        log.append(message, 0, Math.min(message.length(), 15_000 - log.length())).append('\n');
    }
}
