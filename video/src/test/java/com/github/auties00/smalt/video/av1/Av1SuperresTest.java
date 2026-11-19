package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.Av1Context;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes AV1 streams that libaom's aomenc coded with super-resolution (fixed, random and quantizer-threshold
 * denominators, with loop restoration, tiles and film grain, at 8, 10 and 12 bits, in 4:2:0, 4:2:2, 4:4:4 and
 * monochrome) and with frame resizing that changes the coded size from frame to frame, and requires every frame to
 * equal libaom's own decode. The streams and the SHA-256 of aomdec's output are kept with the test
 * ({@code superres/superres.tsv}, written by {@code tools/testdata/av1-superres/make-vectors.py}); with aomdec on
 * {@code PATH} the test also decodes each stream with it and compares the bytes.
 */
class Av1SuperresTest {
    @TempDir
    Path directory;

    record Vector(String name, int frames, String streamSha256, String decodedSha256) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            try (var input = Av1SuperresTest.class.getResourceAsStream("superres/" + name + ".ivf")) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + name);
                }
                var data = input.readAllBytes();
                assertEquals(streamSha256, sha256(data), name + " stream");
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static Stream<Vector> vectors() {
        try (var input = Av1SuperresTest.class.getResourceAsStream("superres/superres.tsv")) {
            var vectors = new ArrayList<Vector>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), columns[2], columns[3]));
            }
            return vectors.stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every frame like libaom")
    void decoder(Vector vector) {
        var data = vector.stream();
        var frames = new int[1];
        var decoded = decodeInternal(data, frames);
        assertEquals(vector.frames(), frames[0], vector + " frames");
        Oracle.find("aomdec").ifPresent(aomdec -> assertArrayEquals(aomdec(data), decoded, vector + " against aomdec"));
        assertEquals(vector.decodedSha256(), sha256(decoded), vector + " decoded");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eightBitVectors")
    @DisplayName("decodes every frame like libaom with several frames in flight through the public decoder")
    void frameThreaded(Vector vector) {
        var out = new ByteArrayOutputStream();
        try (var source = MediaSource.of(vector.stream());
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().maxFrameDelay(4).build())) {
                Consumer<VideoFrame> sink = frame -> {
                    var picture = (YuvPicture) frame.picture();
                    write8(out, picture.y(), picture.yStride(), picture.width(), picture.height());
                    write8(out, picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                    write8(out, picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                };
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        }
        assertEquals(vector.decodedSha256(), sha256(out.toByteArray()), vector + " decoded");
    }

    static Stream<Vector> eightBitVectors() {
        return vectors().filter(vector -> !vector.name().matches(".*(10bit|12bit|mono).*"));
    }

    /** Decodes a stream with the internal decoder into the layout of aomdec's raw output. */
    private static byte[] decodeInternal(byte[] data, int[] frames) {
        var out = new ByteArrayOutputStream();
        try (var source = MediaSource.of(data);
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var context = new Av1Context(MediaLimits.DEFAULT);
            track.codecConfig().ifPresent(context::decodeConfig);
            var pictures = new ArrayList<Av1Picture>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                pictures.clear();
                context.decode(packet.data(), 0, packet.data().length, packet.pts(), 0, pictures);
                for (var picture : pictures) {
                    frames[0]++;
                    write(out, picture, 0, picture.w, picture.h);
                    if (picture.data[1] != null) {
                        var subsampledX = picture.layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
                        var subsampledY = picture.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
                        var width = (picture.w + subsampledX) >> subsampledX;
                        var height = (picture.h + subsampledY) >> subsampledY;
                        write(out, picture, 1, width, height);
                        write(out, picture, 2, width, height);
                    }
                    picture.release();
                }
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Av1Picture picture, int plane, int width, int height) {
        var samples = picture.data[plane];
        var stride = picture.stride[plane == 0 ? 0 : 1];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = samples[y * stride + x];
                out.write(value & 0xFF);
                if (picture.bpc > 8) {
                    out.write((value >> 8) & 0xFF);
                }
            }
        }
    }

    private static void write8(ByteArrayOutputStream out, byte[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            out.write(plane, y * stride, width);
        }
    }

    private byte[] aomdec(byte[] data) {
        try {
            var input = Files.createTempFile(directory, "stream-", ".ivf");
            Files.write(input, data);
            var output = Path.of(input + ".yuv");
            Oracle.run("aomdec", Duration.ofMinutes(2), null, List.of("--rawvideo", "-o", output.toString(),
                    input.toString()));
            var decoded = Files.readAllBytes(output);
            assertTrue(decoded.length > 0, "aomdec wrote nothing");
            return decoded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
