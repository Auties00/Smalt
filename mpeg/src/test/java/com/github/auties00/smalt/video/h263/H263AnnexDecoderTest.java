package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import com.github.auties00.smalt.video.h263.internal.H263PlusStream;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams of tools/testdata/h263-annexes through {@link H263Decoder}, which starts every stream in the
 * baseline engine and hands it over to the annex engine at the first picture that needs an optional mode the baseline
 * engine lacks. The frames must be the annex engine's own decode, in display order, with the layer and sub-bitstream
 * of each picture in its side data once the stream has more than one.
 */
@DisplayName("H.263 decoder with optional modes")
class H263AnnexDecoderTest {
    private static final String VECTORS = "/com/github/auties00/smalt/video/h263/annex/";

    record Decoded(byte[] samples, long pts, boolean keyframe, VideoLayer layer) {
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "tmn-sac", "tmn-pb", "tmn-pb-sac", "tmn-ipb", "tmn-ipb-aic", "tmn-b", "tmn-snr", "tmn-spatial-horizontal",
            "tmn-spatial-vertical", "tmn-spatial", "smalt-rpr", "smalt-rru", "smalt-rru-ap", "smalt-rru-df",
            "smalt-isd", "smalt-rect-aso", "smalt-isd-rect", "smalt-rps", "smalt-erps", "smalt-erps-b", "smalt-b",
            "smalt-b-rpr", "smalt-b-rru", "smalt-ipb", "smalt-ipb-df", "smalt-ipb-erps", "smalt-ipb-rru",
            "smalt-cpm", "smalt-dps", "smalt-dps-rru", "smalt-dps-b", "smalt-dps-ipb", "smalt-dps-ipb-rru",
            "smalt-dps-snr", "smalt-ist", "smalt-dps-ist", "smalt-b-ist", "smalt-dps-b-ist", "smalt-ipb-ist",
            "smalt-dps-ipb-ist", "smalt-dps-snr-ist"
    })
    @DisplayName("hands out the annex engine's pictures in display order")
    void matchesAnnexEngine(String name) {
        var data = resource(name + ".263");
        var frames = decode(data);
        var expected = annexEngine(data);
        var byView = new TreeMap<Integer, List<Decoded>>();
        for (var frame : frames) {
            var view = frame.layer() == null ? 0 : frame.layer().viewId();
            byView.computeIfAbsent(view, ignored -> new ArrayList<>()).add(frame);
        }
        assertEquals(expected.keySet(), byView.keySet(), "sub-bitstreams");
        for (var entry : expected.entrySet()) {
            var pictures = entry.getValue();
            var actual = byView.get(entry.getKey());
            assertEquals(pictures.size(), actual.size(), "pictures of sub-bitstream " + entry.getKey());
            for (var i = 0; i < pictures.size(); i++) {
                assertArrayEquals(pictures.get(i), actual.get(i).samples(), "sub-bitstream " + entry.getKey()
                        + ", picture " + i);
                if (i > 0) {
                    assertTrue(actual.get(i).pts() > actual.get(i - 1).pts(), "pts increase at picture " + i);
                }
            }
            assertTrue(actual.getFirst().keyframe(), "first picture is a keyframe");
        }
    }

    @Test
    @DisplayName("tags the pictures of an enhancement layer with its layer number")
    void enhancementLayer() {
        for (var name : List.of("tmn-snr", "tmn-spatial")) {
            var frames = decode(resource(name + ".263"));
            assertFalse(frames.isEmpty(), name);
            for (var frame : frames) {
                assertNotNull(frame.layer(), name + ": layer side data");
                assertEquals(1, frame.layer().id(), name + ": the enhancement layer has ELNUM 2");
                assertEquals(0, frame.layer().viewId(), name + ": one sub-bitstream");
                assertEquals(VideoLayer.Type.PRIMARY, frame.layer().type(), name);
            }
        }
    }

    @Test
    @DisplayName("tags the pictures of Continuous Presence Multipoint with their sub-bitstream")
    void continuousPresence() {
        var frames = decode(resource("smalt-cpm.263"));
        var views = new TreeSet<Integer>();
        for (var frame : frames) {
            assertNotNull(frame.layer(), "layer side data");
            assertEquals(0, frame.layer().id(), "base layer");
            views.add(frame.layer().viewId());
        }
        assertEquals(Set.of(0, 1), views, "sub-bitstreams");
    }

    @Test
    @DisplayName("adds no layer side data to a single layer stream")
    void singleLayer() {
        for (var name : List.of("tmn-pb", "smalt-b", "smalt-rpr", "smalt-erps")) {
            for (var frame : decode(resource(name + ".263"))) {
                assertNull(frame.layer(), name);
            }
        }
    }

    private static List<Decoded> decode(byte[] data) {
        var frames = new ArrayList<Decoded>();
        try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source);
             var decoder = new H263Decoder(stream.track())) {
            Consumer<VideoFrame> sink = frame -> frames.add(new Decoded(
                    H263DifferentialTest.raw((YuvPicture) frame.picture()), frame.pts(), frame.keyframe(),
                    frame.sideData().layer().orElse(null)));
            Packet packet;
            while ((packet = stream.read()) != null) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    private static Map<Integer, List<byte[]>> annexEngine(byte[] data) {
        var stream = new H263PlusStream(MediaLimits.defaults());
        var out = new TreeMap<Integer, List<byte[]>>();
        H263PlusStream.Sink sink = (picture, time, keyframe, layer, subBitstream) -> out.computeIfAbsent(
                subBitstream, ignored -> new ArrayList<>()).add(H263DifferentialTest.raw(picture));
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0xFC) == 0x80) {
                starts.add(i);
            }
        }
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            stream.decode(data, starts.get(k), end - starts.get(k), sink);
        }
        stream.flush(sink);
        return out;
    }

    private static byte[] resource(String name) {
        try (var input = H263AnnexDecoderTest.class.getResourceAsStream(VECTORS + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
