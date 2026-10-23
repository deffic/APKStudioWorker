import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Compile and run pure-Java tests when a system JDK is present without javac on PATH. */
class RunBuildProfileTests {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass BuildProfile.java and TestBuildProfile.java");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JavaCompiler unavailable");
        Path classes = Files.createTempDirectory("apkstudio-profile-classes-");
        try {
            int result = compiler.run(null, System.out, System.err, "--release", "17", "-d",
                classes.toString(), args[0], args[1]);
            if (result != 0) throw new IllegalStateException("Source compilation failed: " + result);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
                Class<?> tests = loader.loadClass("online.apkstudio.worker.TestBuildProfile");
                tests.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(classes)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
