package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds the multi-track muxers randomized streams (track counts, packet sizes including empty
 * packets, timestamp gaps up to hours, missing timestamps, packets without duration) and checks
 * that every output is read back by Smalt's demuxer with every packet, in bounded time.
 */
@DisplayName("Muxer fuzzing")
class MuxerFuzzTest {
    private record Stream(List<Track> tracks, List<Packet> packets) {
    }

    private static Stream randomStream(long seed) {
        return randomStream(seed, true);
    }

    private static Stream randomStream(long seed, boolean missingTimestamps) {
        var random = new Random(seed);
        var tracks = new ArrayList<Track>();
        var count = 1 + random.nextInt(6);
        for (var id = 0; id < count; id++) {
            var rate = new int[]{8000, 44_100, 48_000, 96_000}[random.nextInt(4)];
            tracks.add(AudioTrack.builder()
                    .id(id)
                    .codec(Codec.PCM_S16LE)
                    .timeBase(Rational.timeBaseOf(rate))
                    .duration(-1)
                    .sampleRate(rate)
                    .layout(ChannelLayout.MONO)
                    .bitsPerSample(16)
                    .skipSamples(0)
                    .build());
        }
        var times = new long[count];
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 400; index++) {
            var id = random.nextInt(count);
            var frames = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(2000);
            var gap = random.nextInt(50) == 0 ? (long) random.nextInt(3600) * ((AudioTrack) tracks.get(id)).sampleRate() : frames;
            var timestamped = !missingTimestamps || random.nextInt(20) != 0;
            var time = timestamped ? times[id] : Packet.NO_TIMESTAMP;
            packets.add(new Packet(id, new byte[frames * 2], time, time, random.nextBoolean() ? frames : 0, true));
            times[id] += gap;
        }
        return new Stream(tracks, packets);
    }

    private static void check(long seed, Function<MediaSink, Muxer> factory) {
        var stream = randomStream(seed);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (var sink : List.of(true, false)) {
                var output = sink
                        ? MuxSupport.toMemory(stream.tracks(), stream.packets(), factory)
                        : MuxSupport.toStream(stream.tracks(), stream.packets(), factory);
                var result = MuxSupport.demux(output);
                assertEquals(stream.tracks().size(), result.tracks().size(), "seed " + seed);
                var nonEmpty = stream.packets().stream().filter(packet -> packet.size() > 0).count();
                var readBack = result.order().stream().filter(packet -> packet.size() > 0).count();
                assertEquals(nonEmpty, readBack, "seed " + seed);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    @DisplayName("Matroska output of random streams reads back completely")
    void matroska(long seed) {
        check(seed, MatroskaMuxer::new);
        check(seed, sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska().toBuilder().live(true).build()));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    @DisplayName("fragmented MP4 output of random streams reads back completely")
    void fragmented(long seed) {
        // a missing decoding timestamp is extrapolated from the previous duration, which a random gap can
        // contradict, so every packet carries its timestamps here
        var stream = randomStream(seed, false);
        var supported = new ArrayList<Track>();
        for (var track : stream.tracks()) {
            var audio = (AudioTrack) track;
            supported.add(AudioTrack.builder()
                    .id(audio.id())
                    .codec(Codec.MP3)
                    .timeBase(audio.timeBase())
                    .duration(-1)
                    .sampleRate(audio.sampleRate())
                    .layout(audio.layout().orElse(null))
                    .bitsPerSample(0)
                    .skipSamples(0)
                    .build());
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var output = MuxSupport.toMemory(supported, stream.packets(),
                    sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(250_000)
                            .build()));
            var result = MuxSupport.demux(output);
            assertEquals(stream.packets().size(), result.order().size(), "seed " + seed);
        });
    }
}
