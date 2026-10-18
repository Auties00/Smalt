package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Mutates subtitle files of every container (byte flips, truncations, inserted and deleted runs)
 * and checks that reading them raises nothing but {@link MediaException}.
 */
@DisplayName("Subtitle robustness")
class SubtitleRobustnessTest {
    private static final List<SubtitleCue> CUES = List.of(
            new SubtitleCue(0, 1_000_000, "<i>a</i> b", "x", "align:start"),
            SubtitleCue.of(500_000, 2_000_000, "c\nd"),
            SubtitleCue.of(3_000_000, 4_000_000, "<b>e</b>"));

    static Stream<Arguments> files() {
        var files = new ArrayList<Arguments>();
        for (var format : List.of(ContainerFormat.SUBRIP, ContainerFormat.WEBVTT)) {
            var sink = MediaSink.memory();
            Subtitles.write(CUES, SubtitleMarkup.WEBVTT, format, sink);
            files.add(Arguments.of(format.name(), sink.toByteArray()));
        }
        for (var codec : List.of(Codec.SUBRIP, Codec.ASS, Codec.WEBVTT)) {
            files.add(Arguments.of("matroska " + codec, write(false, codec)));
        }
        for (var codec : List.of(Codec.MOV_TEXT, Codec.WEBVTT)) {
            files.add(Arguments.of("mp4 " + codec, write(true, codec)));
        }
        return files.stream();
    }

    private static byte[] write(boolean mp4, Codec codec) {
        var sink = MediaSink.memory();
        var track = SubtitleEncoder.track(codec, 1);
        var encoder = new SubtitleEncoder(track);
        try (var muxer = mp4 ? new Mp4Muxer(sink) : new MatroskaMuxer(sink)) {
            muxer.addTrack(track);
            for (var cue : CUES) {
                muxer.writePacket(encoder.encode(cue, SubtitleMarkup.WEBVTT));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("damaged files raise only media exceptions")
    void mutations(String name, byte[] file) {
        var random = new Random(name.hashCode());
        for (var round = 0; round < 300; round++) {
            var damaged = mutate(file, random);
            try {
                Subtitles.read(MediaSource.of(damaged), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
            } catch (MediaException expected) {
                // the only allowed failure
            } catch (RuntimeException | StackOverflowError unexpected) {
                fail(name + " round " + round + ": " + unexpected, unexpected);
            }
        }
    }

    private static byte[] mutate(byte[] file, Random random) {
        var data = file.clone();
        switch (random.nextInt(4)) {
            case 0 -> {
                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
            }
            case 1 -> data = java.util.Arrays.copyOf(data, random.nextInt(data.length));
            case 2 -> {
                var at = random.nextInt(data.length);
                var length = Math.min(data.length - at, 1 + random.nextInt(32));
                var shorter = new byte[data.length - length];
                System.arraycopy(data, 0, shorter, 0, at);
                System.arraycopy(data, at + length, shorter, at, data.length - at - length);
                data = shorter;
            }
            default -> {
                var at = random.nextInt(data.length);
                var insert = new byte[1 + random.nextInt(16)];
                random.nextBytes(insert);
                var longer = new byte[data.length + insert.length];
                System.arraycopy(data, 0, longer, 0, at);
                System.arraycopy(insert, 0, longer, at, insert.length);
                System.arraycopy(data, at, longer, at + insert.length, data.length - at);
                data = longer;
            }
        }
        return data;
    }
}
