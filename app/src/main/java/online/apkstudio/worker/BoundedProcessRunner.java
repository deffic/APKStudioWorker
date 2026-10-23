package online.apkstudio.worker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes a fixed native tool with bounded time and output, no shell interpolation. */
final class BoundedProcessRunner implements ResourceBuildStage.Runner {
    private final String permittedExecutable;

    BoundedProcessRunner(File executable) throws IOException {
        if (executable == null || !executable.isFile() || !executable.canExecute()) {
            throw new IOException("Исполняемый инструмент не найден.");
        }
        permittedExecutable = executable.getCanonicalPath();
    }

    @Override public String run(List<String> arguments, File directory, int timeoutSeconds) throws Exception {
        if (arguments.isEmpty() || !new File(arguments.get(0)).getCanonicalPath().equals(permittedExecutable)) {
            throw new SecurityException("Запуск неизвестного исполняемого файла запрещён.");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 180) {
            throw new IllegalArgumentException("Недопустимый лимит запуска.");
        }
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.directory(directory);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream source = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    synchronized (output) {
                        if (output.size() < 20_000) {
                            output.write(buffer, 0, Math.min(count, 20_000 - output.size()));
                        }
                    }
                }
            } catch (IOException ignored) { }
        }, "apk-studio-compiler-log");
        reader.start();
        boolean complete;
        try { complete = process.waitFor(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException interrupt) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupt;
        }
        if (!complete) process.destroyForcibly();
        reader.join(2000);
        String log;
        synchronized (output) { log = new String(output.toByteArray(), StandardCharsets.UTF_8); }
        if (!complete) throw new IOException("Инструмент превысил лимит " + timeoutSeconds + " секунд: " + log);
        if (process.exitValue() != 0) {
            throw new IOException("Ошибка AAPT2 (" + process.exitValue() + "): " + log);
        }
        return log;
    }
}
