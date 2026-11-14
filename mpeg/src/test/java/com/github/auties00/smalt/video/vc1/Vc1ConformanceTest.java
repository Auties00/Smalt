package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the VC-1 samples of FFmpeg's FATE suite, which include SMPTE VC-1 conformance bitstreams of the advanced
 * profile (SA*, progressive and interlace field pictures), main profile streams in the RCV serialization (SMM*) and a
 * 1080i stream mixing interlace frame and field pictures, plus interlaced streams of FFmpeg's samples archive in AVI
 * and Blu-ray transport stream files, and compares every frame sample for sample with ffmpeg's decode. The streams
 * are also copied by ffmpeg into AVI, Matroska, MP4, MPEG transport stream and ASF files (the Windows Media carriage of
 * the simple and main profile, WMV3, and of the advanced profile, WVC1) and decoded from there, and main profile
 * Windows Media Video 9 files written by Microsoft's encoder (with B and BI frames, overlap smoothing and
 * differential quantization) are decoded from their ASF files, their I and P frames checked against the hashes of
 * Microsoft's own decoder; the files of pre-release encoders are refused.
 *
 * <p>VC-1 specifies its inverse transform, interpolation and filters exactly (SMPTE ST 421 Annex A and sections 8 and
 * 10), so conforming decoders reconstruct the same samples, which is what {@link Tolerance#exact()} checks.
 */
@DisplayName("VC-1 conformance against ffmpeg")
class Vc1ConformanceTest {
    static final Tolerance EXACT = Tolerance.exact();

    // ffmpeg smooths the edges between two intra blocks of the P pictures of the two Windows Media Encoder files
    // with OVERLAP set differently from Microsoft's decoder and Smalt (bloodyback frame 2 at x = 279, psychonauts
    // frame 22 at x = 183, both a1 of an edge, by a few levels), and the difference drifts through the predicted
    // frames up to the next key frame (55 dB at worst in the first 300 frames). Their I and P frames are therefore
    // checked against the hashes of Microsoft's decoder, and every frame against ffmpeg within the drift bound.
    static final Tolerance OVERLAP_P_DRIFT = Tolerance.psnr(50);

    // The SHA-256 of the I and P frames Media Foundation's Windows Media Video 9 decoder outputs
    // (tools/testdata/wmv9-mf/make-hashes.py)
    static final String MEDIA_FOUNDATION_HASHES = "wmv9-mf.tsv";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-fate-SA00040.vc1", "vc1-fate-SA00050.vc1", "vc1-fate-SA10091.vc1",
            "vc1-fate-SA10143.vc1", "vc1-fate-SA20021.vc1", "vc1-fate-SMM0005.rcv", "vc1-fate-SMM0015.rcv",
            "vc1-fate-ilaced_twomv.vc1"})
    @DisplayName("decodes every elementary stream sample as ffmpeg does")
    void elementary(String name) throws IOException {
        var file = Conformance.fetchListed(name);
        var bytes = Files.readAllBytes(file);
        var stream = name.endsWith(".rcv") ? Vc1TestStreams.rcv(bytes) : Vc1TestStreams.elementary(bytes);
        var decoded = Vc1TestStreams.decode(stream);
        assertTrue(decoded.failures().isEmpty(), name + " decodes without errors: " + decoded.failures());
        assertTrue(decoded.count() > 0, name + " produces frames");
        var first = decoded.frames().getFirst();
        compare(name, Oracle.decodeVideo(file, "yuv420p", Integer.MAX_VALUE), decoded.raw(), first.width(),
                first.height(), decoded.count());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-samples-glitch-ffvc1.avi", "vc1-samples-interlaced-bframes.m2ts",
            "vc1-samples-wmv9-bloodyback.wmv", "vc1-samples-wmv9-psychonauts.wmv"})
    @DisplayName("decodes the VC-1 track of a container file as ffmpeg does")
    void container(String name) throws IOException {
        var file = Conformance.fetchListed(name);
        var limit = name.endsWith(".m2ts") ? 40 : name.endsWith(".wmv") ? 300 : Integer.MAX_VALUE;
        var decoded = demuxAndDecode(file, limit);
        if (!name.endsWith(".wmv")) {
            var expected = Oracle.decodeVideo(file, "yuv420p", decoded.count());
            compare(name, expected, decoded.raw(), decoded.width(), decoded.height(), decoded.count());
            return;
        }
        var frameSize = decoded.width() * decoded.height() + 2 * ((decoded.width() + 1) / 2)
                * ((decoded.height() + 1) / 2);
        var hashed = 0;
        for (var row : mediaFoundationHashes(name)) {
            var index = Integer.parseInt(row[1]);
            assertTrue(index < decoded.count(), name + " decodes frame " + index);
            var frame = Arrays.copyOfRange(decoded.raw(), index * frameSize, (index + 1) * frameSize);
            assertEquals(row[3], sha256(frame), name + " " + row[2] + " frame " + index
                    + " as Microsoft's decoder outputs it");
            hashed++;
        }
        assertTrue(hashed > 100, name + " has Media Foundation hashes");
        // ffmpeg rejects the packet after frame 280 of psychonauts as invalid data (Smalt and Microsoft's decoder
        // decode it), so the comparison with ffmpeg stops before it
        var compared = name.endsWith("psychonauts.wmv") ? 280 : decoded.count();
        var expected = Oracle.decodeVideo(file, "yuv420p", compared);
        compare(name, expected, Arrays.copyOf(decoded.raw(), compared * frameSize), decoded.width(),
                decoded.height(), compared, OVERLAP_P_DRIFT);
        var keys = 0;
        for (var index = 0; index < compared; index++) {
            if (decoded.keys().get(index)) {
                keys++;
                var from = index * frameSize;
                EXACT.assertFrames(name + " key frame " + index, Arrays.copyOfRange(expected, from, from + frameSize),
                        Arrays.copyOfRange(decoded.raw(), from, from + frameSize), frameSize);
            }
        }
        assertTrue(keys > 1, name + " has key frames after the first");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-samples-wmv9-simple-sp-ml.wmv", "vc1-samples-wmv9-main-mp-ll.wmv",
            "vc1-samples-wmv9-niceday.wmv", "vc1-samples-wmv9-video-smoothing.wmv"})
    @DisplayName("a stream of a pre-release Windows Media Video 9 encoder (STRUCT_C Reserved6 0) is refused")
    void preRelease(String name) {
        var file = Conformance.fetchListed(name);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(t -> t.codec() == Codec.VC1).findFirst()
                    .orElseThrow();
            var config = track.codecConfig().orElseThrow();
            assertEquals(0, config[3] & 1, "Reserved6 of " + name);
            var failure = assertThrows(MediaException.Unsupported.class, () -> VideoDecoders.create(track));
            assertTrue(failure.getMessage().contains("pre-release"), failure.getMessage());
        }
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({"vc1-fate-SMM0005.rcv, avi", "vc1-fate-SMM0005.rcv, mkv", "vc1-fate-SMM0005.rcv, wmv",
            "vc1-fate-SMM0015.rcv, asf", "vc1-fate-SA10143.vc1, avi", "vc1-fate-SA10143.vc1, mkv",
            "vc1-fate-SA10143.vc1, mp4", "vc1-fate-SA10143.vc1, ts", "vc1-fate-SA10143.vc1, wmv",
            "vc1-fate-SA00050.vc1, asf"})
    @DisplayName("decodes a stream ffmpeg copied into a container as ffmpeg decodes the elementary stream")
    void remuxed(String name, String extension, @TempDir Path directory) throws IOException {
        var file = Conformance.fetchListed(name);
        var remuxed = directory.resolve("remuxed." + extension);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i", file.toString(),
                "-map", "0:v:0", "-c", "copy", remuxed.toString()));
        var decoded = demuxAndDecode(remuxed, Integer.MAX_VALUE);
        compare(name + " in " + extension, Oracle.decodeVideo(file, "yuv420p", Integer.MAX_VALUE), decoded.raw(),
                decoded.width(), decoded.height(), decoded.count());
    }

    @Test
    @DisplayName("a Windows Media Video 9 complex profile stream is refused")
    void complexProfile() {
        var file = Conformance.fetchListed("vc1-samples-wmv9-complex-cp.wmv");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(t -> t.codec() == Codec.VC1).findFirst()
                    .orElseThrow();
            assertThrows(MediaException.Unsupported.class, () -> VideoDecoders.create(track));
        }
    }

    /**
     * The frames a container decode produced, with their size.
     */
    private record ContainerDecode(byte[] raw, int count, int width, int height, List<Boolean> keys) {
    }

    private static ContainerDecode demuxAndDecode(Path file, int limit) {
        var out = new ByteArrayOutputStream();
        var failures = new ArrayList<String>();
        var keys = new ArrayList<Boolean>();
        var state = new int[3];
        Consumer<VideoFrame> sink = frame -> {
            if (state[0] < limit) {
                var picture = (YuvPicture) frame.picture();
                out.writeBytes(Vc1TestStreams.raw(picture));
                keys.add(frame.keyframe());
                state[0]++;
                state[1] = picture.width();
                state[2] = picture.height();
            }
        };
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(t -> t.codec() == Codec.VC1).findFirst()
                    .orElseThrow();
            try (var decoder = VideoDecoders.create(track)) {
                for (var packet = demuxer.read(); packet != null && state[0] < limit; packet = demuxer.read()) {
                    if (packet.trackId() != track.id()) {
                        continue;
                    }
                    try {
                        decoder.decode(packet, sink);
                    } catch (MediaException.Malformed exception) {
                        failures.add(exception.getMessage());
                    }
                }
                decoder.flush(sink);
            }
        }
        assertTrue(failures.isEmpty(), file.getFileName() + " decodes without errors: " + failures);
        assertTrue(state[0] > 0, file.getFileName() + " produces frames");
        return new ContainerDecode(out.toByteArray(), state[0], state[1], state[2], keys);
    }

    private static List<String[]> mediaFoundationHashes(String name) throws IOException {
        try (var input = Vc1ConformanceTest.class.getResourceAsStream(MEDIA_FOUNDATION_HASHES)) {
            assertTrue(input != null, MEDIA_FOUNDATION_HASHES + " is on the test class path");
            var rows = new ArrayList<String[]>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                var row = line.split("\t");
                if (!line.startsWith("#") && row.length == 4 && row[0].equals(name)) {
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void compare(String name, byte[] expected, byte[] actual, int width, int height, int count) {
        compare(name, expected, actual, width, height, count, EXACT);
    }

    private static void compare(String name, byte[] expected, byte[] actual, int width, int height, int count,
                                Tolerance tolerance) {
        var detail = Vc1TestStreams.firstDifference(expected, actual, width, height);
        assertEquals(expected.length, actual.length, name + " decoded size after " + count + " frames; " + detail);
        tolerance.assertFrames(name + ": " + detail, expected, actual,
                width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2));
    }
}
