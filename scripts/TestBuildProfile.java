package online.apkstudio.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Runs on the host JDK: javac -d /tmp/<temp> BuildProfile.java TestBuildProfile.java */
public final class TestBuildProfile {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apk-studio-build-profile-");
        try {
            Path module = Files.createDirectories(root.resolve("app/src/main/java/example"));
            Files.createDirectories(root.resolve("app/src/main/res/values"));
            Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
            Files.writeString(root.resolve("app/build.gradle.kts"),
                "plugins { id(\"org.jetbrains.kotlin.android\"); " +
                "id(\"org.jetbrains.kotlin.plugin.compose\") }\n" +
                "android { namespace = \"example.test\"; compileSdk = 35; " +
                "defaultConfig { applicationId = \"example.demo\"; minSdk = 26 }; " +
                "buildFeatures { compose = true } }\n");
            Files.writeString(module.resolve("MainActivity.kt"), "package example\nclass MainActivity {}");
            Files.writeString(root.resolve("app/src/main/res/values/strings.xml"),
                "<resources><string name=\"hello\">world</string></resources>");

            BuildProfile profile = BuildProfile.inspect(root.toFile());
            check(profile.needsKotlin(), "Kotlin must be detected");
            check(profile.compose, "Compose must be detected");
            check(profile.kotlinFiles == 1, "Kotlin file count");
            check(profile.resourceFiles == 1, "resource count");
            check(profile.javaFiles == 0, "Java file count");
            check(profile.applicationId.equals("example.demo"), "app id");
            check(profile.minSdk == 26 && profile.compileSdk == 35, "SDK versions");
            check(profile.gradleWrapper, "wrapper must be detected");
            check(profile.modules.size() == 1 && profile.modules.get(0).equals("app"), "module");

            Files.writeString(root.resolve("app/build.gradle.kts"),
                "plugins { id(\"com.android.application\") }\nandroid { " +
                "namespace = \"example.onlyjava\"; compileSdk = 35; " +
                "defaultConfig { minSdk = 28 } }", StandardCharsets.UTF_8);
            Files.delete(module.resolve("MainActivity.kt"));
            Files.writeString(module.resolve("MainActivity.java"), "package example; class MainActivity {}");
            profile = BuildProfile.inspect(root.toFile());
            check(!profile.needsKotlin() && !profile.compose, "Java-only profile");
            check(profile.javaFiles == 1 && profile.kotlinFiles == 0, "Java file count");
            check(profile.applicationId.equals("example.onlyjava"), "namespace fallback");

            System.out.println("BuildProfile tests: OK");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void check(boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
    }
}
