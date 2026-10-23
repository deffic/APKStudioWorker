package online.apkstudio.worker;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ProjectStore {
    private static final long MAX_UNPACKED_BYTES = 300_000_000L;
    private static final long MAX_FILE_BYTES = 70_000_000L;

    static File project(Context context) {
        String name = context.getSharedPreferences("project", Context.MODE_PRIVATE).getString("folder", "");
        if (name.isBlank() || !name.matches("TradingHelperAI-[0-9]+")) return null;
        File root = new File(context.getFilesDir(), "projects");
        File project = new File(root, name);
        return project.isDirectory() ? project : null;
    }

    static File importZip(Context context, Uri uri) throws Exception {
        File destination = new File(new File(context.getFilesDir(), "projects"),
            "TradingHelperAI-" + System.currentTimeMillis());
        if (!destination.mkdirs()) throw new IllegalStateException("Не удалось создать каталог проекта.");
        byte[] buffer = new byte[32 * 1024];
        int entries = 0;
        long total = 0;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalStateException("Не удалось открыть ZIP.");
            try (ZipInputStream zip = new ZipInputStream(stream)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 10_000) throw new IllegalStateException("Слишком много файлов в архиве.");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("\0")) throw new IllegalStateException("Недопустимый путь в архиве.");
                    File output = new File(destination, name);
                    if (!output.getCanonicalPath().startsWith(destination.getCanonicalPath() + File.separator)) {
                        throw new IllegalStateException("Архив содержит путь вне проекта.");
                    }
                    if (entry.isDirectory()) {
                        if (!output.mkdirs() && !output.isDirectory()) throw new IllegalStateException("Не создан каталог.");
                        continue;
                    }
                    if (!output.getParentFile().mkdirs() && !output.getParentFile().isDirectory()) {
                        throw new IllegalStateException("Не создан каталог файла.");
                    }
                    long fileBytes = 0;
                    try (FileOutputStream file = new FileOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            fileBytes += read;
                            total += read;
                            if (fileBytes > MAX_FILE_BYTES || total > MAX_UNPACKED_BYTES) {
                                throw new IllegalStateException("Архив слишком большой для телефона.");
                            }
                            file.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            }
        }

        File actual = destination;
        if (!new File(actual, "gradlew").isFile()) {
            File[] children = destination.listFiles(File::isDirectory);
            if (children != null && children.length == 1 && new File(children[0], "gradlew").isFile()) {
                actual = children[0];
            }
        }
        if (!new File(actual, "gradlew").isFile()) {
            throw new IllegalStateException("В ZIP не найден Gradle-проект с файлом gradlew.");
        }
        if (actual != destination) {
            // Preserve the imported archive; use its nested root as the active project.
            context.getSharedPreferences("project", Context.MODE_PRIVATE).edit()
                .putString("folder", destination.getName()).putString("nested", actual.getName()).apply();
        } else {
            context.getSharedPreferences("project", Context.MODE_PRIVATE).edit()
                .putString("folder", destination.getName()).remove("nested").apply();
        }
        return actual;
    }

    static File activeRoot(Context context) {
        File root = project(context);
        if (root == null) return null;
        String nested = context.getSharedPreferences("project", Context.MODE_PRIVATE)
            .getString("nested", "");
        if (nested.isEmpty()) return root;
        if (nested.contains("/") || nested.contains("\\") || nested.equals("..")) return null;
        File folder = new File(root, nested);
        return new File(folder, "gradlew").isFile() ? folder : null;
    }

    static String applyEdits(Context context, File project, String jobId, JSONArray edits) throws Exception {
        if (!jobId.matches("[a-f0-9-]{36}") || edits.length() > 30) {
            throw new IllegalArgumentException("Неверный пакет правок.");
        }
        long total = 0;
        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.getJSONObject(i);
            String relative = edit.getString("path");
            String content = edit.getString("content");
            if (relative.length() > 180 || !relative.matches("[A-Za-z0-9_./+\\-]+") ||
                relative.startsWith("/") || relative.contains("//")) throw new IllegalArgumentException("Неверный путь правки.");
            for (String part : relative.split("/")) {
                if (part.isBlank() || part.equals(".") || part.equals("..") || part.equals(".git") ||
                    part.equals(".gradle") || part.equals("build") || part.equals(".apk-studio-backups")) {
                    throw new IllegalArgumentException("Защищённый путь правки.");
                }
            }
            String basename = relative.substring(relative.lastIndexOf('/') + 1);
            if (basename.equals("local.properties") || basename.equals("gradle.properties") ||
                basename.matches(".*\\.(jks|keystore|pem|key)")) throw new IllegalArgumentException("Защищённый файл.");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            total += bytes.length;
            if (total > 200_000) throw new IllegalArgumentException("Слишком большой пакет правок.");
            File target = new File(project, relative);
            if (!target.getCanonicalPath().startsWith(project.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("Путь выходит за пределы проекта.");
            }
            if (target.exists()) {
                File backup = new File(context.getFilesDir(), "backups/" + jobId + "/" + relative);
                if (!backup.getParentFile().mkdirs() && !backup.getParentFile().isDirectory()) {
                    throw new IllegalStateException("Не удалось создать резервную копию.");
                }
                try (java.io.FileInputStream from = new java.io.FileInputStream(target);
                     FileOutputStream to = new FileOutputStream(backup)) {
                    byte[] tmp = new byte[8192];
                    int length;
                    while ((length = from.read(tmp)) != -1) to.write(tmp, 0, length);
                }
            }
            if (!target.getParentFile().mkdirs() && !target.getParentFile().isDirectory()) {
                throw new IllegalStateException("Не создан каталог правки.");
            }
            try (FileOutputStream file = new FileOutputStream(target)) { file.write(bytes); }
        }
        return "Применено файлов: " + edits.length();
    }

    static String toolchainStatus(Context context) {
        return "Движок не включён в v0.1. Совместимые JDK / Gradle / AAPT2 должны быть встроены в APK: Android 10+ запрещает запуск исполняемых файлов из записываемой папки приложения.";
    }

    static boolean hasToolchain(Context context) {
        // Never mark the Android toolchain ready on the basis of arbitrary downloaded
        // executables in writable app storage; Android 10+ enforces W^X restrictions.
        return false;
    }
}
