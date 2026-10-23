import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Compiles and runs host-only verifier tests without an Android SDK. */
class RunAapt2VerificationTests {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Pass VerifyAapt2.java, TestVerifyAapt2.java, AAPT2 ELF");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JavaCompiler unavailable");
        Path classes = Files.createTempDirectory("apkstudio-aapt2-verifier-");
        try {
            int result = compiler.run(null, System.out, System.err, "--release", "17", "-d",
                classes.toString(), args[0], args[1]);
            if (result != 0) throw new IllegalStateException("Compilation failed: " + result);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
                Class<?> test = loader.loadClass("TestVerifyAapt2");
                test.getMethod("main", String[].class).invoke(null,
                    (Object) new String[]{args[2]});
            }
        } finally {
            try (var paths = Files.walk(classes)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }
}
