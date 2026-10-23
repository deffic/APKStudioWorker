package online.apkstudio.worker;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** First stage of a phone-local build pipeline: detect and exercise native AAPT2. */
final class LocalToolchain {
    private static final int AAPT2_MIN_API = 30;
    private static final String AAPT2 = "libaapt2.so";

    private LocalToolchain() { }

    static File nativeAapt2(Context context) {
        String folder = context.getApplicationInfo().nativeLibraryDir;
        if (folder == null) return null;
        File binary = new File(folder, AAPT2);
        return binary.isFile() && binary.canExecute() ? binary : null;
    }

    static String readiness(Context context, BuildProfile project) {
        StringBuilder message = new StringBuilder();
        message.append("Android API ").append(Build.VERSION.SDK_INT)
            .append(" · ABI ").append(Build.SUPPORTED_ABIS.length == 0 ? "?" : Build.SUPPORTED_ABIS[0]);

        if (Build.VERSION.SDK_INT < AAPT2_MIN_API) {
            message.append("\n✕ Требуется Android 11+ для запланированной версии AAPT2.");
        } else {
            message.append("\n✓ Системная версия Android поддерживается.");
        }

        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
        message.append(arm64 ? "\n✓ ARM64 поддерживается." : "\n! Нужна сборка AAPT2 под архитектуру телефона.");

        message.append(nativeAapt2(context) == null
            ? "\n✕ AAPT2 пока не включён в APK Studio Worker."
            : "\n✓ Исполняемый AAPT2 найден в библиотеке приложения.");

        message.append("\n✕ In-process компилятор Java / D8 / apksig ещё не подключены.");
        if (project != null && project.needsKotlin()) {
            message.append("\n✕ Для этого проекта необходим Kotlin-компилятор.");
        }
        if (project != null && project.compose) {
            message.append("\n✕ Для проекта дополнительно необходим Compose compiler plugin.");
        }
        message.append("\n\nНа данном этапе самостоятельная сборка APK ещё недоступна.");
        return message.toString();
    }

    static String aapt2SelfTest(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < AAPT2_MIN_API) {
            throw new IllegalStateException("AAPT2 требует Android 11 или новее.");
        }
        File aapt2 = nativeAapt2(context);
        if (aapt2 == null) {
            throw new IllegalStateException("AAPT2 отсутствует внутри APK Studio Worker. " +
                "Подключение собственного ARM64-инструмента — следующий шаг.");
        }

        File work = new File(context.getCacheDir(), "aapt2-self-test");
        File res = new File(work, "res/values");
        File out = new File(work, "compiled");
        if ((!res.mkdirs() && !res.isDirectory()) || (!out.mkdirs() && !out.isDirectory())) {
            throw new IllegalStateException("Не удалось подготовить временные файлы AAPT2.");
        }
        File xml = new File(res, "strings.xml");
        try (FileOutputStream stream = new FileOutputStream(xml, false)) {
            stream.write("<resources><string name=\"app_name\">APK Studio</string></resources>"
                .getBytes(StandardCharsets.UTF_8));
        }

        // Passing --dir would produce a ZIP archive instead of .flat files;
        // compile the individual resource for a deterministic smoke test.
        ProcessBuilder processBuilder = new ProcessBuilder(aapt2.getAbsolutePath(), "compile",
            "-o", out.getAbsolutePath(), xml.getAbsolutePath());
        processBuilder.directory(work);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[2048];
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    synchronized (output) {
                        if (output.size() < 24_000) {
                            output.write(bytes, 0, Math.min(count, 24_000 - output.size()));
                        }
                    }
                }
            } catch (Exception ignored) { }
        }, "aapt2-self-test-output");
        reader.start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(2_000);
            throw new IllegalStateException("AAPT2 не завершил пробную компиляцию за 20 секунд.");
        }
        reader.join(2_000);
        String log;
        synchronized (output) { log = new String(output.toByteArray(), StandardCharsets.UTF_8); }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("AAPT2: код " + process.exitValue() + ". " + log);
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(out.toPath())) {
            if (files.noneMatch(path -> path.getFileName().toString().endsWith(".flat"))) {
                throw new IllegalStateException("AAPT2 не создал скомпилированные ресурсы: " + log);
            }
        }
        return "AAPT2 успешно скомпилировал тестовый Android-ресурс. Это проверка " +
            "ресурсов, а не готовая сборка APK.";
    }
}
