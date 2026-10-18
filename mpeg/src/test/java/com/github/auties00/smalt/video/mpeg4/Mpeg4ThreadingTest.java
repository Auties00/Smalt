package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that decoding with several threads, which runs pictures concurrently, hands out the same
 * pictures with the same timestamps in the same order as decoding on the calling thread.
 */
@DisplayName("MPEG-4 Part 2 decoder with several threads")
class Mpeg4ThreadingTest {
    record Case(String name, String size, String container, String codec, String options, double seconds) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("P-VOPs", "176x144", "mp4", "mpeg4", "-bf 0", 1.0),
                new Case("B-VOPs", "176x144", "mp4", "mpeg4", "-bf 2", 1.2),
                new Case("quarter sample and four vectors", "176x144", "mp4", "mpeg4", "-bf 2 -flags +qpel+mv4", 1.0),
                new Case("MPEG quantization", "176x144", "mp4", "mpeg4", "-bf 1 -mpeg_quant 1", 1.0),
                new Case("odd size", "202x118", "mp4", "mpeg4", "-bf 2", 0.8),
                new Case("video packets", "352x288", "mp4", "mpeg4", "-bf 0 -ps 400", 0.5),
                new Case("data partitioning", "352x288", "mp4", "mpeg4", "-bf 0 -ps 400 -data_partitioning 1", 0.5),
                new Case("interlaced B-VOPs", "352x288", "mp4", "mpeg4", "-bf 2 -flags +ildct+ilme -alternate_scan 1", 0.6),
                new Case("short key frame interval", "176x144", "mp4", "mpeg4", "-bf 2 -g 5", 1.0),
                new Case("xvid global motion compensation", "176x144", "mp4", "libxvid", "-bf 0 -gmc 1", 1.0),
                new Case("xvid packed B-VOPs in avi", "176x144", "avi", "libxvid", "-bf 2", 1.0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("hands out the same pictures as one thread")
    void sameAsOneThread(Case c) {
        var output = new ArrayList<>(List.of("-t", Double.toString(c.seconds()), "-c:v", c.codec(), "-q:v", "5"));
        output.addAll(List.of(c.options().split(" ")));
        var file = Fixtures.ffmpeg(c.container(), List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=25"),
                output);
        var single = Mpeg4TestStreams.decode(file, track -> new Mpeg4Decoder(track,
                VideoDecoderOptions.builder().threads(1).build()));
        for (var threads : new int[]{2, 4, 8}) {
            var parallel = Mpeg4TestStreams.decode(file, track -> new Mpeg4Decoder(track,
                    VideoDecoderOptions.builder().threads(threads).build()));
            assertEquals(single.frames(), parallel.frames(), c.name() + " frames with " + threads + " threads");
            assertEquals(single.timestamps(), parallel.timestamps(), c.name() + " timestamps with " + threads
                    + " threads");
            assertArrayEquals(single.raw(), parallel.raw(), c.name() + " samples with " + threads + " threads");
        }
    }

    @ParameterizedTest(name = "{0} threads")
    @ValueSource(ints = {2, 8})
    @DisplayName("hands out every picture of a short clip when the flush ends the stream")
    void everyPicture(int threads) {
        var file = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25"),
                List.of("-t", "1", "-c:v", "mpeg4", "-q:v", "5", "-bf", "2"));
        var decoded = Mpeg4TestStreams.decode(file, track -> new Mpeg4Decoder(track,
                VideoDecoderOptions.builder().threads(threads).build()));
        assertEquals(25, decoded.frames());
    }
}
