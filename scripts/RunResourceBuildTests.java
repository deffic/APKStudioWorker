import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

class RunResourceBuildTests {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass ResourceBuildStage.java and tests");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JavaCompiler unavailable");
        Path classes = Files.createTempDirectory("apkstudio-resource-classes-");
        try {
            Path module = Path.of(args[0]).getParent();
            int result = compiler.run(null, System.out, System.err, "--release", "17", "-d",
                classes.toString(), args[0], module.resolve("BuildProfile.java").toString(),
                module.resolve("ManifestPackagePreparer.java").toString(), args[1]);
            if (result != 0) throw new IllegalStateException("Source compilation failed: " + result);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
                Class<?> test = loader.loadClass("online.apkstudio.worker.TestResourceBuildStage");
                test.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (var paths = Files.walk(classes)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }
}
