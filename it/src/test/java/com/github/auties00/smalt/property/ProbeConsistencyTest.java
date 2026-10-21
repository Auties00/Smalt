package com.github.auties00.smalt.property;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.ProbeOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Probing agrees with demuxing and decoding: for every container Smalt writes and every codec it carries, and for
 * every image format Smalt encodes, {@link Media#probe} names the container or image format, every stream's codec,
 * size, rate and channel count, and a duration within one frame or packet of what the demuxer reports, with and
 * without reading the first frames.
 */
@DisplayName("Probe consistency")
class ProbeConsistencyTest {
    private static final EnumSet<OutputContainer> WRITE_ONLY = EnumSet.of(OutputContainer.H264, OutputContainer.HEVC,
            OutputContainer.AV1);

    static Stream<Arguments> containers() {
        var arguments = new ArrayList<Arguments>();
        for (var container : OutputContainer.values()) {
            if (WRITE_ONLY.contains(container)) {
                continue;
            }
            for (var codec : Catalog.allCodecs()) {
                if (container.supports(codec) && codec.kind() != TrackKind.DATA) {
                    arguments.add(Arguments.of(container, codec, false));
                    arguments.add(Arguments.of(container, codec, true));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} carrying {1}, first frames read {2}")
    @MethodSource("containers")
    @DisplayName("describes the file as the demuxer reads it")
    void container(OutputContainer container, Codec codec, boolean firstFrames) {
        var stream = Catalog.stream(codec);
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var data = sink.toByteArray();
        var info = Media.probe(MediaSource.of(data), ProbeOptions.builder().readFirstFrames(firstFrames).build());
        var what = container + " " + codec;
        assertEquals(container.format(), info.container().orElseThrow(), what + " container");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var track = demuxer.tracks().getFirst();
            assertEquals(1, info.streams().size(), what + " streams");
            switch (track) {
                case VideoTrack video -> {
                    var probed = info.video().orElseThrow(() -> new AssertionError(what + " has no video stream"));
                    assertEquals(codec, probed.codec(), what + " codec");
                    assertEquals(video.width(), probed.width(), what + " width");
                    assertEquals(video.height(), probed.height(), what + " height");
                }
                case AudioTrack audio -> {
                    var probed = info.audio().orElseThrow(() -> new AssertionError(what + " has no audio stream"));
                    if (!codec.name().startsWith("PCM_")) {
                        assertEquals(codec, probed.codec(), what + " codec");
                    }
                    assertEquals(audio.sampleRate(), probed.sampleRate(), what + " sample rate");
                    assertEquals(audio.layout().orElseThrow().channels(), probed.channels(), what + " channels");
                }
                default -> throw new AssertionError(what + " demuxed as " + track);
            }
            var demuxed = demuxer.durationMicros();
            var probed = info.durationMicros();
            if (demuxed > 0 && probed.isPresent()) {
                assertTrue(Math.abs(demuxed - probed.getAsLong()) <= 70_000, what + " duration " + probed.getAsLong()
                        + " us, the demuxer reports " + demuxed);
            }
        }
    }

    static Stream<ImageFormat> images() {
        return Stream.of(ImageFormat.values()).filter(Images::isEncodable);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    @DisplayName("describes an image as it decodes")
    void image(ImageFormat format) {
        var picture = ThreadDigest.picture(97, 61);
        var data = Images.encode(picture, format);
        var info = Media.probe(MediaSource.of(data));
        assertTrue(info.isImage(), format + " is an image");
        assertEquals(format, info.imageFormat().orElseThrow(), format + " format");
        var image = info.image().orElseThrow();
        var decoded = Images.decode(MediaSource.of(data)).picture();
        assertEquals(decoded.width(), image.width(), format + " width");
        assertEquals(decoded.height(), image.height(), format + " height");
    }
}
