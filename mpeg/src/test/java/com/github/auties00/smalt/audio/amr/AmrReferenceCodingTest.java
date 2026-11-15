package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
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
import java.nio.file.StandardOpenOption;
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

/**
 * Codes full-scale and unusual signals (uniform noise, a Nyquist square, toggling full-scale DC, sparse full-scale
 * impulses, an overdriven sweep, clipped speech and noise bursts) with the AMR-NB encoder in every mode, without
 * discontinuous transmission and with either voice activity detector, and with a mode change on every frame, then
 * decodes the streams. These signals saturate the fixed-point arithmetic throughout both coders, which the TS 26.074
 * sequences reach only rarely, and drive the decoder's synthesis overflow rescaling.
 *
 * <p>The reference is the 3GPP simulation of TS 26.073, built by {@code tools/testdata/amr-oracle.py} as
 * {@code smalt-amrnb-ref-enc}, {@code smalt-amrnb-ref-enc-vad2} and {@code smalt-amrnb-ref}. With them on
 * {@code PATH} every stream must equal the reference encoder's byte for byte and every decode the reference decoder's
 * sample for sample; without, the SHA-256 of each must equal the reference's, pinned in {@code reference-coding.tsv}.
 */
class AmrReferenceCodingTest {
    private static final int SECONDS = 3;

    private static final Map<String, String[]> PINNED = pinned();

    // with the reference coders on PATH, -Dsmalt.amr.record=<file> writes the rows of reference-coding.tsv there
    private static final String RECORD = System.getProperty("smalt.amr.record");

    private static final Object RECORD_LOCK = new Object();

    @TempDir
    Path directory;

    /** A test signal. */
    enum Signal {
        /** Uniform white noise over the full 16-bit range. */
        UNIFORM,
        /** A square wave at half the sampling rate, between the extreme values. */
        NYQUIST,
        /** Full-scale DC that changes sign every quarter second. */
        DC,
        /** Sparse full-scale impulses of random sign on silence. */
        IMPULSES,
        /** A sine sweep from 50 Hz to 3.95 kHz, overdriven by a quarter and clipped. */
        SWEEP,
        /** The codec test signal amplified eight times and clipped. */
        SPEECH,
        /** Loud noise bursts of a tenth of a second between quiet noise. */
        BURSTS
    }

    /** The discontinuous transmission setting of a case. */
    enum Detector {
        /** Without discontinuous transmission. */
        NONE,
        /** With discontinuous transmission and VAD option 1. */
        VAD1,
        /** With discontinuous transmission and VAD option 2. */
        VAD2
    }

    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        for (var signal : Signal.values()) {
            for (var detector : Detector.values()) {
                for (var mode : AmrMode.values()) {
                    if (!mode.isWideband()) {
                        arguments.add(Arguments.of(signal, detector, mode.name()));
                    }
                }
                arguments.add(Arguments.of(signal, detector, "SWITCHING"));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("cases")
    @DisplayName("codes and decodes saturating signals like the 3GPP reference coders")
    void reference(Signal signal, Detector detector, String mode) {
        var pcm = signal(signal);
        var modes = schedule(mode, pcm.length / 160);
        var stream = AmrTestSupport.storage(Codec.AMR_NB, encode(pcm, modes, detector));
        var decoded = AmrTestSupport.decode(Codec.AMR_NB, AmrTestSupport.frames(stream));
        var key = signal + "\t" + detector + "\t" + mode;
        var encoder = detector == Detector.VAD2 ? "smalt-amrnb-ref-enc-vad2" : "smalt-amrnb-ref-enc";
        if (Oracle.find(encoder).isPresent() && Oracle.find("smalt-amrnb-ref").isPresent()) {
            var reference = referenceEncode(encoder, pcm, modes, detector != Detector.NONE);
            assertArrayEquals(reference, stream, key + " stream");
            assertArrayEquals(referenceDecode(reference), decoded, key + " decode");
            if (RECORD != null) {
                record(key + "\t" + sha256(stream) + "\t" + sha256(AmrTestSupport.bytes(decoded)));
                return;
            }
        }
        var pinned = PINNED.get(key);
        assertNotNull(pinned, "no pinned digests for " + key);
        assertEquals(pinned[0], sha256(stream), key + " stream (" + encoder + " on PATH compares the bytes)");
        assertEquals(pinned[1], sha256(AmrTestSupport.bytes(decoded)),
                key + " decode (smalt-amrnb-ref on PATH compares the samples)");
    }

    @Test
    @DisplayName("pins the digests of every case")
    void complete() {
        assertEquals(cases().count(), PINNED.size());
    }

    static short[] signal(Signal signal) {
        var n = 8000 * SECONDS;
        var random = new Random(26_073L + signal.ordinal());
        var pcm = new short[n];
        switch (signal) {
            case UNIFORM -> {
                for (var i = 0; i < n; i++) {
                    pcm[i] = (short) random.nextInt();
                }
            }
            case NYQUIST -> {
                for (var i = 0; i < n; i++) {
                    pcm[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
                }
            }
            case DC -> {
                for (var i = 0; i < n; i++) {
                    pcm[i] = (i / 2000) % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
                }
            }
            case IMPULSES -> {
                for (var i = 0; i < n; i++) {
                    if (random.nextInt(100) == 0) {
                        pcm[i] = random.nextBoolean() ? Short.MAX_VALUE : Short.MIN_VALUE;
                    }
                }
            }
            case SWEEP -> {
                var phase = 0.0;
                for (var i = 0; i < n; i++) {
                    phase += 2 * StrictMath.PI * (50 + 3900.0 * i / n) / 8000;
                    pcm[i] = clip(40_000 * StrictMath.sin(phase));
                }
            }
            case SPEECH -> {
                var speech = AmrTestSupport.signal(8000);
                for (var i = 0; i < n; i++) {
                    pcm[i] = clip(8.0 * speech[i % speech.length]);
                }
            }
            case BURSTS -> {
                for (var i = 0; i < n; i++) {
                    pcm[i] = clip(random.nextGaussian() * ((i / 800) % 3 == 0 ? 20_000 : 30));
                }
            }
        }
        return pcm;
    }

    private static short clip(double value) {
        return (short) Math.clamp(Math.round(value), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    // a fixed mode, or a mode change on every frame from a seeded random order
    private static AmrMode[] schedule(String mode, int frames) {
        var modes = new AmrMode[frames];
        if (!mode.equals("SWITCHING")) {
            java.util.Arrays.fill(modes, AmrMode.valueOf(mode));
            return modes;
        }
        var random = new Random(26_090L);
        for (var i = 0; i < frames; i++) {
            modes[i] = AmrMode.values()[random.nextInt(8)];
        }
        return modes;
    }

    private static List<Packet> encode(short[] pcm, AmrMode[] modes, Detector detector) {
        var options = AmrEncoderOptions.builder().mode(modes[0]).dtx(detector != Detector.NONE)
                .vad(detector == Detector.VAD2 ? AmrVad.OPTION_2 : AmrVad.OPTION_1).build();
        var packets = new ArrayList<Packet>();
        try (var encoder = new AmrEncoder(options)) {
            var buffer = new AudioBuffer(ChannelLayout.MONO, 8000, 160);
            for (var frame = 0; frame < modes.length; frame++) {
                encoder.setMode(modes[frame]);
                var plane = buffer.plane(0);
                for (var i = 0; i < 160; i++) {
                    plane[i] = pcm[frame * 160 + i] / 32768f;
                }
                buffer.setFrames(160);
                encoder.encode(buffer, packets::add);
            }
        }
        assertEquals(modes.length, packets.size(), "one frame per 160 samples");
        return packets;
    }

    private byte[] referenceEncode(String tool, short[] pcm, AmrMode[] modes, boolean dtx) {
        try {
            var input = Files.createTempFile(directory, "input-", ".raw");
            Files.write(input, AmrTestSupport.bytes(pcm));
            var output = Path.of(input + ".amr");
            var arguments = new ArrayList<String>();
            if (dtx) {
                arguments.add("-dtx");
            }
            var modeFile = Path.of(input + ".mod");
            var lines = new StringBuilder();
            for (var mode : modes) {
                lines.append(referenceName(mode)).append('\n');
            }
            Files.writeString(modeFile, lines, StandardCharsets.US_ASCII);
            arguments.add("-modefile=" + modeFile);
            arguments.add(input.toString());
            arguments.add(output.toString());
            Oracle.run(tool, Duration.ofMinutes(2), null, arguments);
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String referenceName(AmrMode mode) {
        return switch (mode) {
            case NB_4_75 -> "MR475";
            case NB_5_15 -> "MR515";
            case NB_5_90 -> "MR59";
            case NB_6_70 -> "MR67";
            case NB_7_40 -> "MR74";
            case NB_7_95 -> "MR795";
            case NB_10_2 -> "MR102";
            case NB_12_2 -> "MR122";
            default -> throw new IllegalArgumentException(mode + " is not an AMR-NB mode");
        };
    }

    private short[] referenceDecode(byte[] stream) {
        try {
            var input = Files.createTempFile(directory, "stream-", ".amr");
            Files.write(input, stream);
            var output = Path.of(input + ".raw");
            Oracle.run("smalt-amrnb-ref", Duration.ofMinutes(2), null, List.of(input.toString(), output.toString()));
            return AmrTestSupport.words(Files.readAllBytes(output));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void record(String row) {
        synchronized (RECORD_LOCK) {
            try {
                Files.writeString(Path.of(RECORD), row + "\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String[]> pinned() {
        var rows = new HashMap<String, String[]>();
        try (var input = AmrReferenceCodingTest.class.getResourceAsStream("reference-coding.tsv")) {
            if (input == null) {
                return rows;
            }
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                rows.put(columns[0] + "\t" + columns[1] + "\t" + columns[2], new String[]{columns[3], columns[4]});
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return rows;
    }
}
