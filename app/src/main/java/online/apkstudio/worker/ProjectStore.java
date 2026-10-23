package online.apkstudio.worker;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
        List<SourcePatchTransaction.Edit> changes = new ArrayList<>();
        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.getJSONObject(i);
            changes.add(new SourcePatchTransaction.Edit(edit.getString("path"), edit.getString("content")));
        }
        File backups = new File(context.getFilesDir(), "backups");
        if (!backups.isDirectory() && !backups.mkdirs()) throw new IllegalStateException("Не создан каталог резервных копий.");
        int changed = SourcePatchTransaction.apply(project, new File(backups, jobId), changes);
        return "Применено файлов (с возможностью отката): " + changed;
    }

    static void rollbackEdits(Context context, File project, String jobId) throws Exception {
        if (!jobId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Неверный идентификатор задания.");
        SourcePatchTransaction.rollback(project, new File(context.getFilesDir(), "backups/" + jobId));
    }

    static String toolchainStatus(Context context) {
        String aapt = LocalToolchain.nativeAapt2(context) == null
            ? "Проверка архитектуры и проекта готова. Нативный AAPT2 ещё не встроен. Компиляторы Kotlin / Compose, D8 и подпись APK — в разработке."
            : "AAPT2 обнаружен. Нажми «Проверить сборочный движок», чтобы протестировать ресурсы. Полная сборка APK пока не поддерживается.";
        return aapt + "\n" + ToolPackCatalog.status(context);
    }

    static boolean hasToolchain(Context context) {
        // Never mark the Android toolchain ready on the basis of arbitrary downloaded
        // executables in writable app storage; Android 10+ enforces W^X restrictions.
        return false;
    }
}
