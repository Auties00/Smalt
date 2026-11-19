package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks CAF against Core Audio on macOS, as a black box: {@code afconvert} encodes CAF files of every format
 * Smalt reads, {@link CafDemuxer} reads them, {@link CafMuxer} writes their packets again, and Core Audio must
 * decode Smalt's file to exactly the samples it decodes from its own (cookie, channel layout, priming and
 * remainder frames included). Core Audio's AAC in MP4 written again as CAF is checked the same way.
 */
@DisplayName("CAF against Core Audio")
class CafAppleTest {
    private static final int RATE = 48_000;

    // the files stay under target/media-fixtures, which CI keeps with the test reports
    private Path directory;

    private Path afconvert;

    @BeforeEach
    void requireMac(TestInfo info) throws IOException {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "afconvert is part of macOS");
        afconvert = Oracle.find("afconvert").orElseThrow(() -> new AssertionError("macOS without afconvert"));
        directory = Files.createDirectories(Path.of("target", "media-fixtures", "caf-apple",
                info.getTestMethod().orElseThrow().getName()));
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of("aac", 2, Codec.AAC, List.of("-d", "aac", "-b", "128000")),
                Arguments.of("aach", 2, Codec.AAC, List.of("-d", "aach", "-b", "64000")),
                Arguments.of("aacp", 2, Codec.AAC, List.of("-d", "aacp", "-b", "32000")),
                Arguments.of("aac-5.1", 6, Codec.AAC, List.of("-d", "aac", "-l", "MPEG_5_1_D")),
                Arguments.of("alac", 2, Codec.ALAC, List.of("-d", "alac")),
                Arguments.of("alac-24", 2, Codec.ALAC, List.of("-d", "alac/3")),
                Arguments.of("alac-6", 6, Codec.ALAC, List.of("-d", "alac")),
                Arguments.of("opus", 2, Codec.OPUS, List.of("-d", "opus", "-b", "64000")),
                Arguments.of("ima4", 2, Codec.ADPCM_IMA_QT, List.of("-d", "ima4")),
                Arguments.of("lei16", 2, Codec.PCM_S16LE, List.of("-d", "LEI16")),
                Arguments.of("lei24-5.1", 6, Codec.PCM_S24LE, List.of("-d", "LEI24", "-l", "MPEG_5_1_C")),
                Arguments.of("bef32", 2, Codec.PCM_F32BE, List.of("-d", "BEF32")),
                Arguments.of("ulaw", 2, Codec.PCM_MULAW, List.of("-d", "ulaw")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("Core Audio's file is read, written again, and decoded by Core Audio to the same samples")
    void roundTrip(String name, int channels, Codec codec, List<String> encoder) throws IOException {
        var source = Files.write(directory.resolve(name + "-source.wav"), wav(channels, channels == 2 ? 24_000 : 9600,
                name.contains("24") ? 24 : 16));
        var apple = directory.resolve(name + "-apple.caf");
        var arguments = new ArrayList<>(List.of("-f", "caff"));
        arguments.addAll(encoder);
        arguments.addAll(List.of(source.toString(), apple.toString()));
        run(arguments);
        var input = ContainerProbe.demux(apple, CafDemuxer::new);
        var track = (AudioTrack) input.tracks().getFirst();
        assertEquals(codec, track.codec(), name + " codec");
        assertEquals(channels, track.layout().orElseThrow().channels(), name + " channels");
        if (codec == Codec.PCM_S16LE) {
            assertArrayEquals(data(Files.readAllBytes(source)), concatenate(input), "the samples of the source");
        }
        var smalt = Files.write(directory.resolve(name + "-smalt.caf"),
                MuxSupport.toMemory(List.of(track), input.order(), CafMuxer::new));
        describe(apple);
        describe(smalt);
        var reread = ContainerProbe.demux(smalt, CafDemuxer::new);
        assertEquals(input.order().size(), reread.order().size(), name + " packets");
        assertEquals(track.skipSamples(), ((AudioTrack) reread.tracks().getFirst()).skipSamples(), name + " priming");
        assertEquals(track.duration(), reread.tracks().getFirst().duration(), name + " duration");
        assertArrayEquals(decode(apple), decode(smalt), name + ": Core Audio decodes Smalt's file as its own");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("afinfo counts the packets and frames of Smalt's file as of Core Audio's")
    void afinfo(String name, int channels, Codec codec, List<String> encoder) throws IOException {
        var afinfo = Oracle.find("afinfo").orElseThrow(() -> new AssertionError("macOS without afinfo"));
        var source = Files.write(directory.resolve(name + "-source.wav"), wav(channels, 4800,
                name.contains("24") ? 24 : 16));
        var apple = directory.resolve(name + "-apple.caf");
        var arguments = new ArrayList<>(List.of("-f", "caff"));
        arguments.addAll(encoder);
        arguments.addAll(List.of(source.toString(), apple.toString()));
        run(arguments);
        var input = ContainerProbe.demux(apple, CafDemuxer::new);
        var smalt = Files.write(directory.resolve(name + "-smalt.caf"),
                MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), CafMuxer::new));
        var appleInfo = format(Oracle.execute(afinfo, Duration.ofMinutes(1), null, List.of(apple.toString())));
        var smaltInfo = format(Oracle.execute(afinfo, Duration.ofMinutes(1), null, List.of(smalt.toString())));
        assertEquals(appleInfo, smaltInfo, name + ": afinfo's data format and packet counts");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mp4Formats")
    @DisplayName("Core Audio's AAC and ALAC in MP4, written as CAF, decode as the MP4 file does")
    void fromMp4(String name, List<String> encoder) throws IOException {
        var source = Files.write(directory.resolve(name + "-source.wav"), wav(2, 24_000, 16));
        var m4a = directory.resolve(name + "-apple.m4a");
        var arguments = new ArrayList<>(List.of("-f", "m4af"));
        arguments.addAll(encoder);
        arguments.addAll(List.of(source.toString(), m4a.toString()));
        run(arguments);
        var input = ContainerProbe.demux(m4a, Mp4Demuxer::new);
        var smalt = Files.write(directory.resolve(name + "-smalt.caf"),
                MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), CafMuxer::new));
        assertArrayEquals(decode(m4a), decode(smalt), name + ": Core Audio decodes the CAF file as the MP4 one");
    }

    static Stream<Arguments> mp4Formats() {
        return Stream.of(
                Arguments.of("aac", List.of("-d", "aac", "-b", "128000")),
                Arguments.of("aach", List.of("-d", "aach", "-b", "64000")),
                Arguments.of("alac", List.of("-d", "alac")));
    }

    /** Writes what afinfo says about a file next to it. */
    private static void describe(Path file) throws IOException {
        var afinfo = Oracle.find("afinfo").orElseThrow(() -> new AssertionError("macOS without afinfo"));
        var result = Oracle.execute(afinfo, Duration.ofMinutes(1), null, List.of(file.toString()));
        Files.writeString(file.resolveSibling(file.getFileName() + ".afinfo.txt"),
                new String(result.stdout(), StandardCharsets.UTF_8) + result.stderr());
    }

    private void run(List<String> arguments) {
        var result = Oracle.execute(afconvert, Duration.ofMinutes(2), null, arguments);
        assertEquals(0, result.exitCode(), "afconvert " + arguments + ": " + result.stderr()
                + new String(result.stdout(), StandardCharsets.UTF_8));
    }

    /** Decodes a file with Core Audio into the bytes of 32-bit float samples. */
    private byte[] decode(Path file) throws IOException {
        var wav = directory.resolve(file.getFileName() + ".decoded.wav");
        run(List.of("-f", "WAVE", "-d", "LEF32", file.toString(), wav.toString()));
        var samples = data(Files.readAllBytes(wav));
        assertTrue(samples.length > 0, "Core Audio decodes " + file.getFileName());
        return samples;
    }

    /** Returns the afinfo lines that count the packets and frames of a file. */
    private static List<String> format(Oracle.Result result) {
        assertEquals(0, result.exitCode(), result.stderr());
        return new String(result.stdout(), StandardCharsets.UTF_8).lines().map(String::strip)
                .filter(line -> line.startsWith("audio packets:") || line.startsWith("estimated duration:")
                        || line.startsWith("audio ") && line.contains("valid frames"))
                .toList();
    }

    private static byte[] concatenate(ContainerProbe.Demuxed demuxed) {
        var out = new java.io.ByteArrayOutputStream();
        demuxed.order().forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    /** Returns the bytes of the data chunk of a WAVE file. */
    private static byte[] data(byte[] wav) {
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var position = 12;
        while (position + 8 <= wav.length) {
            var id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            var size = buffer.getInt(position + 4);
            if (id.equals("data")) {
                return Arrays.copyOfRange(wav, position + 8, Math.min(wav.length, position + 8 + size));
            }
            position += 8 + size + (size & 1);
        }
        throw new AssertionError("no data chunk");
    }

    /** Returns a WAVE file of 16 or 24-bit tones, one frequency per channel. */
    private static byte[] wav(int channels, int frames, int bits) {
        var bytes = bits / 8;
        var size = frames * channels * bytes;
        var data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + size)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        data.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(RATE).putInt(RATE * channels * bytes)
                .putShort((short) (channels * bytes)).putShort((short) bits);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                var tone = StrictMath.sin(2 * StrictMath.PI * (440 + 110 * channel) * frame / RATE);
                var value = (int) (8000 * tone) << (bits - 16);
                for (var index = 0; index < bytes; index++) {
                    data.put((byte) (value >> 8 * index));
                }
            }
        }
        return data.array();
    }
}
