package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the comfort noise receiver and the bad frame handling of both decoders against the 3GPP reference decoders
 * (the bit-exact simulations of TS 26.073 and TS 26.173, built by {@code tools/testdata/amr-oracle.py} as
 * {@code smalt-amrnb-ref} and {@code smalt-amrwb-ref}) on storage-format streams coded with discontinuous
 * transmission and then damaged the way a channel damages them: SID frames flagged bad or lost, gaps long enough for
 * the receiver to mute, bad and lost speech inside a comfort noise period, no-data frames inside speech, SID frames
 * without a preceding SID_FIRST and SID frames with arbitrary parameters. ffmpeg's libopencore decoders cannot serve:
 * they ignore the quality bit, and opencore's AMR-WB decoder predates the current TS 26.173 concealment and DTX rules.
 *
 * <p>The streams come from Smalt's encoders, which reproduce the reference encoders bit for bit, so they are the same
 * on every machine. With the reference decoders on {@code PATH} every case must decode sample for sample like them;
 * without, the SHA-256 of each decode must equal the one the reference decoders produced, pinned in
 * {@code dtx-reception.tsv}.
 */
class AmrDtxReceptionTest {
    private static final byte NO_DATA = 0x7C;

    private static final Map<String, String> PINNED = pinned();

    // with the reference decoders on PATH, -Dsmalt.amr.record=<file> writes the digests of dtx-reception.tsv there
    private static final String RECORD = System.getProperty("smalt.amr.record");

    private static final Object RECORD_LOCK = new Object();

    @TempDir
    Path directory;

    /** A way a channel damages a stream coded with discontinuous transmission. */
    enum Damage {
        /** Leaves the stream as the encoder wrote it. */
        NONE,
        /** Clears the quality bit of every other SID_UPDATE frame. */
        SID_BAD,
        /** Clears the quality bit of every SID_FIRST frame. */
        SID_FIRST_BAD,
        /** Replaces every SID_UPDATE frame with a no-data frame, so the receiver mutes in long periods. */
        LOST_SID,
        /** Replaces the SID_UPDATE frames of the first 60 frames of each comfort noise period, then lets one through. */
        MUTE_THEN_UPDATE,
        /** Clears the quality bit of the first speech frames after each comfort noise period. */
        SPEECH_BAD_IN_DTX,
        /** Replaces the first speech frames after each comfort noise period with lost-speech frames (AMR-WB only). */
        SPEECH_LOST_IN_DTX,
        /** Clears the quality bit of every fifth speech frame. */
        SPEECH_BAD,
        /** Replaces every seventh speech frame with a no-data frame. */
        NO_DATA_IN_SPEECH,
        /** Replaces speech frames with a copy of a SID_UPDATE frame, without a SID_FIRST before it. */
        SID_IN_SPEECH,
        /** Gives every SID_UPDATE frame random comfort noise parameters, the extreme energies included. */
        RANDOM_SID
    }

    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        for (var mode : AmrMode.values()) {
            for (var damage : Damage.values()) {
                if (damage != Damage.SPEECH_LOST_IN_DTX || mode.isWideband()) {
                    arguments.add(Arguments.of(mode, damage));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    @DisplayName("decodes clean and damaged comfort noise streams like the 3GPP reference decoder")
    void reference(AmrMode mode, Damage damage) {
        var codec = mode.codec();
        var frames = AmrTestSupport.encode(AmrEncoderOptions.of(mode).toBuilder().dtx(true).build(),
                signal(mode.sampleRate()));
        var damaged = damage(frames, codec, damage);
        var actual = AmrTestSupport.decode(codec, damaged);
        var tool = tool(codec);
        if (Oracle.find(tool).isPresent()) {
            assertArrayEquals(referenceDecode(codec, AmrTestSupport.storage(codec, damaged)), actual, mode + " " + damage);
            if (RECORD != null) {
                record(mode + "\t" + damage + "\t" + sha256(actual));
                return;
            }
        }
        var pinned = PINNED.get(mode + "\t" + damage);
        assertNotNull(pinned, "no pinned digest for " + mode + " " + damage);
        assertEquals(pinned, sha256(actual), mode + " " + damage + " (" + tool + " on PATH compares the samples)");
    }

    @Test
    @DisplayName("pins a digest for every case")
    void complete() {
        assertEquals(cases().count(), PINNED.size());
    }

    private static void record(String row) {
        synchronized (RECORD_LOCK) {
            try {
                Files.writeString(Path.of(RECORD), row + "\n", StandardCharsets.US_ASCII,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static String tool(Codec codec) {
        return codec == Codec.AMR_WB ? "smalt-amrwb-ref" : "smalt-amrnb-ref";
    }

    private short[] referenceDecode(Codec codec, byte[] file) {
        try {
            var input = Files.createTempFile(directory, "damaged-", ".amr");
            Files.write(input, file);
            var output = Path.of(input + ".raw");
            var arguments = new ArrayList<String>();
            if (codec == Codec.AMR_WB) {
                arguments.add("-mime");
            }
            arguments.add(input.toString());
            arguments.add(output.toString());
            Oracle.run(tool(codec), Duration.ofMinutes(2), null, arguments);
            return AmrTestSupport.words(Files.readAllBytes(output));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns two passes of the codec test signal (speech, background noise and silence) with two more seconds of
     * silence between them, so the first comfort noise period outlasts the receiver's 50-frame limit.
     */
    private static short[] signal(int sampleRate) {
        var once = AmrTestSupport.signal(sampleRate);
        var twice = new short[once.length * 2 + 2 * sampleRate];
        System.arraycopy(once, 0, twice, 0, once.length);
        System.arraycopy(once, 0, twice, twice.length - once.length, once.length);
        return twice;
    }

    private static List<Packet> damage(List<Packet> frames, Codec codec, Damage damage) {
        var sid = codec == Codec.AMR_WB ? 9 : 8;
        var random = new Random(26_101L + damage.ordinal());
        var result = new ArrayList<Packet>(frames.size());
        var updates = 0;
        var speechAfterDtx = Integer.MAX_VALUE;
        var speech = 0;
        var periodStart = -1;
        var changed = 0;
        byte[] lastUpdate = null;
        for (var index = 0; index < frames.size(); index++) {
            var packet = frames.get(index);
            var data = packet.data();
            var type = (data[0] >> 3) & 0x0F;
            var isSid = type == sid;
            var isUpdate = isSid && (data[5] & 0x10) != 0;
            var isSpeech = type < sid;
            if (isSid || type == 15) {
                if (periodStart < 0) {
                    periodStart = index;
                }
                speechAfterDtx = 0;
            } else if (isSpeech) {
                periodStart = -1;
            }
            if (isUpdate) {
                lastUpdate = data;
            }
            byte[] replacement = null;
            switch (damage) {
                case NONE -> {
                }
                case SID_BAD -> {
                    if (isUpdate && updates++ % 2 == 0) {
                        replacement = withoutQuality(data);
                    }
                }
                case SID_FIRST_BAD -> {
                    if (isSid && !isUpdate) {
                        replacement = withoutQuality(data);
                    }
                }
                case LOST_SID -> {
                    if (isUpdate) {
                        replacement = new byte[]{NO_DATA};
                    }
                }
                case MUTE_THEN_UPDATE -> {
                    // 60 frames without a SID_UPDATE exceed the receiver's 50-frame limit, so it mutes before the
                    // next update arrives
                    if (isUpdate && index - periodStart < 60) {
                        replacement = new byte[]{NO_DATA};
                    }
                }
                case SPEECH_BAD_IN_DTX, SPEECH_LOST_IN_DTX -> {
                    if (isSpeech && speechAfterDtx < 3) {
                        replacement = damage == Damage.SPEECH_BAD_IN_DTX ? withoutQuality(data)
                                : new byte[]{(byte) (14 << 3 | 0x04)};
                    }
                }
                case SPEECH_BAD -> {
                    if (isSpeech && speech % 5 == 2) {
                        replacement = withoutQuality(data);
                    }
                }
                case NO_DATA_IN_SPEECH -> {
                    if (isSpeech && speech % 7 == 3) {
                        replacement = new byte[]{NO_DATA};
                    }
                }
                case SID_IN_SPEECH -> {
                    if (isSpeech && lastUpdate != null && speech % 11 == 5) {
                        replacement = lastUpdate.clone();
                    }
                }
                case RANDOM_SID -> {
                    if (isUpdate) {
                        replacement = data.clone();
                        // the 35 comfort noise parameter bits; the STI bit and the mode indication stay
                        for (var bit = 0; bit < 35; bit++) {
                            setBit(replacement, bit, random.nextBoolean());
                        }
                        if (updates % 4 == 0) {
                            // the energy index at its extremes, all ones and all zeros in turn
                            var ones = updates % 8 == 0;
                            for (var bit = 0; bit < 6; bit++) {
                                setBit(replacement, (codec == Codec.AMR_WB ? 28 : 29) + bit, ones);
                            }
                        }
                        updates++;
                    }
                }
            }
            if (isSpeech) {
                speech++;
                speechAfterDtx++;
            }
            if (replacement != null) {
                changed++;
                result.add(packet.withData(replacement));
            } else {
                result.add(packet);
            }
        }
        assertTrue(damage == Damage.NONE || changed > 0, damage + " changed no frame");
        return result;
    }

    private static byte[] withoutQuality(byte[] data) {
        var copy = data.clone();
        copy[0] &= (byte) ~0x04;
        return copy;
    }

    private static void setBit(byte[] frame, int bit, boolean value) {
        var mask = 0x80 >> (bit & 7);
        if (value) {
            frame[1 + (bit >> 3)] |= (byte) mask;
        } else {
            frame[1 + (bit >> 3)] &= (byte) ~mask;
        }
    }

    private static String sha256(short[] samples) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(AmrTestSupport.bytes(samples)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> pinned() {
        var rows = new HashMap<String, String>();
        try (var input = AmrDtxReceptionTest.class.getResourceAsStream("dtx-reception.tsv")) {
            if (input == null) {
                return rows;
            }
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                rows.put(columns[0] + "\t" + columns[1], columns[2]);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return rows;
    }
}
