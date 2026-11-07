package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.alac.AmbisonicFixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.audio.alac.AmbisonicFixtures.RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers Vorbis tracks whose MP4 spatial audio box declares an ambisonic sound field: ffmpeg codes ambisonic PCM with
 * libvorbis into MP4 and the box is appended to its sample entry, as Google's spatial media injector does; the Vorbis
 * decoder reorders the coded channels (the Vorbis I order) to WAVE order, the order libvorbis took them in, so the
 * components read back in the PCM's order.
 */
@DisplayName("Vorbis with a declared ambisonic layout")
class VorbisAmbisonicLayoutTest {
    @ParameterizedTest(name = "{0} channels")
    @CsvSource({"4, 4.0", "6, 5.1"})
    @DisplayName("ffmpeg's Vorbis with an injected spatial audio box reads back in the PCM's order")
    void injected(int channels, String ffmpegLayout) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assumeEncoder("libvorbis");
        var directory = Files.createDirectories(Path.of("target", "vorbis-ambisonic"));
        var tones = AmbisonicFixtures.tones(channels);
        if (channels == 6) {
            // libvorbis codes the sixth Vorbis channel, the LFE of 5.1 that PCM channel 3 lands on, band-limited
            tones[3] = 90;
        }
        var raw = Files.write(directory.resolve(channels + ".raw"), AmbisonicFixtures.pcm(tones));
        var coded = directory.resolve(channels + ".mp4");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "s16le", "-ar", Integer.toString(RATE), "-ch_layout", ffmpegLayout, "-i", raw.toString(),
                "-c:a", "libvorbis", "-q:a", "8", "-strict", "-2", "-bitexact", coded.toString()));
        var headLocked = channels == 6;
        var map = headLocked ? new long[]{0, 1, 2, 3, 4, 5} : new long[]{0, 1, 2, 3};
        var file = AmbisonicFixtures.inject(Files.readAllBytes(coded), AmbisonicFixtures.sa3d(1, headLocked, map));
        var layout = headLocked
                ? ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT))
                : ChannelLayout.ofAmbisonics(1);
        AmbisonicFixtures.assertChannels(file, layout, tones);
    }

    @Test
    @DisplayName("the coded order is the Vorbis I order for three to eight channels")
    void codedOrder() {
        assertEquals(List.of(0, 2, 1, 5, 3, 4), codedOrder(6));
        assertEquals(List.of(0, 2, 1), codedOrder(3));
        assertEquals(List.of(0, 1, 2, 3), codedOrder(4));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), codedOrder(9));
    }

    private static List<Integer> codedOrder(int channels) {
        var encoder = new VorbisEncoder(VorbisEncoderOptions.of(RATE, ChannelLayout.defaultFor(channels)));
        var track = AudioTrack.builder().id(1).codec(Codec.VORBIS).timeBase(Rational.timeBaseOf(RATE))
                .sampleRate(RATE).layout(ChannelLayout.defaultFor(channels))
                .codecConfig(encoder.codecConfig().orElseThrow()).build();
        try (var decoder = new VorbisDecoder(track)) {
            return Arrays.stream(decoder.codedChannelOrder()).boxed().toList();
        }
    }
}
