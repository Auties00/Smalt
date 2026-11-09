package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AAC tracks whose MP4 spatial audio box ({@code SA3D}) declares an ambisonic sound field: ffmpeg codes
 * ambisonic PCM, every channel a tone of its own frequency, and the box is added both as Google's spatial media
 * injector adds it (appended to the sample entry of ffmpeg's file) and by Smalt's MP4 muxer remuxing ffmpeg's packets;
 * the decoded channels must carry the components in the PCM's order. ffmpeg codes four channels with channel
 * configuration 4 (coded C L R Cs, which the decoder outputs in WAVE order, the PCM's order) and sixteen with a
 * program configuration whose channels have no position (the sixteen channels of no defined order, output in coded
 * order, the PCM's order).
 */
@DisplayName("AAC with a declared ambisonic layout")
class AacAmbisonicLayoutTest {
    private static final int RATE = 48_000;

    private static final int FRAMES = 48_000;

    @ParameterizedTest(name = "order {0}")
    @ValueSource(ints = {1, 3})
    @DisplayName("ffmpeg's AAC with an injected spatial audio box decodes to the PCM's component order")
    void injected(int order) throws IOException {
        var coded = encode(order);
        var channels = (order + 1) * (order + 1);
        var map = new long[channels];
        for (var k = 0; k < channels; k++) {
            map[k] = k;
        }
        var injected = inject(Files.readAllBytes(coded), sa3d(order, map));
        assertComponents(injected, ChannelLayout.ofAmbisonics(order));
    }

    @ParameterizedTest(name = "order {0}")
    @ValueSource(ints = {1, 3})
    @DisplayName("ffmpeg's AAC remuxed by Smalt with an ambisonic layout decodes to the PCM's component order")
    void remuxed(int order) throws IOException {
        var coded = encode(order);
        var layout = ChannelLayout.ofAmbisonics(order);
        var sink = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(coded));
             var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().build())) {
            var track = ((AudioTrack) demuxer.tracks().getFirst()).toBuilder().layout(layout).build();
            muxer.addTrack(track);
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var bytes = sink.toByteArray();
        assertTrue(indexOf(bytes, "SA3D") > 0, "the muxer writes the spatial audio box");
        assertComponents(bytes, layout);
    }

    @Test
    @DisplayName("double output of a program configuration keeps the codec's order and reports its components")
    void exactOutputKeepsCodecOrder() throws IOException {
        var coded = encode(3);
        var map = new long[16];
        for (var k = 0; k < 16; k++) {
            map[k] = k;
        }
        var injected = inject(Files.readAllBytes(coded), sa3d(3, map));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(injected))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var options = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.DOUBLE).build();
            try (var decoder = AudioStreamDecoder.create(track, options)) {
                var layout = decoder.layout();
                var components = layout.ambisonics().orElseThrow().components();
                assertEquals(16, components.size());
                assertTrue(!components.equals(Ambisonics.of(3).components()),
                        "the codec reorders the program configuration's channels: " + components);
                var planes = new double[16][FRAMES];
                var filled = 0;
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet);
                    filled += decoder.readDoubles(planes, filled, FRAMES - filled);
                }
                decoder.finish();
                filled += decoder.readDoubles(planes, filled, FRAMES - filled);
                var tones = tones(16);
                for (var k = 0; k < 16; k++) {
                    var plane = new float[filled];
                    for (var i = 0; i < filled; i++) {
                        plane[i] = (float) planes[k][i];
                    }
                    assertEquals(components.get(k), TestTones.strongest(plane, RATE, tones), "output channel " + k);
                }
            }
        }
    }

    @Test
    @DisplayName("a component order other than ACN is honored through the reordering")
    void permutedMap() throws IOException {
        // the file holds the PCM's channels; a map naming channel k as component perm[k] relabels them
        var coded = encode(1);
        long[] map = {3, 1, 2, 0};
        var injected = inject(Files.readAllBytes(coded), sa3d(1, map));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(injected))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var declared = ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D,
                    List.of(3, 1, 2, 0)));
            assertEquals(declared, track.layout().orElseThrow());
            var planes = decode(demuxer, track);
            // the output is the declared layout; PCM channel k carries component map[k]
            for (var k = 0; k < 4; k++) {
                assertEquals(k, TestTones.strongest(planes[k], RATE, tones(4)), "PCM channel " + k);
            }
        }
    }

    @Test
    @DisplayName("the committed sample of the spatial media injector decodes with its components in ACN order")
    void committedSample() throws IOException {
        // core's behavior sample: pink noise panned to channel k at gain 1 / (k + 1), coded by ffmpeg as 4.0
        var file = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "samples", "mp4",
                "ambisonic-aac.mp4"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(ChannelLayout.ofAmbisonics(1), track.layout().orElseThrow());
            var planes = decode(demuxer, track);
            var reference = rms(planes[0]);
            // AAC's quantization of correlated noise moves the gains by a few percent; a channel in another place
            // would be off by a third or more
            for (var k = 1; k < 4; k++) {
                var ratio = rms(planes[k]) / reference;
                assertEquals(1.0 / (k + 1), ratio, 0.15 / (k + 1), "the gain of component " + k);
                assertTrue(ratio < rms(planes[k - 1]) / reference, "component " + k + " is quieter than " + (k - 1));
            }
        }
    }

    private static double rms(float[] plane) {
        var sum = 0.0;
        var count = 0;
        for (var sample : plane) {
            sum += (double) sample * sample;
            count++;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }

    @Test
    @DisplayName("the decoder reports the coded channel of each output channel")
    void codedOrder() {
        // configuration 4 codes C L R Cs, output as L R C Cs
        assertEquals(List.of(1, 2, 0, 3), codedOrder(AudioConfigs.audioSpecificConfig(2, RATE, 4, 0, false)));
        // configuration 6 codes C L R Ls Rs LFE, output as L R C LFE Ls Rs
        assertEquals(List.of(1, 2, 0, 5, 3, 4), codedOrder(AudioConfigs.audioSpecificConfig(2, RATE, 6, 0, false)));
        assertEquals(List.of(0, 1), codedOrder(AudioConfigs.audioSpecificConfig(2, RATE, 2, 0, false)));
    }

    private static List<Integer> codedOrder(byte[] config) {
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE)).codecConfig(config)
                .sampleRate(RATE).build();
        try (var decoder = new AacDecoder(track)) {
            return Arrays.stream(decoder.codedChannelOrder()).boxed().toList();
        }
    }

    /**
     * Decodes a file with the stream decoder and checks its layout, and that ambisonic channel {@code k} carries the
     * tone of PCM channel {@code k}.
     */
    private static void assertComponents(byte[] file, ChannelLayout expected) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.AAC, track.codec());
            assertEquals(expected, track.layout().orElseThrow());
            var planes = decode(demuxer, track);
            var channels = expected.channels();
            var tones = tones(channels);
            for (var k = 0; k < channels; k++) {
                assertEquals(k, TestTones.strongest(planes[k], RATE, tones), "component " + k);
            }
        }
    }

    private static float[][] decode(Demuxer demuxer, AudioTrack track) {
        try (var decoder = AudioStreamDecoder.create(track)) {
            assertEquals(track.layout().orElseThrow(), decoder.layout(), "the output is the declared layout");
            var planes = new float[track.layout().orElseThrow().channels()][FRAMES];
            var filled = 0;
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet);
                filled += decoder.read(planes, filled, FRAMES - filled);
            }
            decoder.finish();
            filled += decoder.read(planes, filled, FRAMES - filled);
            assertTrue(filled > 0, "decoded " + filled + " samples");
            for (var channel = 0; channel < planes.length; channel++) {
                planes[channel] = Arrays.copyOf(planes[channel], filled);
            }
            return planes;
        }
    }

    /** {@return the tone frequency of every channel, spaced so that AAC keeps them apart} */
    private static double[] tones(int channels) {
        var tones = new double[channels];
        for (var k = 0; k < channels; k++) {
            tones[k] = 300 + 650 * k;
        }
        return tones;
    }

    /** Codes ambisonic PCM of an order with ffmpeg's AAC encoder into an MP4 file. */
    private static Path encode(int order) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var channels = (order + 1) * (order + 1);
        var directory = Files.createDirectories(Path.of("target", "aac-ambisonic"));
        var tones = tones(channels);
        var pcm = ByteBuffer.allocate(FRAMES * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < FRAMES; i++) {
            for (var k = 0; k < channels; k++) {
                pcm.putShort((short) Math.round(6000 * StrictMath.sin(2 * StrictMath.PI * tones[k] * i / RATE)));
            }
        }
        var raw = Files.write(directory.resolve("order" + order + ".raw"), pcm.array());
        var coded = directory.resolve("order" + order + ".mp4");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "s16le", "-ar", Integer.toString(RATE), "-ch_layout", order == 1 ? "4.0" : channels + "c",
                "-i", raw.toString(),
                "-c:a", "aac", "-b:a", Integer.toString(64_000 * channels), "-bitexact", coded.toString()));
        return coded;
    }

    /** Builds a spatial audio box of a periphonic ACN SN3D sound field. */
    private static byte[] sa3d(int order, long[] map) {
        var box = ByteBuffer.allocate(20 + 4 * map.length);
        box.putInt(20 + 4 * map.length).put("SA3D".getBytes(StandardCharsets.US_ASCII));
        box.put((byte) 0).put((byte) 0).putInt(order).put((byte) 0).put((byte) 0).putInt(map.length);
        for (var entry : map) {
            box.putInt((int) entry);
        }
        return box.array();
    }

    /**
     * Appends a box to the first sample entry of a file whose {@code moov} follows its media, growing every enclosing
     * box, as the spatial media injector does.
     */
    private static byte[] inject(byte[] file, byte[] box) {
        var parents = new ArrayList<Integer>();
        var insert = find(file, 0, file.length, parents);
        assertTrue(insert > 0, "an audio sample entry");
        var data = ByteBuffer.wrap(file);
        for (var parent : parents) {
            data.putInt(parent, data.getInt(parent) + box.length);
        }
        var result = new byte[file.length + box.length];
        System.arraycopy(file, 0, result, 0, insert);
        System.arraycopy(box, 0, result, insert, box.length);
        System.arraycopy(file, insert, result, insert + box.length, file.length - insert);
        return result;
    }

    private static int find(byte[] file, int start, int end, List<Integer> parents) {
        var data = ByteBuffer.wrap(file);
        var position = start;
        while (position + 8 <= end) {
            var size = data.getInt(position);
            var type = new String(file, position + 4, 4, StandardCharsets.US_ASCII);
            if (List.of("moov", "trak", "mdia", "minf", "stbl").contains(type)) {
                parents.add(position);
                var hit = find(file, position + 8, position + size, parents);
                if (hit > 0) {
                    return hit;
                }
                parents.removeLast();
            } else if (type.equals("stsd")) {
                var entry = position + 16;
                parents.add(position);
                parents.add(entry);
                return entry + data.getInt(entry);
            }
            position += size;
        }
        return -1;
    }

    private static int indexOf(byte[] data, String type) {
        var pattern = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var i = 0; i + 4 <= data.length; i++) {
            for (var j = 0; j < 4; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Finds the strongest of a set of tones in a signal. */
    static final class TestTones {
        private TestTones() {
        }

        static int strongest(float[] signal, int rate, double[] tones) {
            var best = -1;
            var bestPower = -1.0;
            var start = signal.length / 4;
            var length = Math.min(8192, signal.length / 2);
            for (var t = 0; t < tones.length; t++) {
                double re = 0;
                double im = 0;
                for (var i = 0; i < length; i++) {
                    var phase = 2 * StrictMath.PI * tones[t] * i / rate;
                    re += signal[start + i] * StrictMath.cos(phase);
                    im += signal[start + i] * StrictMath.sin(phase);
                }
                var power = re * re + im * im;
                if (power > bestPower) {
                    bestPower = power;
                    best = t;
                }
            }
            return best;
        }
    }
}
