package online.apkstudio.worker;

import java.io.File;
import java.io.IOException;

/** Find a Gradle project in a downloaded source ZIP without requiring gradlew. */
final class GradleProjectLocator {
    private GradleProjectLocator() { }

    static File findRoot(File extracted) throws IOException {
        if (extracted == null || !extracted.isDirectory()) {
            throw new IOException("Каталог архива отсутствует.");
        }
        if (isProjectRoot(extracted)) return extracted;

        // GitHub's Download ZIP adds a single top-level project folder.
        File[] children = extracted.listFiles(File::isDirectory);
        if (children != null && children.length == 1 && isProjectRoot(children[0])) {
            return children[0];
        }
        throw new IOException("В ZIP не найден Gradle-проект: нужны settings.gradle(.kts) " +
            "и build.gradle(.kts) в корне или модуле. Файл gradlew необязателен.");
    }

    static boolean isProjectRoot(File directory) {
        if (directory == null || !directory.isDirectory()) return false;
        if (!isRegularFile(directory, "settings.gradle") &&
            !isRegularFile(directory, "settings.gradle.kts")) return false;
        if (isRegularFile(directory, "build.gradle") ||
            isRegularFile(directory, "build.gradle.kts")) return true;
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) return false;
        for (File child : children) {
            if (isRegularFile(child, "build.gradle") ||
                isRegularFile(child, "build.gradle.kts")) return true;
        }
        return false;
    }

    private static boolean isRegularFile(File root, String name) {
        return new File(root, name).isFile();
    }
}
