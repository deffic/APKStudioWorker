package online.apkstudio.worker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs an explicitly SHA-256 pinned, DATA-ONLY compiler pack into app-private storage.
 * Never launches files from this directory: Android native tools must be packaged in
 * nativeLibraryDir at APK build time, not downloaded as executable files.
 *
 * No network code and no Android framework dependencies, so extraction can be tested on JDK.
 */
final class VerifiedToolPack {
    private static final long MAX_ARCHIVE = 350_000_000L;
    private static final long MAX_UNPACKED = 850_000_000L;
    private static final long MAX_SINGLE_FILE = 220_000_000L;
    private static final int MAX_ENTRIES = 5000;
    private static final String MARKER = ".verified-sha256";
    private static final String FORMAT = "apk-studio-toolpack-v1";

    private VerifiedToolPack() { }

    static synchronized File install(InputStream input, File root, String expectedSha) throws Exception {
        if (input == null) throw new IOException("Не удалось открыть пакет инструментов.");
        if (expectedSha == null || !expectedSha.matches("[a-f0-9]{64}")) {
            throw new SecurityException("Хеш пакета не закреплён в APK Studio. Установка запрещена.");
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Не удалось подготовить папку инструментов.");
        }
        File target = new File(root, expectedSha);
        if (isInstalled(target, expectedSha)) return target;
        if (target.exists()) {
            throw new SecurityException("Папка пакета уже занята непроверенными данными.");
        }

        String random = UUID.randomUUID().toString();
        File archive = new File(root, "incoming-" + random + ".zip");
        File staging = new File(root, "staging-" + random);
        try {
            String digest = copyAndHash(input, archive);
            if (!expectedSha.equals(digest)) {
                throw new SecurityException("SHA-256 пакета не совпадает с доверенным значением.");
            }
            if (!staging.mkdir()) throw new IOException("Не удалось создать временную папку.");
            unpack(archive, staging);
            checkMetadata(staging);
            try (FileOutputStream output = new FileOutputStream(new File(staging, MARKER))) {
                output.write(expectedSha.getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            if (!staging.renameTo(target)) {
                throw new IOException("Не удалось завершить установку инструментов.");
            }
            return target;
        } finally {
            // Only these two uniquely named, app-created paths may be cleaned up.
            if (archive.exists() && !archive.delete()) archive.deleteOnExit();
            if (staging.exists()) deleteTree(staging);
        }
    }

    static boolean isInstalled(File folder, String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}") || !folder.isDirectory()) return false;
        File marker = new File(folder, MARKER);
        File metadata = new File(folder, "toolpack.properties");
        if (!marker.isFile() || !metadata.isFile() || marker.length() != 64) return false;
        try (InputStream in = new FileInputStream(marker)) {
            byte[] content = new byte[64];
            int count = 0;
            while (count < content.length) {
                int read = in.read(content, count, content.length - count);
                if (read == -1) return false;
                count += read;
            }
            return digest.equals(new String(content, StandardCharsets.US_ASCII));
        } catch (IOException ignored) { return false; }
    }

    private static String copyAndHash(InputStream input, File output) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long length = 0;
        try (FileOutputStream file = new FileOutputStream(output)) {
            byte[] block = new byte[32 * 1024];
            int n;
            while ((n = input.read(block)) != -1) {
                if (n == 0) continue;
                length += n;
                if (length > MAX_ARCHIVE) throw new IOException("Архив инструментов слишком большой.");
                sha.update(block, 0, n);
                file.write(block, 0, n);
            }
            file.getFD().sync();
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static void unpack(File archive, File staging) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        Set<String> names = new HashSet<>();
        int entries = 0;
        long unpacked = 0;
        String rootPath = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Слишком много файлов в пакете.");
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.indexOf('\0') >= 0 ||
                    name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.contains("//")) {
                    throw new SecurityException("Недопустимый путь внутри пакета.");
                }
                for (String part : name.split("/")) {
                    if (part.equals(".") || part.equals("..")) {
                        throw new SecurityException("Путь пакета выходит за пределы папки.");
                    }
                }
                if (!names.add(name)) throw new SecurityException("Повторяющийся файл внутри пакета.");
                File destination = new File(staging, name);
                if (!destination.getCanonicalPath().startsWith(rootPath)) {
                    throw new SecurityException("Путь пакета выходит за пределы папки.");
                }
                if (entry.isDirectory()) {
                    if (!destination.isDirectory() && !destination.mkdirs()) {
                        throw new IOException("Не удалось создать папку пакета.");
                    }
                    continue;
                }
                // Restrict tool packs to inert JVM/Android data, never downloaded native binaries.
                if (!(name.equals("toolpack.properties") || name.matches(
                    "(?:lib|platform|meta)/[A-Za-z0-9_./+\\-]+\\.(?:jar|dex|json|xml|txt|properties|cfg|pro|bin)"))) {
                    throw new SecurityException("Недопустимый тип файла в пакете: " + name);
                }
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать папку файла.");
                }
                long fileLength = 0;
                try (FileOutputStream to = new FileOutputStream(destination)) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        if (n == 0) continue;
                        fileLength += n;
                        unpacked += n;
                        if (fileLength > MAX_SINGLE_FILE || unpacked > MAX_UNPACKED) {
                            throw new IOException("Распакованный пакет слишком большой.");
                        }
                        to.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void checkMetadata(File staging) throws IOException {
        File file = new File(staging, "toolpack.properties");
        if (!file.isFile() || file.length() > 4096) {
            throw new IOException("Пакет не содержит допустимые метаданные.");
        }
        Properties info = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { info.load(input); }
        if (!FORMAT.equals(info.getProperty("format")) ||
            !info.getProperty("id", "").matches("[a-z0-9-]{2,60}")) {
            throw new IOException("Несовместимый формат пакета инструментов.");
        }
    }

    private static void deleteTree(File path) {
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!path.delete()) path.deleteOnExit();
    }
}
