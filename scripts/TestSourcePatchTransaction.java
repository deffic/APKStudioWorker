package online.apkstudio.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class TestSourcePatchTransaction {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("apkstudio-edit-tests-");
        Path project = temp.resolve("project");
        Path backup = temp.resolve("backups");
        Files.createDirectories(project.resolve("app/src/main"));
        Path main = project.resolve("app/src/main/Main.java");
        Files.writeString(main, "original source", StandardCharsets.UTF_8);
        try {
            List<SourcePatchTransaction.Edit> changes = List.of(
                new SourcePatchTransaction.Edit("app/src/main/Main.java", "updated source"),
                new SourcePatchTransaction.Edit("app/src/main/New.kt", "new Kotlin source"));
            int count = SourcePatchTransaction.apply(project.toFile(), backup.resolve("job1").toFile(), changes);
            require(count == 2, "two changes applied");
            require(Files.readString(main).equals("updated source"), "existing modified");
            require(Files.exists(project.resolve("app/src/main/New.kt")), "new file created");
            SourcePatchTransaction.rollback(project.toFile(), backup.resolve("job1").toFile());
            require(Files.readString(main).equals("original source"), "original restored");
            require(!Files.exists(project.resolve("app/src/main/New.kt")), "new file removed");

            reject(() -> SourcePatchTransaction.apply(project.toFile(), backup.resolve("job2").toFile(),
                List.of(new SourcePatchTransaction.Edit("../outside.java", "bad"))), "traversal");
            reject(() -> SourcePatchTransaction.apply(project.toFile(), backup.resolve("job3").toFile(),
                List.of(new SourcePatchTransaction.Edit("local.properties", "secret"))), "secrets");
            reject(() -> SourcePatchTransaction.apply(project.toFile(), backup.resolve("job4").toFile(),
                List.of(new SourcePatchTransaction.Edit("app/Main.java", "1"),
                    new SourcePatchTransaction.Edit("app/Main.java", "2"))), "duplicate file");

            // Existing file must not be changed if any other requested target is invalid.
            reject(() -> SourcePatchTransaction.apply(project.toFile(), backup.resolve("job5").toFile(),
                List.of(new SourcePatchTransaction.Edit("app/src/main/Main.java", "danger"),
                    new SourcePatchTransaction.Edit(".git/config", "danger"))), "partial edits");
            require(Files.readString(main).equals("original source"), "validation before writes");
            System.out.println("SourcePatchTransaction tests OK");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean truth, String detail) { if (!truth) throw new AssertionError(detail); }
    private static void reject(Throwing action, String detail) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Accepted " + detail);
    }
    private interface Throwing { void run() throws Exception; }
}
