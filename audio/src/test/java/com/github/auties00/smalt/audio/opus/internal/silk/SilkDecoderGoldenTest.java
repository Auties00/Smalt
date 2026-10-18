package com.github.auties00.smalt.audio.opus.internal.silk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the SILK decoder bit for bit against the libopus 1.5.2 reference.
 *
 * <p>The {@code .bit} fixtures were produced by the reference encoder forced to SILK-only mode
 * (NB/MB/WB, 10 to 60 ms, mono and stereo, stereo/mono and bandwidth switches, CBR padding, DTX,
 * in-band FEC); {@code silk-golden.txt} holds, for every stream, API rate, API channel count and
 * loss pattern, the SHA-256 of the int16 output of the reference harness
 * {@code ref/harness-silkdec/silk_dec_ref.c} and the number of packets whose final range differs
 * from the encoder's (nonzero only where the encoder appended CELT redundancy, which a SILK-only
 * driver does not decode).
 */
class SilkDecoderGoldenTest {
    record Case(String stream, int rate, int channels, int lossPeriod, int fec, int rangeMismatches, String sha256) {
        @Override
        public String toString() {
            return stream + " @" + rate + "x" + channels + " loss=" + lossPeriod + " fec=" + fec;
        }
    }

    static Stream<Case> cases() {
        return read("silk-golden.txt").lines().filter(line -> !line.isBlank()).map(line -> {
            var f = line.trim().split("\\s+");
            return new Case(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6]);
        });
    }

    private static String read(String name) {
        return new String(readBytes(name), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(String name) {
        try (var in = SilkDecoderGoldenTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes SILK-only streams bit-exactly like libopus")
    void bitExact(Case c) throws NoSuchAlgorithmException {
        var packets = SilkReferenceDriver.parseBit(readBytes(c.stream() + ".bit"));
        var driver = new SilkReferenceDriver(c.rate(), c.channels());
        var pcm = driver.decodeAll(packets, c.lossPeriod(), c.fec() != 0);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm));
        assertEquals(c.sha256(), sha, "PCM hash");
        assertEquals(c.rangeMismatches(), driver.rangeMismatches, "final range mismatches");
    }
}
