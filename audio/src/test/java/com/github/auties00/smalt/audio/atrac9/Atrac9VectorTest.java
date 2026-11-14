package com.github.auties00.smalt.audio.atrac9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Decodes the committed ATRAC9 vectors ({@code src/test/resources/com/github/auties00/smalt/audio/atrac9}, written by
 * {@link Atrac9VectorWriter}) and compares the MD5 of every sample with the LibAtrac9 decode that
 * {@code tools/testdata/atrac9-oracle/make-vectors.py} recorded in {@code expected.tsv}, so that bit-exactness is
 * checked on every machine, with or without the reference installed.
 */
@DisplayName("ATRAC9 decoder on the committed vectors")
class Atrac9VectorTest {
    static byte[] resource(String name) {
        try (var in = Atrac9VectorTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<String[]> vectors() {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource("expected.tsv"), StandardCharsets.US_ASCII).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                rows.add(line.split("\t"));
            }
        }
        assertFalse(rows.isEmpty());
        return rows;
    }

    static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes the vector to LibAtrac9's samples")
    void decodes(String name, String sha256, String superframes, String md5) {
        var data = resource(name + ".a9");
        assertEquals(sha256, hex("SHA-256", data), name + ": vector changed");
        var config = Arrays.copyOf(data, 4);
        var size = Atrac9Config.parse(config, 0).superframeBytes();
        var list = new ArrayList<byte[]>();
        for (var at = 4; at < data.length; at += size) {
            list.add(Arrays.copyOfRange(data, at, Math.min(data.length, at + size)));
        }
        assertEquals(Integer.parseInt(superframes), list.size());
        var samples = Atrac9DecoderTest.decode(new Atrac9TestStreams.Stream(config, list));
        var bytes = new byte[samples.length * 2];
        for (var i = 0; i < samples.length; i++) {
            bytes[2 * i] = (byte) samples[i];
            bytes[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        assertEquals(md5, hex("MD5", bytes), name);
    }
}
