import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Tests the exact archive verifier to be used on Android without loading Android classes. */
class RunToolPackTests {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass VerifiedToolPack.java and its test source");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JavaCompiler unavailable");
        Path classes = Files.createTempDirectory("apkstudio-toolpack-classes-");
        try {
            int result = compiler.run(null, System.out, System.err, "--release", "17", "-d",
                classes.toString(), args[0], args[1]);
            if (result != 0) throw new IllegalStateException("Source compilation failed: " + result);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
                Class<?> tests = loader.loadClass("online.apkstudio.worker.TestVerifiedToolPack");
                tests.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
