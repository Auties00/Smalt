package com.github.auties00.smalt.audio.g722;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * The digital test sequences of ITU-T G.722 Appendix II, read from the archive the ITU distributes (listed in
 * {@code tools/testdata/vectors.tsv}): each sequence is lines of 16 four-digit hexadecimal words followed by a
 * checksum, in a ZIP archive nested inside the downloaded one.
 */
public final class G722Sequences {
    private static Map<String, int[]> sequences;

    private G722Sequences() {
    }

    /** Returns the words of a sequence, by its file name, for example {@code T2R1.COD}. */
    public static synchronized int[] words(String name) {
        if (sequences == null) {
            sequences = load();
        }
        var words = sequences.get(name);
        if (words == null || words.length == 0) {
            throw new IllegalStateException("G.722 Appendix II has no sequence " + name);
        }
        return words;
    }

    private static Map<String, int[]> load() {
        try {
            var outer = Files.readAllBytes(Conformance.fetchListed("g722-appII.zip"));
            var inner = entry(outer, "software/G722ap2/G722E/Software.zip");
            var map = new HashMap<String, int[]>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(inner))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    var name = entry.getName();
                    map.put(name.substring(name.lastIndexOf('/') + 1), parse(zip.readAllBytes()));
                }
            }
            return map;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] entry(byte[] zipFile, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipFile))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IOException(name + " is not in the archive");
    }

    private static int[] parse(byte[] text) {
        var words = new ArrayList<Integer>();
        for (var line : new String(text, StandardCharsets.US_ASCII).split("\\R")) {
            line = line.strip();
            if (line.length() < 66 || line.startsWith("/*")) {
                continue;
            }
            for (var k = 0; k < 16; k++) {
                words.add(Integer.parseInt(line.substring(4 * k, 4 * k + 4), 16));
            }
        }
        return words.stream().mapToInt(Integer::intValue).toArray();
    }
}
