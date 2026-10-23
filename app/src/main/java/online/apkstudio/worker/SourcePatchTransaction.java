package online.apkstudio.worker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Transactional source editing, independent of Android/Gradle. Saves all originals
 * before touching any user file, and journals created files for lossless rollback.
 */
final class SourcePatchTransaction {
    private static final String JOURNAL = "changes.tsv";
    private static final int MAX_EDITS = 30;
    private static final int MAX_BYTES = 200_000;
    private static final long MAX_ORIGINAL_BYTES = 5_000_000L;

    static final class Edit {
        final String path;
        final String content;
        Edit(String path, String content) { this.path = path; this.content = content; }
    }

    private SourcePatchTransaction() { }

    static synchronized int apply(File project, File jobBackup, List<Edit> edits) throws IOException {
        if (edits.size() > MAX_EDITS || edits.isEmpty()) {
            if (edits.isEmpty()) return 0;
            throw new IOException("Превышен лимит количества правок.");
        }
        if (!project.isDirectory() || jobBackup.exists()) {
            throw new IOException("Проект не найден или резервная копия задания уже существует.");
        }
        Set<String> unique = new HashSet<>();
        List<File> targets = new ArrayList<>();
        long total = 0;
        for (Edit edit : edits) {
            validatePath(edit.path);
            if (!unique.add(edit.path)) throw new IOException("Один и тот же файл изменён дважды.");
            if (edit.content == null) throw new IOException("Файл правки не содержит данных.");
            total += edit.content.getBytes(StandardCharsets.UTF_8).length;
            if (total > MAX_BYTES) throw new IOException("Пакет правок слишком большой.");
            targets.add(resolveWithin(project, edit.path));
        }
        if (!jobBackup.mkdirs()) throw new IOException("Не удалось создать резервную папку.");
        File originals = new File(jobBackup, "originals");
        if (!originals.mkdir()) throw new IOException("Не удалось создать папку исходников.");

        // All original files and journal entries are flushed BEFORE any replacement.
        StringBuilder journal = new StringBuilder();
        for (int i = 0; i < edits.size(); i++) {
            File target = targets.get(i);
            String relative = edits.get(i).path;
            boolean existed = target.exists();
            if (existed && !target.isFile()) throw new IOException("Правка указывает на каталог.");
            if (existed && target.length() > MAX_ORIGINAL_BYTES) {
                throw new IOException("Редактируемый файл слишком большой для безопасной резервной копии.");
            }
            if (existed) {
                File backup = new File(originals, relative);
                if (!backup.getParentFile().isDirectory() && !backup.getParentFile().mkdirs()) {
                    throw new IOException("Не удалось подготовить резервную копию.");
                }
                Files.copy(target.toPath(), backup.toPath());
            }
            journal.append(existed ? 'E' : 'N').append('\t').append(relative).append('\n');
        }
        try (FileOutputStream stream = new FileOutputStream(new File(jobBackup, JOURNAL))) {
            stream.write(journal.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }

        try {
            for (int i = 0; i < edits.size(); i++) {
                File target = resolveWithin(project, edits.get(i).path);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать каталог редактируемого файла.");
                }
                replace(target, edits.get(i).content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException failure) {
            try { rollback(project, jobBackup); }
            catch (Exception restoration) { failure.addSuppressed(restoration); }
            throw failure;
        }
        return edits.size();
    }

    static synchronized void rollback(File project, File jobBackup) throws IOException {
        File journal = new File(jobBackup, JOURNAL);
        if (!journal.isFile() || journal.length() > 10_000) {
            throw new IOException("Нет достоверного журнала для восстановления правок.");
        }
        List<String> lines = Files.readAllLines(journal.toPath(), StandardCharsets.UTF_8);
        List<File> targets = new ArrayList<>();
        List<Boolean> previouslyExisted = new ArrayList<>();
        if (lines.size() > MAX_EDITS) throw new IOException("Недопустимый журнал правок.");
        Set<String> unique = new HashSet<>();
        for (String entry : lines) {
            if (entry.length() < 3 || entry.charAt(1) != '\t' ||
                (entry.charAt(0) != 'E' && entry.charAt(0) != 'N')) {
                throw new IOException("Повреждён журнал изменений.");
            }
            String relative = entry.substring(2);
            validatePath(relative);
            if (!unique.add(relative)) throw new IOException("Повтор записи в журнале изменений.");
            File target = resolveWithin(project, relative);
            boolean existed = entry.charAt(0) == 'E';
            File original = new File(jobBackup, "originals/" + relative);
            if (existed && !original.isFile()) {
                throw new IOException("Резервная копия отсутствует: " + relative);
            }
            targets.add(target);
            previouslyExisted.add(existed);
        }
        for (int i = 0; i < targets.size(); i++) {
            File target = targets.get(i);
            if (previouslyExisted.get(i)) {
                File saved = new File(jobBackup, "originals/" + lines.get(i).substring(2));
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Не удалось восстановить каталог файла.");
                }
                replace(target, Files.readAllBytes(saved.toPath()));
            } else if (target.exists() && !target.delete()) {
                throw new IOException("Не удалось удалить созданный при правке файл.");
            }
        }
    }

    private static File resolveWithin(File project, String relative) throws IOException {
        File target = new File(project, relative);
        if (!target.getCanonicalPath().startsWith(project.getCanonicalPath() + File.separator)) {
            throw new IOException("Путь правки выходит за пределы проекта.");
        }
        Path current = project.getCanonicalFile().toPath();
        for (String part : relative.split("/")) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Символические ссылки запрещены в правках.");
        }
        return target;
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 180 ||
            !path.matches("[A-Za-z0-9_./+\\-]+") || path.startsWith("/") || path.contains("//")) {
            throw new IOException("Недопустимый путь правки.");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git") ||
                part.equals(".gradle") || part.equals("build") || part.equals(".apk-studio-backups")) {
                throw new IOException("Защищённая папка правки.");
            }
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        if (leaf.equals("local.properties") || leaf.equals("gradle.properties") ||
            leaf.matches(".*\\.(jks|keystore|pem|key)")) {
            throw new IOException("Нельзя менять файлы с секретами и локальные настройки.");
        }
    }

    private static void replace(File target, byte[] content) throws IOException {
        File temporary = File.createTempFile("apkstudio-edit-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(content);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
}
