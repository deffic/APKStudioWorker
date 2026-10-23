package online.apkstudio.worker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TestResourceBuildStage {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("apkstudio-aapt2-tests-");
        try {
            Path project = temp.resolve("input");
            Path res = project.resolve("app/src/main/res");
            Files.createDirectories(res.resolve("values"));
            Files.createDirectories(res.resolve("drawable"));
            Files.writeString(project.resolve("app/src/main/AndroidManifest.xml"),
                "<manifest package=\"online.apkstudio.example\"/>", StandardCharsets.UTF_8);
            Files.writeString(res.resolve("values/strings.xml"),
                "<resources><string name=\"title\">Demo</string></resources>");
            Files.write(res.resolve("drawable/icon.png"), new byte[]{1, 2, 3});
            Path nativeTool = temp.resolve("libaapt2.so");
            Files.write(nativeTool, new byte[]{10});
            nativeTool.toFile().setExecutable(true);
            Path androidJar = temp.resolve("android.jar");
            Files.write(androidJar, new byte[]{8});

            MockRunner mock = new MockRunner();
            ResourceBuildStage.Result result = ResourceBuildStage.compileAndLink(
                project.toFile(), nativeTool.toFile(), androidJar.toFile(), temp.resolve("job1").toFile(), mock);
            require(result.compiledResourceCount == 2, "two resources compiled");
            require(result.resourceApk.isFile() && result.resourceApk.length() > 0, "resource-only APK produced");
            require(result.generatedJava.isDirectory(), "Java generation folder");
            require(mock.calls.size() == 3, "two compile commands plus link");
            List<String> link = mock.calls.get(2);
            require(link.contains("link") && link.contains("--java") && link.contains("-I"), "link args");
            require(link.stream().filter("-R"::equals).count() == 2, "two .flat inputs");

            // Never let a temporary compiler folder overwrite imported source.
            expectFailure(() -> ResourceBuildStage.compileAndLink(project.toFile(), nativeTool.toFile(),
                androidJar.toFile(), project.resolve("output").toFile(), new MockRunner()), "output within sources");
            expectFailure(() -> ResourceBuildStage.compileAndLink(project.toFile(), nativeTool.toFile(),
                null, temp.resolve("job2").toFile(), new MockRunner()), "missing platform");

            Path symlink = res.resolve("values/evil.xml");
            Files.createSymbolicLink(symlink, temp.resolve("android.jar"));
            expectFailure(() -> ResourceBuildStage.compileAndLink(project.toFile(), nativeTool.toFile(),
                androidJar.toFile(), temp.resolve("job3").toFile(), new MockRunner()), "resource symlink");
            System.out.println("ResourceBuildStage tests OK");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    static final class MockRunner implements ResourceBuildStage.Runner {
        final List<List<String>> calls = new ArrayList<>();
        @Override public String run(List<String> command, File work, int timeoutSeconds) throws IOException {
            calls.add(List.copyOf(command));
            if ("compile".equals(command.get(1))) {
                Path output = Path.of(command.get(command.indexOf("-o") + 1));
                Files.write(output.resolve("test" + calls.size() + ".flat"), new byte[]{1});
            } else if ("link".equals(command.get(1))) {
                Path output = Path.of(command.get(command.indexOf("-o") + 1));
                Files.write(output, new byte[]{3});
                Path generated = Path.of(command.get(command.indexOf("--java") + 1));
                Path folder = generated.resolve("online/apkstudio/example");
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("R.java"), "public final class R {}", StandardCharsets.UTF_8);
            } else throw new IOException("Unexpected command");
            if (timeoutSeconds < 1 || timeoutSeconds > 180) throw new IOException("Unbounded command");
            return "mock OK";
        }
    }

    interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked fn, String name) throws Exception {
        try { fn.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + name);
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
