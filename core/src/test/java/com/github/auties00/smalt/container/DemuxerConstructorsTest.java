package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.aiff.AiffDemuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.ivf.IvfDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.subtitle.SubRipDemuxer;
import com.github.auties00.smalt.container.subtitle.WebVttDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that the {@code (MediaSource)} constructor of every demuxer behaves as the
 * {@code (MediaSource, DemuxerOptions)} constructor with the default options, and that the options
 * constructor behaves as {@link Containers#openDemuxer(MediaSource, DemuxerOptions)}, on a valid file of
 * the format and on bytes that are not one.
 */
@DisplayName("Demuxer constructors")
class DemuxerConstructorsTest {
    /** The two constructors of one demuxer and an input of its format. */
    record Case(String name, Supplier<byte[]> input, BiFunction<MediaSource, DemuxerOptions, Demuxer> withOptions,
                Function<MediaSource, Demuxer> plain) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static byte[] tone(String extension, List<String> options) {
        return Fixtures.read(Fixtures.tone(extension, 440, 48_000, 2, 0.3, options));
    }

    private static byte[] wav() {
        var pcm = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 4800);
        return MuxSupport.toMemory(List.of(pcm.track()), pcm.packets(), WavMuxer::new);
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("ac3", () -> tone("ac3", List.of("-c:a", "ac3")), Ac3Demuxer::new, Ac3Demuxer::new),
                new Case("adts", () -> tone("aac", List.of("-c:a", "aac", "-f", "adts")), AdtsDemuxer::new, AdtsDemuxer::new),
                new Case("aiff", () -> tone("aiff", List.of("-c:a", "pcm_s16be")), AiffDemuxer::new, AiffDemuxer::new),
                new Case("amr", () -> Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 0.3, List.of("-c:a", "libopencore_amrnb"))),
                        AmrDemuxer::new, AmrDemuxer::new),
                new Case("avi", () -> tone("avi", List.of("-c:a", "pcm_s16le")), AviDemuxer::new, AviDemuxer::new),
                new Case("flac", () -> tone("flac", List.of("-c:a", "flac")), FlacDemuxer::new, FlacDemuxer::new),
                new Case("ivf", () -> Fixtures.read(Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=64x48:rate=10:duration=0.3"), List.of("-c:v", "libvpx-vp9", "-deadline", "realtime"))),
                        IvfDemuxer::new, IvfDemuxer::new),
                new Case("matroska", () -> tone("mka", List.of("-c:a", "pcm_s16le")), MatroskaDemuxer::new,
                        MatroskaDemuxer::new),
                new Case("mp4", () -> tone("m4a", List.of("-c:a", "aac")), Mp4Demuxer::new, Mp4Demuxer::new),
                new Case("mp3", () -> tone("mp3", List.of("-c:a", "libmp3lame")), MpegAudioDemuxer::new,
                        MpegAudioDemuxer::new),
                new Case("ogg", () -> tone("opus", List.of("-c:a", "libopus")), OggDemuxer::new, OggDemuxer::new),
                new Case("subrip", () -> "1\n00:00:00,000 --> 00:00:01,000\nHello\n".getBytes(StandardCharsets.UTF_8),
                        SubRipDemuxer::new, SubRipDemuxer::new),
                new Case("webvtt", () -> "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".getBytes(StandardCharsets.UTF_8),
                        WebVttDemuxer::new, WebVttDemuxer::new),
                new Case("ts", () -> tone("ts", List.of("-c:a", "aac")), TsDemuxer::new, TsDemuxer::new),
                new Case("wav", DemuxerConstructorsTest::wav, WavDemuxer::new, WavDemuxer::new));
    }

    /** What a demuxer reports, or the class of the exception it threw. */
    private static String summarize(Supplier<Demuxer> opener) {
        try (var demuxer = opener.get()) {
            var builder = new StringBuilder(demuxer.format().name());
            for (Track track : demuxer.tracks()) {
                builder.append(';').append(track.id()).append(':').append(track.codec()).append(':')
                        .append(track.duration());
            }
            var packets = 0;
            while (demuxer.read() != null) {
                packets++;
            }
            return builder.append(";packets=").append(packets).toString();
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    static Stream<Arguments> inputs() {
        return cases().flatMap(c -> Stream.of(Arguments.of(c, "valid"), Arguments.of(c, "junk")));
    }

    @ParameterizedTest(name = "{0} on {1} input")
    @MethodSource("inputs")
    @DisplayName("agree with each other and with Containers.openDemuxer")
    void agree(Case demuxer, String kind) {
        var bytes = kind.equals("valid") ? demuxer.input().get() : new byte[257];
        var expected = summarize(() -> demuxer.withOptions().apply(MediaSource.of(bytes), DemuxerOptions.defaults()));
        assertEquals(expected, summarize(() -> demuxer.plain().apply(MediaSource.of(bytes))));
        if (kind.equals("valid")) {
            var small = DemuxerOptions.builder().limits(MediaLimits.builder().maxChannels(1).maxBytes(1 << 16).build())
                    .build();
            assertEquals(summarize(() -> Containers.openDemuxer(MediaSource.of(bytes), small)),
                    summarize(() -> demuxer.withOptions().apply(MediaSource.of(bytes), small)));
        }
    }

    @Test
    @DisplayName("enforce the limits of the options")
    void enforceLimits() {
        var bytes = wav();
        var options = DemuxerOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> new WavDemuxer(MediaSource.of(bytes), options));
    }
}
