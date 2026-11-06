package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every JCT-VC conformance bitstream of {@link HevcConformanceTest} through {@link AnnexBDemuxer} and decodes
 * the packets it returns: the output must hash to the reference MD5, as when the decoder is fed the stream's NAL
 * units one by one, every packet with a presentation time must come out as one frame at that time and no other
 * frame may (the pictures an IRAP picture with {@code no_output_of_prior_pics_flag} discards, as in the NoOutPrior
 * streams, have none), and the durations must tile the timeline.
 */
@DisplayName("HEVC Annex B demuxing of the conformance streams")
class HevcAnnexBDemuxTest {
    static Stream<HevcConformanceTest.Vector> vectors() {
        return HevcConformanceTest.vectors().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("splits access units the decoder decodes to the reference MD5, timed in presentation order")
    void conformance(HevcConformanceTest.Vector vector) throws IOException {
        var stream = HevcConformanceTest.bitstream(vector);
        assertEquals(ContainerFormat.HEVC, Containers.detect(MediaSource.of(stream)).orElseThrow());
        var options = HevcDecoderOptions.builder().highBitDepth(true).build();
        var chromaShift = vector.name().equals("TSUNEQBD_A_MAIN10_Technicolor_2") ? 1 : 0;
        var digest = HevcTestStreams.md5();
        var count = new int[1];
        var times = new ArrayList<Long>();
        var packets = new ArrayList<Packet>();
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            try (var decoder = new HevcDecoder(track, options)) {
                Consumer<VideoFrame> sink = frame -> {
                    count[0]++;
                    HevcTestStreams.hash(digest, HevcTestStreams.unshift(frame.picture(), 0, chromaShift));
                    times.add(frame.pts());
                };
                Packet previous = null;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (previous != null) {
                        assertTrue(packet.dts() >= previous.dts(), "decoding times do not decrease");
                    }
                    assertTrue(packet.pts() == Packet.NO_TIMESTAMP || packet.pts() >= packet.dts(),
                            "no picture is presented before it is decoded");
                    decoder.decode(packet, sink);
                    packets.add(packet);
                    previous = packet;
                }
                decoder.flush(sink);
            }
        }
        assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
        assertEquals(vector.md5(), HexFormat.of().formatHex(digest.digest()), "output MD5 of " + vector.name());
        for (var i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) > times.get(i - 1), vector.name() + ": frame " + i + " at " + times.get(i)
                    + " after " + times.get(i - 1));
        }
        var shown = packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP)
                .sorted(Comparator.comparingLong(Packet::pts)).toList();
        assertEquals(shown.stream().map(Packet::pts).toList(), times,
                vector.name() + ": the frames are the packets with a presentation time");
        for (var i = 1; i < shown.size(); i++) {
            assertEquals(shown.get(i - 1).pts() + shown.get(i - 1).duration(), shown.get(i).pts(),
                    vector.name() + ": durations tile the timeline at " + i);
        }
        if (vector.name().startsWith("NoOutPrior_A")) {
            assertTrue(shown.size() < packets.size(), "the IDR picture discards the pictures waiting for output");
        }
    }
}
