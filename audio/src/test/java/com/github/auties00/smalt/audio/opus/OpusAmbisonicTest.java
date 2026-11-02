package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioWriter;
import com.github.auties00.smalt.audio.AudioWriterOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ambisonic channel layouts in Opus (RFC 8486): the mapping the encoder chooses for an ambisonic layout,
 * the layouts it rejects, the layout decoders report, plane waves coded by Smalt and by libopus (through ffmpeg)
 * that decode to the left and right gains of the RFC 8486 stereo rendering, the agreement of that rendering in
 * {@link ChannelMixer} with the decoder's own downmix, and the family 3 identification header in MP4.
 */
@DisplayName("Opus ambisonics")
class OpusAmbisonicTest {
    private static final ChannelLayout HEAD_LOCKED = ChannelLayout.ofAmbisonics(Ambisonics.of(1),
            List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));

    private static final int FRAMES = 48000;

    /**
     * Returns first-order SN3D planes of a 500 Hz plane wave from a horizontal azimuth, plus a 300 Hz tone on the
     * left head-locked channel when the layout has one.
     */
    private static float[][] planeWave(ChannelLayout layout, double degrees) {
        var azimuth = Math.toRadians(degrees);
        var gains = new double[]{1, Math.sin(azimuth), 0, Math.cos(azimuth)};
        var planes = new float[layout.channels()][FRAMES];
        for (var t = 0; t < FRAMES; t++) {
            var wave = 0.25 * Math.sin(2 * Math.PI * 500 * t / 48000.0);
            for (var acn = 0; acn < 4; acn++) {
                planes[layout.indexOfComponent(acn)][t] = (float) (gains[acn] * wave);
            }
            if (layout.channels() == 6) {
                planes[4][t] = (float) (0.25 * Math.sin(2 * Math.PI * 300 * t / 48000.0));
            }
        }
        return planes;
    }

    private static double rms(float[] plane, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
            sum += plane[i] * (double) plane[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    private static float[][] stereo(ChannelLayout layout, float[][] planes) {
        var frames = planes[0].length;
        var out = new float[2][frames];
        new ChannelMixer(layout, ChannelLayout.STEREO).process(planes, out, frames);
        return out;
    }

    /**
     * Asserts that a decoded first-order plane wave renders to stereo with the RFC 8486 gains
     * {@code (1 + sin(A)) / 2} and {@code (1 - sin(A)) / 2}, within the coding loss.
     */
    private static void assertGains(ChannelLayout layout, float[][] decoded, double degrees) {
        var out = stereo(layout, decoded);
        var reference = stereo(layout, planeWave(layout, degrees));
        var from = 4800;
        var to = Math.min(out[0].length, FRAMES) - 4800;
        for (var side = 0; side < 2; side++) {
            var expected = rms(reference[side], from, to);
            var actual = rms(out[side], from, to);
            assertEquals(expected, actual, 0.01 + 0.05 * expected, "side " + side + " at " + degrees + " degrees");
        }
    }

    @Nested
    @DisplayName("encoder options")
    class Options {
        @Test
        @DisplayName("choose family 2 for an AmbiX layout")
        void automatic() {
            for (var layout : List.of(ChannelLayout.ofAmbisonics(0), ChannelLayout.ofAmbisonics(1), HEAD_LOCKED,
                    ChannelLayout.ofAmbisonics(3), ChannelLayout.ofAmbisonics(14))) {
                var options = OpusEncoderOptions.builder().format(48000, layout).build();
                assertEquals(OpusChannelMapping.AMBISONIC, options.channelMapping(), layout.toString());
                var order = layout.ambisonics().orElseThrow().order();
                if (order >= 1 && order <= 5) {
                    assertEquals(OpusChannelMapping.AMBISONIC_PROJECTION, options.toBuilder()
                            .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).build().channelMapping());
                }
            }
        }

        @Test
        @DisplayName("reject ambisonics Opus cannot carry, unless coded as discrete channels")
        void rejects() {
            var layouts = List.of(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D)),
                    ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(0, 3, 1, 2))),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_RIGHT, Speaker.FRONT_LEFT)),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_CENTER)),
                    new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN,
                            Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN), Optional.of(Ambisonics.of(1))));
            for (var layout : layouts) {
                for (var mapping : new OpusChannelMapping[]{null, OpusChannelMapping.AMBISONIC,
                        OpusChannelMapping.AMBISONIC_PROJECTION}) {
                    var builder = OpusEncoderOptions.builder().format(48000, layout);
                    if (mapping != null) {
                        builder.channelMapping(mapping);
                    }
                    assertThrows(IllegalArgumentException.class, builder::build, layout + " " + mapping);
                }
                assertEquals(OpusChannelMapping.DISCRETE, OpusEncoderOptions.builder().format(48000, layout)
                        .channelMapping(OpusChannelMapping.DISCRETE).build().channelMapping());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OpusChannelMapping.class, names = {"AMBISONIC", "AMBISONIC_PROJECTION"})
    @DisplayName("codes plane waves that decode, in the ambisonic layout, to the RFC 8486 stereo gains")
    void planeWaves(OpusChannelMapping mapping) {
        for (var layout : List.of(ChannelLayout.ofAmbisonics(1), HEAD_LOCKED)) {
            for (var degrees : new int[]{90, 0, -90, 30, 150}) {
                var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, layout).channelMapping(mapping)
                        .bitrate(64000 * layout.channels()).build());
                var packets = OpusMultistreamApiTest.encode(encoder, planeWave(layout, degrees));
                var decoder = new OpusDecoder(OpusMultistreamApiTest.track(encoder.codecConfig().orElseThrow(), 48000));
                assertEquals(layout, decoder.layout());
                var decoded = OpusMultistreamApiTest.decode(decoder, packets);
                assertGains(layout, decoded, degrees);
                var out = stereo(layout, decoded);
                if (degrees == 90 && layout.channels() == 4) {
                    assertTrue(rms(out[1], 4800, 40000) < 0.05 * rms(out[0], 4800, 40000), "right is silent");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6})
    @DisplayName("decodes libopus plane waves to the ambisonic layout and the RFC 8486 gains")
    void libopus(int channels) {
        Oracle.assumeEncoder("libopus");
        var layout = channels == 4 ? ChannelLayout.ofAmbisonics(1) : HEAD_LOCKED;
        for (var degrees : new int[]{90, -90, 0}) {
            var azimuth = Math.toRadians(degrees);
            // ACN W Y Z X of a 500 Hz plane wave, and a 300 Hz tone on the left head-locked channel
            var pan = String.format(Locale.ROOT, "pan=%dC|c0=c0|c1=%.6f*c0|c2=0*c0|c3=%.6f*c0%s", channels,
                    Math.sin(azimuth), Math.cos(azimuth), channels == 6 ? "|c4=c1|c5=0*c1" : "");
            var file = Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i",
                    "aevalsrc=0.25*sin(2*PI*500*t)|0.25*sin(2*PI*300*t):s=48000:d=1"), List.of("-af", pan,
                    "-c:a", "libopus", "-mapping_family", "2", "-b:a", (64 * channels) + "k"));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(layout, track.layout().orElseThrow());
                var decoder = new OpusDecoder(track);
                assertEquals(layout, decoder.layout());
                var packets = new ArrayList<Packet>();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    packets.add(packet);
                }
                assertGains(layout, OpusMultistreamApiTest.decode(decoder, packets), degrees);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OpusChannelMapping.class, names = {"AMBISONIC", "AMBISONIC_PROJECTION"})
    @DisplayName("ChannelMixer renders a decoded stream to stereo and mono as the decoder's own downmix does")
    void downmixAgreement(OpusChannelMapping mapping) {
        for (var layout : List.of(ChannelLayout.ofAmbisonics(1), HEAD_LOCKED, ChannelLayout.ofAmbisonics(2))) {
            var input = OpusMultistreamApiTest.tones(layout, 48000, 9600);
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, layout).channelMapping(mapping)
                    .bitrate(48000 * layout.channels()).build());
            var packets = OpusMultistreamApiTest.encode(encoder, input);
            var head = encoder.codecConfig().orElseThrow();
            var full = OpusMultistreamApiTest.decode(new OpusDecoder(OpusMultistreamApiTest.track(head, 48000)), packets);
            for (var target : List.of(ChannelLayout.STEREO, ChannelLayout.MONO)) {
                var mixed = new float[target.channels()][full[0].length];
                new ChannelMixer(layout, target).process(full, mixed, full[0].length);
                var downmixed = OpusMultistreamApiTest.decode(new OpusDecoder(OpusMultistreamApiTest.track(head, 48000),
                        OpusDecoderOptions.builder().channels(target.channels()).build()), packets);
                for (var c = 0; c < target.channels(); c++) {
                    for (var t = 0; t < mixed[c].length; t++) {
                        assertEquals(downmixed[c][t], mixed[c][t], 1e-5, layout + " to " + target + " at " + t);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("writes the family 3 demixing matrix into dOps in little-endian Ogg order and reads it back")
    void dops() {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.ofAmbisonics(1))
                .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).bitrate(256000).build());
        var packets = OpusMultistreamApiTest.encode(encoder, planeWave(ChannelLayout.ofAmbisonics(1), 45));
        var head = encoder.codecConfig().orElseThrow();
        var track = AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .layout(encoder.layout()).codecConfig(head).build();
        Function<MediaSink, Muxer> mp4 = Mp4Muxer::new;
        var sink = MediaSink.memory();
        try (var muxer = mp4.apply(sink)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        var file = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("ambisonic-projection.mp4", file);
        var dops = indexOf(file, "dOps") + 4;
        assertEquals(0, file[dops], "version");
        assertEquals(3, file[dops + 10], "mapping family");
        // stream counts and the RFC 8486 matrix follow the family byte as the OpusHead holds them (Chromium)
        assertArrayEquals(Arrays.copyOfRange(head, 19, head.length), Arrays.copyOfRange(file, dops + 11,
                dops + 11 + head.length - 19));
        assertTrue(indexOf(file, "SA3D") > 0, "spatial audio box");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertArrayEquals(head, read.codecConfig().orElseThrow());
            assertEquals(ChannelLayout.ofAmbisonics(1), read.layout().orElseThrow());
        }
        var ogg = MediaSink.memory();
        try (var muxer = new OggMuxer(ogg)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(ogg.toByteArray()))) {
            assertEquals(ChannelLayout.ofAmbisonics(1), ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(value = OutputContainer.class, names = {"OGG", "MATROSKA", "MP4", "MOV"})
    @DisplayName("AudioWriter and AudioReader keep an ambisonic layout through Opus and FLAC files")
    void writer(OutputContainer container) {
        var codecs = (container == OutputContainer.MATROSKA || container == OutputContainer.OGG ? List.of(Codec.OPUS)
                : List.of(Codec.OPUS, Codec.FLAC, Codec.PCM_S16LE)).stream().filter(container::supports).toList();
        assertTrue(codecs.contains(Codec.OPUS), container.toString());
        for (var codec : codecs) {
            for (var layout : List.of(ChannelLayout.ofAmbisonics(1), HEAD_LOCKED)) {
                var sink = MediaSink.memory();
                try (var writer = AudioWriter.open(sink, container, AudioWriterOptions.of(48000, layout).toBuilder()
                        .codec(codec).build())) {
                    var planes = planeWave(layout, 60);
                    writer.write(new AudioBuffer(layout, 48000, planes, planes[0].length));
                    writer.finish();
                }
                var file = sink.toByteArray();
                try (var reader = AudioReader.open(MediaSource.of(file))) {
                    assertEquals(layout, reader.layout(), container + " " + codec);
                    assertEquals(layout, reader.track().layout().orElseThrow(), container + " " + codec);
                }
                if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                    var path = com.github.auties00.smalt.testkit.Scratch.directory("opus-ambisonic-writer")
                            .resolve(container + "-" + codec + "-" + layout.channels() + "." + container.extension());
                    try {
                        java.nio.file.Files.write(path, file);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                    var name = layout.channels() == 6 ? "ambisonic 1+stereo" : "ambisonic 1";
                    var probe = Oracle.probe(path);
                    assertTrue(probe.contains("\"channel_layout\": \"" + name + "\""), container + " " + codec
                            + ": " + probe);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {90, -90, 30})
    @DisplayName("AudioWriter codes Furse-Malham and N3D input as the same sound field in ACN order with SN3D")
    void convertedInput(int degrees) {
        var target = ChannelLayout.ofAmbisonics(1);
        var reference = planeWave(target, degrees);
        for (var input : List.of(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D)),
                ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(3, 1, 2, 0))))) {
            var normalization = input.ambisonics().orElseThrow().normalization();
            var planes = new float[4][FRAMES];
            for (var acn = 0; acn < 4; acn++) {
                // FuMa W is SN3D W / sqrt(2); N3D first-order components are SN3D * sqrt(3)
                var scale = switch (normalization) {
                    case FUMA -> acn == 0 ? 1 / Math.sqrt(2) : 1;
                    case N3D -> acn == 0 ? 1 : Math.sqrt(3);
                    case SN3D -> 1;
                };
                for (var t = 0; t < FRAMES; t++) {
                    planes[input.indexOfComponent(acn)][t] = (float) (scale * reference[acn][t]);
                }
            }
            var sink = MediaSink.memory();
            try (var writer = AudioWriter.open(sink, OutputContainer.OGG, AudioWriterOptions.of(48000, input).toBuilder()
                    .codec(Codec.OPUS).bitrate(256000).build())) {
                assertEquals(target, writer.track().layout().orElseThrow(), input.toString());
                writer.write(new AudioBuffer(input, 48000, planes, FRAMES));
                writer.finish();
            }
            var decoded = new float[4][FRAMES + 48000];
            var frames = 0;
            try (var reader = AudioReader.open(MediaSource.of(sink.toByteArray()))) {
                assertEquals(target, reader.layout(), input.toString());
                AudioBuffer block;
                while ((block = reader.read()) != null && frames < decoded[0].length) {
                    var count = Math.min(block.frames(), decoded[0].length - frames);
                    for (var c = 0; c < 4; c++) {
                        System.arraycopy(block.plane(c), 0, decoded[c], frames, count);
                    }
                    frames += count;
                }
            }
            for (var acn = 0; acn < 4; acn++) {
                var expected = rms(reference[acn], 4800, FRAMES - 4800);
                assertEquals(expected, rms(decoded[acn], 4800, FRAMES - 4800), 0.005 + 0.05 * expected,
                        input + " ACN " + acn);
            }
            assertGains(target, Arrays.stream(decoded).map(plane -> Arrays.copyOf(plane, FRAMES))
                    .toArray(float[][]::new), degrees);
        }
    }

    @Test
    @DisplayName("an ambisonic layout survives the generic encoder API and AudioBuffer checks")
    void buffers() {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, HEAD_LOCKED).build());
        var planes = planeWave(HEAD_LOCKED, 30);
        var packets = new ArrayList<Packet>();
        encoder.encode(new AudioBuffer(HEAD_LOCKED, 48000, planes, 960), packets::add);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(
                ChannelLayout.defaultFor(6), 48000, planes, 960), packets::add));
        assertEquals(HEAD_LOCKED, encoder.layout());
    }

    private static int indexOf(byte[] data, String type) {
        var pattern = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (var index = 0; index + pattern.length <= data.length; index++) {
            for (var offset = 0; offset < pattern.length; offset++) {
                if (data[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
