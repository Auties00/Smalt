package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every JVT conformance bitstream of {@link H264ConformanceTest} through {@link AnnexBDemuxer} and decodes the
 * packets it returns: the output must equal the reference pictures bit for bit, as when the decoder is fed the
 * stream's NAL units one by one, and the frames must come out with increasing presentation times, which the demuxer
 * derives from the picture order counts.
 */
@DisplayName("H.264 Annex B demuxing of the conformance streams")
class H264AnnexBDemuxTest {
    static Stream<H264ConformanceTest.Vector> vectors() {
        return H264ConformanceTest.vectors().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("splits access units the decoder decodes bit-exactly, timed in presentation order")
    void conformance(H264ConformanceTest.Vector vector) throws IOException {
        var stream = H264ConformanceTest.bitstream(vector);
        var reference = H264ConformanceTest.reference(vector);
        assertEquals(ContainerFormat.H264, Containers.detect(MediaSource.of(stream)).orElseThrow());
        var count = new int[1];
        var times = new ArrayList<Long>();
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var options = H264DecoderOptions.builder().highBitDepth(vector.wide()).build();
            try (var decoder = new H264Decoder(track, options)) {
                java.util.function.Consumer<VideoFrame> sink = frame -> {
                    if (vector.wide()) {
                        H264ConformanceTest.compareWide(vector, (Yuv16Picture) frame.picture(), reference, count[0]);
                    } else {
                        H264ConformanceTest.compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
                    }
                    times.add(frame.pts());
                    count[0]++;
                };
                Packet previous = null;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (previous != null) {
                        assertTrue(packet.dts() >= previous.dts(), "decoding times do not decrease");
                    }
                    assertTrue(packet.pts() == Packet.NO_TIMESTAMP || packet.pts() >= packet.dts(),
                            "no picture is presented before it is decoded");
                    decoder.decode(packet, sink);
                    previous = packet;
                }
                decoder.flush(sink);
            }
        }
        assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
        for (var i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) > times.get(i - 1), vector.name() + ": frame " + i + " at " + times.get(i)
                    + " after " + times.get(i - 1));
        }
    }
}
