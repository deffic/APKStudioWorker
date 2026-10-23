import com.sun.source.util.JavacTask;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Syntax-only verification without Android SDK. Semantic/Android API checks require a real build. */
class ValidateJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler module is unavailable.");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<java.io.File> paths = new ArrayList<>();
            for (String arg : args) paths.add(Path.of(arg).toFile());
            Iterable<? extends JavaFileObject> sources = files.getJavaFileObjectsFromFiles(paths);
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "--release", "17"), null, sources);
            task.parse();
            boolean fail = false;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(diagnostic);
                    fail = true;
                }
            }
            if (fail) System.exit(1);
            System.out.println("Java syntax: OK (Android SDK linkage not checked)");
        }
    }
}
