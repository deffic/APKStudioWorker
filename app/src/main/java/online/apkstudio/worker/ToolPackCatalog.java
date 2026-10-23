package online.apkstudio.worker;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

/** A first-party, offline delivery slot for pinned compiler jars and Android platform data. */
final class ToolPackCatalog {
    private static final String BUNDLED_ASSET = "toolpacks/android-compiler.zip";

    // Intentionally empty until an independently verified, license-reviewed compiler pack
    // is actually embedded in the APK. Never accept a hash supplied by the server/job.
    private static final String APPROVED_SHA256 = "";

    private ToolPackCatalog() { }

    static String status(Context context) {
        if (APPROVED_SHA256.isEmpty()) {
            return "✓ Безопасный установщик инструментов готов. " +
                "Проверенный пакет Kotlin / D8 / Android API ещё не включён в приложение.";
        }
        File root = new File(context.getFilesDir(), "verified-toolpacks");
        File folder = new File(root, APPROVED_SHA256);
        return VerifiedToolPack.isInstalled(folder, APPROVED_SHA256)
            ? "✓ Пакет данных компиляторов установлен и проверен по SHA-256. " +
                "Наличие файлов ещё не означает работоспособность полного сборщика."
            : "! Проверенный пакет инструментов находится в APK, но ещё не установлен.";
    }

    static File platformJar(Context context) {
        if (!APPROVED_SHA256.matches("[a-f0-9]{64}")) return null;
        File folder = new File(new File(context.getFilesDir(), "verified-toolpacks"), APPROVED_SHA256);
        if (!VerifiedToolPack.isInstalled(folder, APPROVED_SHA256)) return null;
        File jar = new File(folder, "platform/android.jar");
        return jar.isFile() ? jar : null;
    }

    static File installBundled(Context context) throws Exception {
        if (!APPROVED_SHA256.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Проверенный пакет инструментов ещё не опубликован. " +
                "Приложение не загружает неподписанные компиляторы из интернета.");
        }
        // Use an APK asset, not a downloaded executable, to avoid third-party build services.
        try (InputStream input = context.getAssets().open(BUNDLED_ASSET)) {
            return VerifiedToolPack.install(input,
                new File(context.getFilesDir(), "verified-toolpacks"), APPROVED_SHA256);
        }
    }
}
