package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the ambisonic layouts of CAF and QuickTime files against Core Audio on macOS, as a black box: the
 * higher-order ambisonics and B-format layout tags {@code afconvert} writes are read with their order, normalization
 * and channel order and written back identically, and the HOA and B-format channel labels Smalt writes for a sound
 * field no tag describes are understood by Core Audio, which reorders the channels to the tag's order by them.
 */
@DisplayName("ambisonic channel layouts against Core Audio")
class CafAmbisonicAppleTest {
    private static final int RATE = 48_000;

    private static final int FRAMES = 4800;

    private Path directory;

    private Path afconvert;

    @BeforeEach
    void requireMac(TestInfo info) throws IOException {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "afconvert is part of macOS");
        afconvert = Oracle.find("afconvert").orElseThrow(() -> new AssertionError("macOS without afconvert"));
        directory = Files.createDirectories(Path.of("target", "media-fixtures", "caf-ambisonic-apple",
                info.getDisplayName().replaceAll("[^A-Za-z0-9]+", "-")));
    }

    @ParameterizedTest(name = "{0} {1} channels")
    @CsvSource({
            "HOA_ACN_SN3D, 4, 1, SN3D",
            "HOA_ACN_SN3D, 9, 2, SN3D",
            "HOA_ACN_SN3D, 16, 3, SN3D",
            "HOA_ACN_N3D, 4, 1, N3D",
            "HOA_ACN_N3D, 25, 4, N3D"})
    @DisplayName("Core Audio's higher-order ambisonics tag is read, and written back identically")
    void hoaTag(String tag, int channels, int order, Ambisonics.Normalization normalization) throws IOException {
        var expected = ChannelLayout.ofAmbisonics(Ambisonics.of(order, normalization));
        readAndWriteBack(tag, channels, expected, (tag.endsWith("SN3D") ? 190 : 191) << 16 | channels);
    }

    @Test
    @DisplayName("Core Audio's B-format tag is read as Furse-Malham W X Y Z, and written back identically")
    void bFormatTag() throws IOException {
        readAndWriteBack("Ambisonic_B_Format", 4, ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                107 << 16 | 4);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "SN3D, HOA_ACN_SN3D",
            "N3D, HOA_ACN_N3D"})
    @DisplayName("Core Audio reorders channels labelled with their HOA components to the ACN order of the tag")
    void hoaLabels(Ambisonics.Normalization normalization, String tag) throws IOException {
        var components = List.of(3, 0, 2, 1);
        var layout = ChannelLayout.ofAmbisonics(new Ambisonics(normalization, components));
        var smalt = writeCaf("labels-" + normalization, layout);
        var reread = (AudioTrack) ContainerProbe.demux(smalt, CafDemuxer::new).tracks().getFirst();
        assertEquals(layout, reread.layout().orElseThrow(), "Smalt reads its own labels back");
        assertReordered(smalt, tag, components);
    }

    @Test
    @DisplayName("Core Audio reorders channels labelled with the B-format labels to W X Y Z")
    void bFormatLabels() throws IOException {
        // Furse-Malham Y Z X W, in ACN numbers
        var components = List.of(1, 2, 3, 0);
        var layout = ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.FUMA, components));
        var smalt = writeCaf("labels-fuma", layout);
        var reread = (AudioTrack) ContainerProbe.demux(smalt, CafDemuxer::new).tracks().getFirst();
        assertEquals(layout, reread.layout().orElseThrow());
        assertReordered(smalt, "Ambisonic_B_Format", components);
    }

    @Test
    @DisplayName("Core Audio reads the higher-order ambisonics tag of Smalt's QuickTime file")
    void quickTime() throws IOException {
        var layout = ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D));
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .layout(layout).duration(FRAMES).build();
        var packet = new Packet(1, pcm(4), 0, 0, FRAMES, true);
        var mov = Files.write(directory.resolve("smalt.mov"), MuxSupport.toMemory(List.of(track), List.of(packet),
                sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build())));
        var reread = (AudioTrack) ContainerProbe.demux(mov, Mp4Demuxer::new).tracks().getFirst();
        assertEquals(layout, reread.layout().orElseThrow(), "Smalt reads its own chan atom back");
        var afinfo = afinfo(mov);
        assertTrue(afinfo.contains("High-Order Ambisonics, ACN/N3D"), afinfo);
        var decoded = directory.resolve("smalt-mov.caf");
        run(List.of("-f", "caff", "-d", "LEI16", mov.toString(), decoded.toString()));
        var caf = (AudioTrack) ContainerProbe.demux(decoded, CafDemuxer::new).tracks().getFirst();
        assertEquals(layout, caf.layout().orElseThrow(), "Core Audio carries the layout over to CAF");
    }

    /**
     * Checks that a file Core Audio writes with a layout tag reads with the expected layout and samples, and that
     * Smalt writes the same {@code chan} chunk again.
     */
    private void readAndWriteBack(String tag, int channels, ChannelLayout expected, int tagValue) throws IOException {
        var source = Files.write(directory.resolve("source.wav"), wav(channels));
        var apple = directory.resolve("apple.caf");
        run(List.of("-f", "caff", "-d", "LEI16", "-l", tag, source.toString(), apple.toString()));
        var appleChan = chan(Files.readAllBytes(apple));
        assertEquals(tagValue, ByteBuffer.wrap(appleChan).getInt(), "the tag afconvert writes");
        var input = ContainerProbe.demux(apple, CafDemuxer::new);
        var track = (AudioTrack) input.tracks().getFirst();
        assertEquals(expected, track.layout().orElseThrow());
        var samples = new java.io.ByteArrayOutputStream();
        input.order().forEach(packet -> samples.writeBytes(packet.data()));
        assertArrayEquals(pcm(channels), samples.toByteArray(), "the channels in their order");
        var smalt = Files.write(directory.resolve("smalt.caf"),
                MuxSupport.toMemory(List.of(track), input.order(), CafMuxer::new));
        assertArrayEquals(appleChan, chan(Files.readAllBytes(smalt)), "Smalt's chan chunk");
        assertEquals(layoutLine(afinfo(apple)), layoutLine(afinfo(smalt)), "afinfo's channel layout");
    }

    /**
     * Checks that Core Audio, converting a file to a layout tag, moves the channel of each component to the tag's
     * position.
     */
    private void assertReordered(Path smalt, String tag, List<Integer> components) throws IOException {
        var converted = directory.resolve(tag + ".caf");
        run(List.of("-f", "caff", "-d", "LEI16", "-l", tag, smalt.toString(), converted.toString()));
        var output = ContainerProbe.demux(converted, CafDemuxer::new);
        var samples = new java.io.ByteArrayOutputStream();
        output.order().forEach(packet -> samples.writeBytes(packet.data()));
        var data = ByteBuffer.wrap(samples.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        var channels = components.size();
        assertEquals((long) FRAMES * channels * 2, data.capacity(), "the samples of " + tag);
        // the tag's channel k carries the component the tag puts there; tagged channel order is ACN except B-format
        var order = tag.equals("Ambisonic_B_Format") ? List.of(0, 3, 1, 2) : List.of(0, 1, 2, 3);
        for (var position = 0; position < channels; position++) {
            var source = components.indexOf(order.get(position));
            for (var frame = 0; frame < FRAMES; frame += 97) {
                assertEquals(value(source, frame), data.getShort((frame * channels + position) * 2),
                        tag + " channel " + position + " frame " + frame);
            }
        }
    }

    private Path writeCaf(String name, ChannelLayout layout) throws IOException {
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .layout(layout).duration(FRAMES).build();
        var packet = new Packet(1, pcm(layout.channels()), 0, 0, FRAMES, true);
        return Files.write(directory.resolve(name + ".caf"), MuxSupport.toMemory(List.of(track), List.of(packet),
                CafMuxer::new));
    }

    private String afinfo(Path file) {
        var afinfo = Oracle.find("afinfo").orElseThrow(() -> new AssertionError("macOS without afinfo"));
        var result = Oracle.execute(afinfo, Duration.ofMinutes(1), null, List.of(file.toString()));
        return new String(result.stdout(), StandardCharsets.UTF_8) + result.stderr();
    }

    private static String layoutLine(String afinfo) {
        return afinfo.lines().filter(line -> line.startsWith("Channel layout")).findFirst().orElse("");
    }

    private void run(List<String> arguments) {
        var result = Oracle.execute(afconvert, Duration.ofMinutes(2), null, arguments);
        assertEquals(0, result.exitCode(), "afconvert " + arguments + ": " + result.stderr()
                + new String(result.stdout(), StandardCharsets.UTF_8));
    }

    /** Returns the body of the first {@code chan} chunk of a CAF file. */
    private static byte[] chan(byte[] caf) {
        var buffer = ByteBuffer.wrap(caf);
        var position = 8;
        while (position + 12 <= caf.length) {
            var type = new String(caf, position, 4, StandardCharsets.US_ASCII);
            var size = buffer.getLong(position + 4);
            if (type.equals("chan")) {
                return Arrays.copyOfRange(caf, position + 12, position + 12 + (int) size);
            }
            if (size < 0) {
                break;
            }
            position += 12 + (int) size;
        }
        throw new AssertionError("no chan chunk");
    }

    /** {@return the sample of a channel at a frame: a distinct level per channel plus a ramp} */
    private static short value(int channel, int frame) {
        return (short) ((channel + 1) * 1000 + frame % 100);
    }

    private static byte[] pcm(int channels) {
        var data = ByteBuffer.allocate(FRAMES * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                data.putShort(value(channel, frame));
            }
        }
        return data.array();
    }

    private static byte[] wav(int channels) {
        var samples = pcm(channels);
        var data = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        data.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(RATE).putInt(RATE * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length).put(samples);
        return data.array();
    }
}
