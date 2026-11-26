package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the libaom AV1 test vectors ({@code av1-1-b8-*} and {@code av1-1-b10-*}: every quantizer,
 * the frame size sweep, all-intra, CDF update, motion vector extremes, motion field projection,
 * intra block copy, scalability, film grain and monochrome) and requires the MD5 of every frame to
 * match libaom's reference. High bit depth vectors are hashed on the decoder's 16-bit planes
 * (little-endian samples), as libaom hashes them; monochrome frames are hashed with mid-gray 4:2:0
 * chroma planes, as libaom writes them. Every vector and reference is read from the hosted test data
 * by {@link Conformance#vector}.
 */
@DisplayName("AV1 conformance vectors (libaom test vectors)")
class Av1ConformanceTest {
    /**
     * The vector names; each is read as {@code av1-<name>}, with its reference as {@code av1-<name>.md5}.
     */
    private static final String MANIFEST = """
            av1-1-b8-00-quantizer-00.ivf
            av1-1-b8-00-quantizer-01.ivf
            av1-1-b8-00-quantizer-02.ivf
            av1-1-b8-00-quantizer-03.ivf
            av1-1-b8-00-quantizer-04.ivf
            av1-1-b8-00-quantizer-05.ivf
            av1-1-b8-00-quantizer-06.ivf
            av1-1-b8-00-quantizer-07.ivf
            av1-1-b8-00-quantizer-08.ivf
            av1-1-b8-00-quantizer-09.ivf
            av1-1-b8-00-quantizer-10.ivf
            av1-1-b8-00-quantizer-11.ivf
            av1-1-b8-00-quantizer-12.ivf
            av1-1-b8-00-quantizer-13.ivf
            av1-1-b8-00-quantizer-14.ivf
            av1-1-b8-00-quantizer-15.ivf
            av1-1-b8-00-quantizer-16.ivf
            av1-1-b8-00-quantizer-17.ivf
            av1-1-b8-00-quantizer-18.ivf
            av1-1-b8-00-quantizer-19.ivf
            av1-1-b8-00-quantizer-20.ivf
            av1-1-b8-00-quantizer-21.ivf
            av1-1-b8-00-quantizer-22.ivf
            av1-1-b8-00-quantizer-23.ivf
            av1-1-b8-00-quantizer-24.ivf
            av1-1-b8-00-quantizer-25.ivf
            av1-1-b8-00-quantizer-26.ivf
            av1-1-b8-00-quantizer-27.ivf
            av1-1-b8-00-quantizer-28.ivf
            av1-1-b8-00-quantizer-29.ivf
            av1-1-b8-00-quantizer-30.ivf
            av1-1-b8-00-quantizer-31.ivf
            av1-1-b8-00-quantizer-32.ivf
            av1-1-b8-00-quantizer-33.ivf
            av1-1-b8-00-quantizer-34.ivf
            av1-1-b8-00-quantizer-35.ivf
            av1-1-b8-00-quantizer-36.ivf
            av1-1-b8-00-quantizer-37.ivf
            av1-1-b8-00-quantizer-38.ivf
            av1-1-b8-00-quantizer-39.ivf
            av1-1-b8-00-quantizer-40.ivf
            av1-1-b8-00-quantizer-41.ivf
            av1-1-b8-00-quantizer-42.ivf
            av1-1-b8-00-quantizer-43.ivf
            av1-1-b8-00-quantizer-44.ivf
            av1-1-b8-00-quantizer-45.ivf
            av1-1-b8-00-quantizer-46.ivf
            av1-1-b8-00-quantizer-47.ivf
            av1-1-b8-00-quantizer-48.ivf
            av1-1-b8-00-quantizer-49.ivf
            av1-1-b8-00-quantizer-50.ivf
            av1-1-b8-00-quantizer-51.ivf
            av1-1-b8-00-quantizer-52.ivf
            av1-1-b8-00-quantizer-53.ivf
            av1-1-b8-00-quantizer-54.ivf
            av1-1-b8-00-quantizer-55.ivf
            av1-1-b8-00-quantizer-56.ivf
            av1-1-b8-00-quantizer-57.ivf
            av1-1-b8-00-quantizer-58.ivf
            av1-1-b8-00-quantizer-59.ivf
            av1-1-b8-00-quantizer-60.ivf
            av1-1-b8-00-quantizer-61.ivf
            av1-1-b8-00-quantizer-62.ivf
            av1-1-b8-00-quantizer-63.ivf
            av1-1-b10-00-quantizer-00.ivf
            av1-1-b10-00-quantizer-01.ivf
            av1-1-b10-00-quantizer-02.ivf
            av1-1-b10-00-quantizer-03.ivf
            av1-1-b10-00-quantizer-04.ivf
            av1-1-b10-00-quantizer-05.ivf
            av1-1-b10-00-quantizer-06.ivf
            av1-1-b10-00-quantizer-07.ivf
            av1-1-b10-00-quantizer-08.ivf
            av1-1-b10-00-quantizer-09.ivf
            av1-1-b10-00-quantizer-10.ivf
            av1-1-b10-00-quantizer-11.ivf
            av1-1-b10-00-quantizer-12.ivf
            av1-1-b10-00-quantizer-13.ivf
            av1-1-b10-00-quantizer-14.ivf
            av1-1-b10-00-quantizer-15.ivf
            av1-1-b10-00-quantizer-16.ivf
            av1-1-b10-00-quantizer-17.ivf
            av1-1-b10-00-quantizer-18.ivf
            av1-1-b10-00-quantizer-19.ivf
            av1-1-b10-00-quantizer-20.ivf
            av1-1-b10-00-quantizer-21.ivf
            av1-1-b10-00-quantizer-22.ivf
            av1-1-b10-00-quantizer-23.ivf
            av1-1-b10-00-quantizer-24.ivf
            av1-1-b10-00-quantizer-25.ivf
            av1-1-b10-00-quantizer-26.ivf
            av1-1-b10-00-quantizer-27.ivf
            av1-1-b10-00-quantizer-28.ivf
            av1-1-b10-00-quantizer-29.ivf
            av1-1-b10-00-quantizer-30.ivf
            av1-1-b10-00-quantizer-31.ivf
            av1-1-b10-00-quantizer-32.ivf
            av1-1-b10-00-quantizer-33.ivf
            av1-1-b10-00-quantizer-34.ivf
            av1-1-b10-00-quantizer-35.ivf
            av1-1-b10-00-quantizer-36.ivf
            av1-1-b10-00-quantizer-37.ivf
            av1-1-b10-00-quantizer-38.ivf
            av1-1-b10-00-quantizer-39.ivf
            av1-1-b10-00-quantizer-40.ivf
            av1-1-b10-00-quantizer-41.ivf
            av1-1-b10-00-quantizer-42.ivf
            av1-1-b10-00-quantizer-43.ivf
            av1-1-b10-00-quantizer-44.ivf
            av1-1-b10-00-quantizer-45.ivf
            av1-1-b10-00-quantizer-46.ivf
            av1-1-b10-00-quantizer-47.ivf
            av1-1-b10-00-quantizer-48.ivf
            av1-1-b10-00-quantizer-49.ivf
            av1-1-b10-00-quantizer-50.ivf
            av1-1-b10-00-quantizer-51.ivf
            av1-1-b10-00-quantizer-52.ivf
            av1-1-b10-00-quantizer-53.ivf
            av1-1-b10-00-quantizer-54.ivf
            av1-1-b10-00-quantizer-55.ivf
            av1-1-b10-00-quantizer-56.ivf
            av1-1-b10-00-quantizer-57.ivf
            av1-1-b10-00-quantizer-58.ivf
            av1-1-b10-00-quantizer-59.ivf
            av1-1-b10-00-quantizer-60.ivf
            av1-1-b10-00-quantizer-61.ivf
            av1-1-b10-00-quantizer-62.ivf
            av1-1-b10-00-quantizer-63.ivf
            av1-1-b10-23-film_grain-50.ivf
            av1-1-b10-24-monochrome.ivf
            av1-1-b8-01-size-16x16.ivf
            av1-1-b8-01-size-16x18.ivf
            av1-1-b8-01-size-16x32.ivf
            av1-1-b8-01-size-16x34.ivf
            av1-1-b8-01-size-16x64.ivf
            av1-1-b8-01-size-16x66.ivf
            av1-1-b8-01-size-18x16.ivf
            av1-1-b8-01-size-18x18.ivf
            av1-1-b8-01-size-18x32.ivf
            av1-1-b8-01-size-18x34.ivf
            av1-1-b8-01-size-18x64.ivf
            av1-1-b8-01-size-18x66.ivf
            av1-1-b8-01-size-196x196.ivf
            av1-1-b8-01-size-196x198.ivf
            av1-1-b8-01-size-196x200.ivf
            av1-1-b8-01-size-196x202.ivf
            av1-1-b8-01-size-196x208.ivf
            av1-1-b8-01-size-196x210.ivf
            av1-1-b8-01-size-196x224.ivf
            av1-1-b8-01-size-196x226.ivf
            av1-1-b8-01-size-198x196.ivf
            av1-1-b8-01-size-198x198.ivf
            av1-1-b8-01-size-198x200.ivf
            av1-1-b8-01-size-198x202.ivf
            av1-1-b8-01-size-198x208.ivf
            av1-1-b8-01-size-198x210.ivf
            av1-1-b8-01-size-198x224.ivf
            av1-1-b8-01-size-198x226.ivf
            av1-1-b8-01-size-200x196.ivf
            av1-1-b8-01-size-200x198.ivf
            av1-1-b8-01-size-200x200.ivf
            av1-1-b8-01-size-200x202.ivf
            av1-1-b8-01-size-200x208.ivf
            av1-1-b8-01-size-200x210.ivf
            av1-1-b8-01-size-200x224.ivf
            av1-1-b8-01-size-200x226.ivf
            av1-1-b8-01-size-202x196.ivf
            av1-1-b8-01-size-202x198.ivf
            av1-1-b8-01-size-202x200.ivf
            av1-1-b8-01-size-202x202.ivf
            av1-1-b8-01-size-202x208.ivf
            av1-1-b8-01-size-202x210.ivf
            av1-1-b8-01-size-202x224.ivf
            av1-1-b8-01-size-202x226.ivf
            av1-1-b8-01-size-208x196.ivf
            av1-1-b8-01-size-208x198.ivf
            av1-1-b8-01-size-208x200.ivf
            av1-1-b8-01-size-208x202.ivf
            av1-1-b8-01-size-208x208.ivf
            av1-1-b8-01-size-208x210.ivf
            av1-1-b8-01-size-208x224.ivf
            av1-1-b8-01-size-208x226.ivf
            av1-1-b8-01-size-210x196.ivf
            av1-1-b8-01-size-210x198.ivf
            av1-1-b8-01-size-210x200.ivf
            av1-1-b8-01-size-210x202.ivf
            av1-1-b8-01-size-210x208.ivf
            av1-1-b8-01-size-210x210.ivf
            av1-1-b8-01-size-210x224.ivf
            av1-1-b8-01-size-210x226.ivf
            av1-1-b8-01-size-224x196.ivf
            av1-1-b8-01-size-224x198.ivf
            av1-1-b8-01-size-224x200.ivf
            av1-1-b8-01-size-224x202.ivf
            av1-1-b8-01-size-224x208.ivf
            av1-1-b8-01-size-224x210.ivf
            av1-1-b8-01-size-224x224.ivf
            av1-1-b8-01-size-224x226.ivf
            av1-1-b8-01-size-226x196.ivf
            av1-1-b8-01-size-226x198.ivf
            av1-1-b8-01-size-226x200.ivf
            av1-1-b8-01-size-226x202.ivf
            av1-1-b8-01-size-226x208.ivf
            av1-1-b8-01-size-226x210.ivf
            av1-1-b8-01-size-226x224.ivf
            av1-1-b8-01-size-226x226.ivf
            av1-1-b8-01-size-32x16.ivf
            av1-1-b8-01-size-32x18.ivf
            av1-1-b8-01-size-32x32.ivf
            av1-1-b8-01-size-32x34.ivf
            av1-1-b8-01-size-32x64.ivf
            av1-1-b8-01-size-32x66.ivf
            av1-1-b8-01-size-34x16.ivf
            av1-1-b8-01-size-34x18.ivf
            av1-1-b8-01-size-34x32.ivf
            av1-1-b8-01-size-34x34.ivf
            av1-1-b8-01-size-34x64.ivf
            av1-1-b8-01-size-34x66.ivf
            av1-1-b8-01-size-64x16.ivf
            av1-1-b8-01-size-64x18.ivf
            av1-1-b8-01-size-64x32.ivf
            av1-1-b8-01-size-64x34.ivf
            av1-1-b8-01-size-64x64.ivf
            av1-1-b8-01-size-64x66.ivf
            av1-1-b8-01-size-66x16.ivf
            av1-1-b8-01-size-66x18.ivf
            av1-1-b8-01-size-66x32.ivf
            av1-1-b8-01-size-66x34.ivf
            av1-1-b8-01-size-66x64.ivf
            av1-1-b8-01-size-66x66.ivf
            av1-1-b8-02-allintra.ivf
            av1-1-b8-03-sizedown.mkv
            av1-1-b8-03-sizeup.mkv
            av1-1-b8-04-cdfupdate.ivf
            av1-1-b8-05-mv.ivf
            av1-1-b8-06-mfmv.ivf
            av1-1-b8-16-intra_only-intrabc-extreme-dv.ivf
            av1-1-b8-22-svc-L1T2.ivf
            av1-1-b8-22-svc-L2T1-2.ivf
            av1-1-b8-22-svc-L2T2-2.ivf
            av1-1-b8-23-film_grain-50.ivf
            av1-1-b8-24-monochrome.ivf
            """;

    record Vector(String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> vectors() {
        return MANIFEST.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(Vector::new);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every frame matches the reference MD5 on the calling thread")
    void vector(Vector vector) throws IOException {
        check(vector, Av1Context.Threading.OFF);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every frame matches the reference MD5 through the threaded pipeline")
    void threaded(Vector vector) throws IOException {
        check(vector, Av1Context.Threading.FORCED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every frame matches the reference MD5 with frames decoded concurrently")
    void frameThreaded(Vector vector) throws IOException {
        check(vector, Av1Context.Threading.AUTO, 4);
    }

    private static void check(Vector vector, Av1Context.Threading threading) throws IOException {
        check(vector, threading, 1);
    }

    private static void check(Vector vector, Av1Context.Threading threading, int frameDelay) throws IOException {
        var file = Conformance.vector("av1-" + vector.name());
        var md5File = Conformance.vector("av1-" + vector.name() + ".md5");
        var expected = new ArrayList<String>();
        for (var line : Files.readAllLines(md5File)) {
            if (!line.isBlank()) {
                expected.add(line.strip().split("[ \t]+")[0]);
            }
        }
        var actual = vector.name().endsWith(".mkv") ? decodeMatroska(file, threading, frameDelay)
                : decodeIvf(file, threading, frameDelay);
        assertEquals(expected.size(), actual.size(), "frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + i);
        }
    }

    private static List<String> decodeIvf(Path file, Av1Context.Threading threading, int frameDelay)
            throws IOException {
        var data = Files.readAllBytes(file);
        var context = new Av1Context(MediaLimits.DEFAULT);
        context.threading(threading);
        context.frameDelay(frameDelay);
        var pos = (data[6] & 0xFF) | (data[7] & 0xFF) << 8;
        var hashes = new ArrayList<String>();
        var out = new ArrayList<Av1Picture>();
        while (pos + 12 <= data.length) {
            var size = (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16
                    | (data[pos + 3] & 0xFF) << 24;
            pos += 12;
            out.clear();
            context.decode(data, pos, size, 0, 0, out);
            for (var picture : out) {
                hashes.add(md5(picture));
                picture.release();
            }
            pos += size;
        }
        out.clear();
        context.flush(out);
        for (var picture : out) {
            hashes.add(md5(picture));
            picture.release();
        }
        return hashes;
    }

    private static List<String> decodeMatroska(Path file, Av1Context.Threading threading, int frameDelay)
            throws IOException {
        var hashes = new ArrayList<String>();
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var demuxer = new MatroskaDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var context = new Av1Context(MediaLimits.DEFAULT);
            context.threading(threading);
            context.frameDelay(frameDelay);
            track.codecConfig().ifPresent(context::decodeConfig);
            var out = new ArrayList<Av1Picture>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                out.clear();
                context.decode(packet.data(), 0, packet.data().length, packet.pts(), packet.duration(), out);
                for (var picture : out) {
                    hashes.add(md5(picture));
                    picture.release();
                }
            }
            out.clear();
            context.flush(out);
            for (var picture : out) {
                hashes.add(md5(picture));
                picture.release();
            }
        }
        return hashes;
    }

    static String md5(Av1Picture picture) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var hbd = picture.bpc > 8;
        var ssH = picture.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var ssV = picture.layout == SequenceHeader.LAYOUT_I444 || picture.layout == SequenceHeader.LAYOUT_I422 ? 0 : 1;
        var row = new byte[picture.w * 2];
        for (var pl = 0; pl < 3; pl++) {
            var w = pl == 0 ? picture.w : (picture.w + ssH) >> ssH;
            var h = pl == 0 ? picture.h : (picture.h + ssV) >> ssV;
            var stride = picture.stride[pl == 0 ? 0 : 1];
            var plane = picture.data[pl];
            var gray = 1 << (picture.bpc - 1);
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var v = plane == null ? gray : plane[y * stride + x];
                    if (hbd) {
                        row[2 * x] = (byte) v;
                        row[2 * x + 1] = (byte) (v >> 8);
                    } else {
                        row[x] = (byte) v;
                    }
                }
                md.update(row, 0, hbd ? 2 * w : w);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
