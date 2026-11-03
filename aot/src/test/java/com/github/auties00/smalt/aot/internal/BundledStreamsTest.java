package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.exr.ExrDecoder;
import com.github.auties00.smalt.image.jxr.JxrDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h263.H263Decoder;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the reference streams the image and MPEG workloads bundle: each is the pinned copy of a test vector of the
 * module whose decoder it trains (streams.sha256 records which), and decodes through the public decoder; the
 * separately coded H.264 color planes to the per-frame MD5s the mpeg module's chroma444 suite expects.
 */
@DisplayName("bundled training streams")
class BundledStreamsTest {
    // the expected MD5 of every frame's planar 8-bit samples, in display order, from mpeg's chroma444/frames.tsv
    private static final Map<String, List<String>> SEPARATE_PLANE_FRAMES = Map.of(
            "m8-cavlc-sep", List.of("7feed26531f54da633d8b02dd12b861a", "9e9528c2bdee1f2b0828ce2f4f95f4ac",
                    "b4d68c9cb392214261dff70c93d4aef4", "671e5b0ea5994c1543628d23b2c9437c"),
            "m8-cabac-slices-sep", List.of("69412913e496cb09b719bbbeb86b0a21", "e793135d9044424806767ee903f63071",
                    "3376aaa9c629a4ec7eec3ce1174729a9", "d9baf37a9683639598a559955ea884ad",
                    "c1846d9b978c09a2e206f83bfed52482", "f4ceeb7bd4dffd4f4d398340e8dee01a"),
            "m8-cavlc-scaling-sep", List.of("fe8522c1a1e13f7359431f6c419695d2", "a304eeb9679e6db9664230136f2a1f1e",
                    "161433b82c8b4946637420d7d488511f", "e8575e6e000cacc24fff81dfcea505ae"));

    // the picture sizes of each H.263 stream's sub-bitstreams, from mpeg's h263/annex/golden.tsv
    private static final Map<String, Set<String>> H263_SIZES = Map.of(
            "tmn-pb-sac", Set.of("176x144"), "smalt-b", Set.of("176x144"), "tmn-snr", Set.of("176x144"),
            "tmn-spatial-horizontal", Set.of("176x144", "352x144"), "smalt-ist", Set.of("176x144"),
            "smalt-rru", Set.of("176x144"), "smalt-dps-ipb", Set.of("176x144"),
            "smalt-rpr", Set.of("176x144", "128x96"), "smalt-isd-rect", Set.of("176x144"));

    static final List<String> FILES = files();

    private static List<String> files() {
        var files = new ArrayList<String>();
        ImageFormatWorkloads.EXR_FILES.forEach(name -> files.add("exr/" + name + ".exr"));
        ImageFormatWorkloads.JXR_FILES.forEach(name -> files.add("jxr/" + name + ".jxr"));
        MpegStreamWorkloads.H263_STREAMS.forEach(name -> files.add("h263/" + name + ".263"));
        MpegStreamWorkloads.H264_SEPARATE_PLANE_STREAMS.forEach(name -> files.add("h264/" + name + ".264"));
        MpegStreamWorkloads.USAC_STREAMS.forEach(name -> files.add("usac/" + name + ".bin"));
        return List.copyOf(files);
    }

    private static Map<String, String> pins() {
        try (var input = BundledStreamsTest.class.getResourceAsStream("streams.sha256")) {
            assertNotNull(input);
            var pins = new HashMap<String, String>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    pins.put(line.substring(66), line.substring(0, 64));
                }
            }
            return pins;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String hex(String algorithm, byte[]... parts) {
        try {
            var digest = MessageDigest.getInstance(algorithm);
            for (var part : parts) {
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("FILES")
    @DisplayName("every stream a workload reads is the pinned copy of its test vector")
    void pinned(String path) {
        var pins = pins();
        assertTrue(pins.containsKey(path), path + " is not pinned");
        assertEquals(pins.get(path), hex("SHA-256", TrainingStreams.bytes(path)), path);
    }

    @Test
    @DisplayName("every pinned stream is read by a workload")
    void noStrays() {
        assertEquals(Set.copyOf(FILES), pins().keySet());
    }

    @Test
    @DisplayName("the OpenEXR and JPEG XR files decode")
    void images() {
        for (var name : ImageFormatWorkloads.EXR_FILES) {
            var source = TrainingStreams.bytes("exr/" + name + ".exr");
            try (var pages = ExrDecoder.openPages(MediaSource.of(source))) {
                assertTrue(pages.pageCount() >= 1, name);
                for (var page = 0; page < pages.pageCount(); page++) {
                    assertTrue(pages.read(page).picture().width() > 0, name);
                }
            }
        }
        for (var name : ImageFormatWorkloads.JXR_FILES) {
            var picture = JxrDecoder.decode(MediaSource.of(TrainingStreams.bytes("jxr/" + name + ".jxr")),
                    ImageDecoderOptions.defaults()).picture();
            assertEquals(75, picture.width(), name);
            assertEquals(43, picture.height(), name);
        }
    }

    @Test
    @DisplayName("the separately coded H.264 color planes decode to the frames the chroma444 suite expects")
    void separatePlanes() {
        for (var name : MpegStreamWorkloads.H264_SEPARATE_PLANE_STREAMS) {
            var stream = TrainingStreams.demux("h264/" + name + ".264");
            var hashes = new ArrayList<String>();
            try (var decoder = new H264Decoder((VideoTrack) stream.track(), H264DecoderOptions.defaults())) {
                java.util.function.Consumer<VideoFrame> output = frame -> hashes.add(planarMd5(frame));
                for (var packet : stream.packets()) {
                    decoder.decode(packet, output);
                }
                decoder.flush(output);
            }
            assertEquals(SEPARATE_PLANE_FRAMES.get(name), hashes, name);
        }
    }

    private static String planarMd5(VideoFrame frame) {
        var picture = (YuvPicture) frame.picture();
        var width = picture.width();
        var height = picture.height();
        var chromaWidth = picture.chromaFormat().chromaWidth(width);
        var chromaHeight = picture.chromaFormat().chromaHeight(height);
        var y = new byte[width * height];
        var u = new byte[chromaWidth * chromaHeight];
        var v = new byte[chromaWidth * chromaHeight];
        for (var row = 0; row < height; row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), y, row * width, width);
        }
        for (var row = 0; row < chromaHeight; row++) {
            System.arraycopy(picture.u(), row * picture.uvStride(), u, row * chromaWidth, chromaWidth);
            System.arraycopy(picture.v(), row * picture.uvStride(), v, row * chromaWidth, chromaWidth);
        }
        return hex("MD5", y, u, v);
    }

    @Test
    @DisplayName("the H.263 streams of the optional modes decode to pictures of their sub-bitstreams' sizes")
    void h263() {
        for (var name : MpegStreamWorkloads.H263_STREAMS) {
            var stream = TrainingStreams.demux("h263/" + name + ".263");
            var sizes = new ArrayList<String>();
            try (var decoder = new H263Decoder((VideoTrack) stream.track(), VideoDecoderOptions.defaults())) {
                java.util.function.Consumer<VideoFrame> output = frame -> sizes.add(frame.picture().width() + "x"
                        + frame.picture().height());
                for (var packet : stream.packets()) {
                    decoder.decode(packet, output);
                }
                decoder.flush(output);
            }
            assertTrue(sizes.size() >= 5, name + " decodes " + sizes.size() + " pictures");
            assertTrue(H263_SIZES.get(name).containsAll(sizes), name + " decodes pictures of " + Set.copyOf(sizes));
        }
    }

    @Test
    @DisplayName("the USAC streams decode to audio of their channel count, not silence")
    void usac() {
        for (var name : MpegStreamWorkloads.USAC_STREAMS) {
            var stream = MpegStreamWorkloads.usac(name);
            assertFalse(stream.packets().isEmpty(), name);
            var samples = new long[1];
            var peak = new float[1];
            var channels = stream.track().layout().orElseThrow().channels();
            try (var decoder = new AacDecoder(stream.track())) {
                for (var packet : stream.packets()) {
                    decoder.decode(packet, buffer -> {
                        samples[0] += buffer.frames();
                        assertEquals(channels, buffer.channels(), name);
                        for (var channel = 0; channel < buffer.channels(); channel++) {
                            for (var frame = 0; frame < buffer.frames(); frame++) {
                                peak[0] = Math.max(peak[0], Math.abs(buffer.plane(channel)[frame]));
                            }
                        }
                    });
                }
            }
            // each stream codes 0.4 seconds of a 0.3 amplitude tone
            assertTrue(samples[0] >= stream.track().sampleRate() / 4, name + " decodes " + samples[0] + " frames");
            assertTrue(peak[0] > 0.1f, name + " peaks at " + peak[0]);
        }
    }
}
