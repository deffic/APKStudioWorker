import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host-side structural validation; intentionally does not run untrusted ARM code. */
public final class VerifyAapt2 {
    private static final int ELF_HEADER = 64;
    private static final int PROGRAM_HEADER = 56;
    private static final long MIN_LOAD_ALIGNMENT = 0x4000;

    private VerifyAapt2() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Specify an ARM64 ELF file");
        validate(Path.of(args[0]));
        System.out.println("Verified ELF64 ARM64, static executable, 16K-aligned load segments.");
    }

    static void validate(Path file) throws IOException {
        long size = Files.size(file);
        // 50MB is more than enough for the pinned 4.7MB release asset.
        if (size < ELF_HEADER || size > 50L * 1024 * 1024) {
            throw new IOException("Unexpected AAPT2 file size");
        }
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            if (readByte(f, 0) != 0x7f || readByte(f, 1) != 'E' ||
                readByte(f, 2) != 'L' || readByte(f, 3) != 'F' ||
                readByte(f, 4) != 2 || readByte(f, 5) != 1) {
                throw new IOException("Expected little-endian ELF64");
            }
            int type = u16(f, 16);
            if (type != 2 && type != 3) throw new IOException("Expected executable or PIE ELF");
            if (u16(f, 18) != 183) throw new IOException("Expected AArch64 ELF");
            if (u32(f, 20) != 1) throw new IOException("Unexpected ELF version");

            long phoff = u64(f, 32);
            int phentsize = u16(f, 54);
            int phnum = u16(f, 56);
            if (phoff < ELF_HEADER || phentsize != PROGRAM_HEADER || phnum < 1 || phnum > 128 ||
                phoff > size || (long) phnum * phentsize > size - phoff) {
                throw new IOException("Invalid ELF program-header table");
            }

            int loads = 0;
            for (int i = 0; i < phnum; i++) {
                long at = phoff + (long) i * phentsize;
                long segmentType = u32(f, at);
                int flags = (int) u32(f, at + 4);
                long offset = u64(f, at + 8);
                long virtualAddress = u64(f, at + 16);
                long fileSize = u64(f, at + 32);
                long alignment = u64(f, at + 48);
                if (segmentType == 3 || segmentType == 2) {
                    throw new IOException("Expected static AAPT2 without PT_INTERP / PT_DYNAMIC");
                }
                if (segmentType != 1) continue;
                loads++;
                if (alignment < MIN_LOAD_ALIGNMENT || (alignment & (alignment - 1)) != 0 ||
                    offset < 0 || fileSize < 0 || offset > size || fileSize > size - offset ||
                    Math.floorMod(virtualAddress - offset, alignment) != 0) {
                    throw new IOException("Invalid or non-16K-aligned PT_LOAD segment");
                }
                if ((flags & 0x3) == 0x3) {
                    throw new IOException("Writable + executable ELF segment forbidden");
                }
            }
            if (loads == 0) throw new IOException("No loadable ELF segments");
        }
    }

    private static int readByte(RandomAccessFile f, long at) throws IOException {
        f.seek(at);
        return f.readUnsignedByte();
    }

    private static int u16(RandomAccessFile f, long at) throws IOException {
        return readByte(f, at) | (readByte(f, at + 1) << 8);
    }

    private static long u32(RandomAccessFile f, long at) throws IOException {
        return (u16(f, at) & 0xffffL) | ((u16(f, at + 2) & 0xffffL) << 16);
    }

    private static long u64(RandomAccessFile f, long at) throws IOException {
        long low = u32(f, at);
        long high = u32(f, at + 4);
        return low | (high << 32);
    }
}
