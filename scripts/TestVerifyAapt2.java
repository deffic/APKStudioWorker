import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression checks against a real, pinned ARM64 AAPT2 executable. */
public final class TestVerifyAapt2 {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass verified AAPT2 file");
        Path original = Path.of(args[0]);
        VerifyAapt2.validate(original);
        expectReject(original, 18, new byte[]{0x3e, 0x00}, "x86-64 rejected");
        expectReject(original, 4, new byte[]{0x01}, "ELF32 rejected");
        expectReject(original, 16, new byte[]{0x01, 0x00}, "non-executable rejected");
        expectReject(original, 54, new byte[]{0x01, 0x00}, "wrong program-header size rejected");
        expectReject(original, 56, new byte[]{0x00, 0x00}, "no program headers rejected");
        // First PT_LOAD follows the 56-byte PT_PHDR entry.
        expectReject(original, 0x40 + 56 + 48, new byte[]{0x00, 0x10, 0, 0, 0, 0, 0, 0},
            "4K-only alignment rejected");
        System.out.println("AAPT2 ELF checks passed: valid release + 6 corruptions rejected.");
    }

    private static void expectReject(Path original, long offset, byte[] bytes, String caseName)
        throws Exception {
        Path temp = Files.createTempFile("apkstudio-mutated-aapt2-", ".elf");
        try {
            Files.copy(original, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (RandomAccessFile file = new RandomAccessFile(temp.toFile(), "rw")) {
                file.seek(offset);
                file.write(bytes);
            }
            try {
                VerifyAapt2.validate(temp);
                throw new AssertionError("Not rejected: " + caseName);
            } catch (IOException expected) {
                // Expected validation failure.
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
