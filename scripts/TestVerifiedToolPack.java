package online.apkstudio.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestVerifiedToolPack {
    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("apkstudio-toolpack-test-");
        try {
            Map<String, byte[]> valid = new LinkedHashMap<>();
            valid.put("toolpack.properties", bytes("format=apk-studio-toolpack-v1\nid=java-compiler\n"));
            valid.put("lib/hello.jar", bytes("test jar"));
            valid.put("platform/android.jar", bytes("platform"));
            byte[] archive = makeZip(valid);
            String digest = sha(archive);

            Path installed = VerifiedToolPack.install(new ByteArrayInputStream(archive), root.toFile(), digest).toPath();
            require(Files.readString(installed.resolve("lib/hello.jar")).equals("test jar"), "round trip");
            require(VerifiedToolPack.isInstalled(installed.toFile(), digest), "marker");
            require(installed.equals(VerifiedToolPack.install(
                new ByteArrayInputStream(new byte[0]), root.toFile(), digest).toPath()), "idempotent");

            byte[] changed = makeZip(Map.of("lib/other.jar", bytes("tampered")));
            reject(() -> VerifiedToolPack.install(new ByteArrayInputStream(changed),
                root.toFile(), sha(bytes("different approved archive"))), "mismatched pinned digest");

            checkRejected(root, "../escape.jar", "path traversal");
            checkRejected(root, "lib/tool.so", "untrusted native executable");
            checkRejected(root, "lib/launch.sh", "untrusted shell script");
            checkRejected(root, "lib/../../escape.jar", "deep path traversal");

            byte[] noManifest = makeZip(Map.of("lib/tool.jar", bytes("no manifest")));
            reject(() -> VerifiedToolPack.install(new ByteArrayInputStream(noManifest),
                root.toFile(), sha(noManifest)), "missing manifest");

            require(!Files.exists(root.resolve("escape.jar")), "no Zip Slip write");
            try (var files = Files.list(root)) {
                require(files.count() == 1, "temporary files cleaned on failure");
            }
            System.out.println("VerifiedToolPack tests OK");
        } finally { delete(root); }
    }

    private static void checkRejected(Path root, String invalidPath, String label) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("toolpack.properties", bytes("format=apk-studio-toolpack-v1\nid=test-pack\n"));
        entries.put(invalidPath, bytes("UNTRUSTED"));
        byte[] archive = makeZip(entries);
        reject(() -> VerifiedToolPack.install(new ByteArrayInputStream(archive),
            root.toFile(), sha(archive)), label);
    }

    private static byte[] makeZip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var item : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String sha(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static void reject(Throwing action, String name) throws Exception {
        try { action.run(); }
        catch (SecurityException | java.io.IOException expected) { return; }
        throw new AssertionError("Accepted " + name);
    }

    private static void require(boolean predicate, String error) {
        if (!predicate) throw new AssertionError(error);
    }

    private static void delete(Path folder) throws Exception {
        try (var paths = Files.walk(folder)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private interface Throwing { void run() throws Exception; }
}
