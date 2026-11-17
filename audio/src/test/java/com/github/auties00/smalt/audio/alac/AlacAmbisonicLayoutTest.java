package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.audio.alac.AmbisonicFixtures.FRAMES;
import static com.github.auties00.smalt.audio.alac.AmbisonicFixtures.RATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers ALAC tracks whose MP4 spatial audio box declares an ambisonic sound field: the ALAC decoder reorders the
 * coded channels (center first) to WAVE order, the order ALAC encoders take their input in, so the declared
 * components apply to the decoded channels and read back in the order of the PCM that was coded, for Smalt's encoder
 * and for ffmpeg's.
 */
@DisplayName("ALAC with a declared ambisonic layout")
class AlacAmbisonicLayoutTest {
    @Test
    @DisplayName("Smalt's ALAC of first-order ambisonics reads back in the PCM's order, bit for bit")
    void smaltFirstOrder() {
        var layout = ChannelLayout.ofAmbisonics(1);
        var file = smalt(layout);
        AmbisonicFixtures.assertChannels(file, layout);
        assertExact(file, layout);
    }

    @Test
    @DisplayName("Smalt's ALAC of a sound field with a head-locked pair reads back in the PCM's order, bit for bit")
    void smaltHeadLocked() {
        var layout = ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
        var file = smalt(layout);
        AmbisonicFixtures.assertChannels(file, layout);
        assertExact(file, layout);
    }

    @Test
    @DisplayName("ffmpeg's ALAC with an injected spatial audio box reads back in the PCM's order, bit for bit")
    void ffmpeg() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var directory = Files.createDirectories(Path.of("target", "alac-ambisonic"));
        var raw = Files.write(directory.resolve("order1.raw"), AmbisonicFixtures.pcm(4));
        var coded = directory.resolve("order1.mp4");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "s16le", "-ar", Integer.toString(RATE), "-ch_layout", "4.0", "-i", raw.toString(),
                "-c:a", "alac", "-bitexact", coded.toString()));
        var file = AmbisonicFixtures.inject(Files.readAllBytes(coded), AmbisonicFixtures.sa3d(1, false, 0, 1, 2, 3));
        var layout = ChannelLayout.ofAmbisonics(1);
        AmbisonicFixtures.assertChannels(file, layout);
        assertExact(file, layout);
    }

    @Test
    @DisplayName("the decoder reports the coded channel of each output channel, the center coded first")
    void codedOrder() {
        assertEquals(List.of(1, 2, 0, 3), codedOrder(4));
        assertEquals(List.of(1, 2, 0, 5, 3, 4), codedOrder(6));
        assertEquals(List.of(0, 1), codedOrder(2));
    }

    private static List<Integer> codedOrder(int channels) {
        var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(RATE, ChannelLayout.defaultFor(channels))
                .build());
        var track = AudioTrack.builder().id(1).codec(Codec.ALAC).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .codecConfig(encoder.codecConfig().orElseThrow()).build();
        try (var decoder = new AlacDecoder(track)) {
            return java.util.Arrays.stream(decoder.codedChannelOrder()).boxed().toList();
        }
    }

    /** Codes the fixture PCM of a layout with Smalt's ALAC encoder into an MP4 file. */
    private static byte[] smalt(ChannelLayout layout) {
        var channels = layout.channels();
        var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(RATE, layout).bitsPerSample(16).build());
        var packets = new ArrayList<Packet>();
        encoder.encodeInterleaved(AlacSignals.interleave(AmbisonicFixtures.planes(channels)), 0, FRAMES, packets::add);
        encoder.flush(packets::add);
        var track = AudioTrack.builder().id(1).codec(Codec.ALAC).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .layout(layout).bitsPerSample(16).codecConfig(encoder.codecConfig().orElseThrow()).build();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().build())) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(1));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Checks that the integer output of a file is the fixture PCM, channel for channel. */
    private static void assertExact(byte[] file, ChannelLayout layout) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var options = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
            try (var decoder = AudioStreamDecoder.create(track, options)) {
                assertEquals(layout, decoder.layout(), "the integer output's layout");
                var planes = new int[layout.channels()][FRAMES];
                var filled = 0;
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet);
                    filled += decoder.readIntegers(planes, filled, FRAMES - filled);
                }
                decoder.finish();
                filled += decoder.readIntegers(planes, filled, FRAMES - filled);
                assertEquals(FRAMES, filled);
                var expected = AmbisonicFixtures.planes(layout.channels());
                for (var k = 0; k < layout.channels(); k++) {
                    assertArrayEquals(expected[k], planes[k], "channel " + k);
                }
            }
        }
    }
}
