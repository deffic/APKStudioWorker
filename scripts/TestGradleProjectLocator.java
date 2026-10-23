package online.apkstudio.worker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Host tests for regular GitHub ZIPs, Kotlin DSL and missing Gradle wrappers. */
public final class TestGradleProjectLocator {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("apk-studio-import-test-");
        try {
            Path githubZip = Files.createDirectory(temp.resolve("github"));
            Path kotlinRoot = Files.createDirectory(githubZip.resolve("TradingHelperAI-main"));
            Files.createFile(kotlinRoot.resolve("settings.gradle.kts"));
            Files.createFile(kotlinRoot.resolve("build.gradle.kts"));
            Path module = Files.createDirectory(kotlinRoot.resolve("app"));
            Files.createFile(module.resolve("build.gradle.kts"));
            equal(kotlinRoot.toFile(), GradleProjectLocator.findRoot(githubZip.toFile()),
                "GitHub ZIP with Kotlin DSL and no gradlew");
            equal(kotlinRoot.toFile(), GradleProjectLocator.findRoot(kotlinRoot.toFile()),
                "Gradle sources at archive root");

            Path groovy = Files.createDirectory(temp.resolve("groovy"));
            Files.createFile(groovy.resolve("settings.gradle"));
            Path groovyModule = Files.createDirectory(groovy.resolve("app"));
            Files.createFile(groovyModule.resolve("build.gradle"));
            equal(groovy.toFile(), GradleProjectLocator.findRoot(groovy.toFile()),
                "Groovy Gradle project with module build file");

            Path impostor = Files.createDirectory(temp.resolve("impostor"));
            Files.createFile(impostor.resolve("gradlew"));
            reject(impostor.toFile(), "A lone gradlew must not be accepted");

            Path unrelated = Files.createDirectory(temp.resolve("unrelated"));
            Files.createFile(unrelated.resolve("build.gradle.kts"));
            reject(unrelated.toFile(), "Build file without settings must not be accepted");

            Path ambiguous = Files.createDirectory(temp.resolve("ambiguous"));
            Path p1 = Files.createDirectory(ambiguous.resolve("one"));
            Files.createFile(p1.resolve("settings.gradle.kts"));
            Files.createFile(p1.resolve("build.gradle.kts"));
            Files.createDirectory(ambiguous.resolve("two"));
            reject(ambiguous.toFile(), "Multiple top-level folders require an explicit root");

            System.out.println("GradleProjectLocator: six checks passed");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private static void reject(File root, String why) throws IOException {
        try {
            GradleProjectLocator.findRoot(root);
            throw new AssertionError(why);
        } catch (IOException expected) { }
    }

    private static void equal(File expected, File actual, String why) {
        if (!expected.equals(actual)) throw new AssertionError(why);
    }
}
