package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

/**
 * Fetches the 3GPP digital test sequences of AMR-NB (TS 26.074) and AMR-WB (TS 26.174) and
 * extracts them once into the conformance cache, unpacking the zips nested inside the archives.
 *
 * <p>The AMR-NB tree holds the linear-PCM sequences: {@code T_INP/} with the encoder inputs and the mode file
 * {@code T21.MOD}, {@code T_475/} to {@code T_122/} with the {@code .COD} encoder outputs and
 * {@code .OUT} decoder outputs, {@code T_MODE/}, {@code DTX/} (VAD option 1 with DTX) and the
 * {@code SYNC_*} folders. A {@code .COD} frame is 250 little-endian 16-bit words: the TX frame
 * type, 244 bit words (0 or 1), the mode (or -1) and 4 unused words. A {@code .OUT} file is
 * 160 samples per frame, little-endian.
 *
 * <p>The extended AMR-NB tree holds the rest of the TS 26.074 archive: the A-law and mu-law
 * variants of the sequences above ({@code T_INP/T00A.INP}, {@code T_102/T00A102.COD},
 * {@code T_MODE/T21U.COD}, {@code DTX/DTX3A102.COD}, and so on, with {@code A} or {@code U} in
 * place of the underscore), and the VAD option 2 sequences with DTX in {@code DTX2/linear/},
 * {@code DTX2/alaw/} and {@code DTX2/mulaw/}. A companded {@code .INP} or {@code .OUT} file holds
 * one G.711 code per little-endian 16-bit word; a companded {@code .OUT} is the compressed
 * decoder output.
 *
 * <p>The AMR-WB tree holds {@code T/T_inp/}, {@code T/T_660/} to {@code T/T_2385/},
 * {@code T/T_mode/}, {@code T/DTX_test_vectors/...} and the {@code Sync_*} folders. A
 * {@code .cod} frame is the 3GPP serial format: the word {@code 0x6b21}, the TX frame type, the
 * mode, then one word per bit ({@code 0x007f} for 1, {@code 0xff81} for 0); a {@code .out} file
 * is 320 samples per frame. File names keep the case they have in the archives.
 */
final class AmrVectors {
    private static final String NB_URL = "https://www.3gpp.org/ftp/Specs/archive/26_series/26.074/26074-i00.zip";
    private static final String NB_SHA256 = "176b3281ca76cd9b4bd38d15d4e272b1b7cd54d94df1751bff158ca6958e986d";
    private static final String WB_URL = "https://www.3gpp.org/ftp/Specs/archive/26_series/26.174/26174-i00.zip";
    private static final String WB_SHA256 = "5e7ac548aa8219435ae5847e1050b1e9d319bcac130ee279c043abdf2856928f";

    private static final Object LOCK = new Object();

    private AmrVectors() {
    }

    /**
     * Returns the root of the extracted AMR-NB linear sequences, fetching them when needed.
     */
    static Path narrowband() {
        var archive = Conformance.fetch("3gpp-26074-i00.zip", NB_URL, NB_SHA256);
        return extracted(archive, "3gpp-26074-i00", name -> {
            var upper = name.toUpperCase(Locale.ROOT);
            if (upper.endsWith(".ZIP")) {
                var base = upper.substring(upper.lastIndexOf('/') + 1);
                return base.startsWith("TS_AMR_H01_NODTX") || base.startsWith("TS_AMR_H01_DTX.")
                        || base.equals("NODTX_H00.ZIP") || base.equals("DTX_H00.ZIP") || base.equals("SYNC_H00.ZIP");
            }
            return upper.endsWith(".COD") || upper.endsWith(".OUT") || upper.endsWith(".INP") || upper.endsWith(".MOD");
        });
    }

    /**
     * Returns the root of the extracted A-law, mu-law and VAD option 2 AMR-NB sequences, fetching
     * them when needed.
     */
    static Path narrowbandExtended() {
        var archive = Conformance.fetch("3gpp-26074-i00.zip", NB_URL, NB_SHA256);
        return extracted(archive, "3gpp-26074-i00-ext", name -> {
            var upper = name.toUpperCase(Locale.ROOT);
            if (upper.endsWith(".ZIP")) {
                var base = upper.substring(upper.lastIndexOf('/') + 1);
                return base.startsWith("TS_AMR_H01_NODTX") || base.startsWith("TS_AMR_H01_DTX")
                        || base.equals("NODTXAH00.ZIP") || base.equals("NODTXUH00.ZIP")
                        || base.equals("DTXAH00.ZIP") || base.equals("DTXUH00.ZIP")
                        || base.equals("DTX2_H00.ZIP") || base.equals("DTX2AH00.ZIP") || base.equals("DTX2UH00.ZIP");
            }
            return upper.endsWith(".COD") || upper.endsWith(".OUT") || upper.endsWith(".INP");
        });
    }

    /**
     * Returns the root of the extracted AMR-WB sequences, fetching them when needed.
     */
    static Path wideband() {
        var archive = Conformance.fetch("3gpp-26174-i00.zip", WB_URL, WB_SHA256);
        return extracted(archive, "3gpp-26174-i00", name -> {
            var upper = name.toUpperCase(Locale.ROOT);
            return upper.endsWith(".ZIP") || upper.endsWith(".COD") || upper.endsWith(".OUT")
                    || upper.endsWith(".INP") || upper.endsWith(".MOD");
        });
    }

    /**
     * Reads a file of little-endian 16-bit words.
     */
    static short[] words(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            var words = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(words);
            return words;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Writes 16-bit words little-endian, for debugging.
     */
    static byte[] bytes(short[] words, int count) {
        var buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(words, 0, count);
        return buffer.array();
    }

    /**
     * Finds a file below a root by name, ignoring case.
     */
    static Path find(Path root, String name) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name + " not found below " + root));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Lists the files below a root whose name matches a predicate, sorted by path.
     */
    static java.util.List<Path> list(Path root, Predicate<String> name) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> name.test(file.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path extracted(Path archive, String name, Predicate<String> keep) {
        var target = Conformance.directory().resolve(name);
        var marker = target.resolve(".complete");
        synchronized (LOCK) {
            if (Files.isRegularFile(marker)) {
                return target;
            }
            try {
                Files.createDirectories(Conformance.directory());
                var temporary = Files.createTempDirectory(Conformance.directory(), name + "-");
                try (var input = Files.newInputStream(archive)) {
                    unzip(input, temporary, keep, 0);
                }
                Files.writeString(temporary.resolve(".complete"), "ok");
                if (Files.exists(target)) {
                    delete(target);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static void unzip(InputStream input, Path directory, Predicate<String> keep, int depth) throws IOException {
        var zip = new ZipInputStream(input);
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            var entryName = entry.getName().replace('\\', '/');
            if (entry.isDirectory() || !keep.test(entryName)) {
                continue;
            }
            if (entryName.toUpperCase(Locale.ROOT).endsWith(".ZIP")) {
                if (depth < 3) {
                    unzip(new ByteArrayInputStream(zip.readAllBytes()), directory, keep, depth + 1);
                }
                continue;
            }
            var file = directory.resolve(entryName).normalize();
            if (!file.startsWith(directory)) {
                throw new IOException("entry outside the archive root: " + entryName);
            }
            Files.createDirectories(file.getParent());
            Files.write(file, zip.readAllBytes());
        }
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
