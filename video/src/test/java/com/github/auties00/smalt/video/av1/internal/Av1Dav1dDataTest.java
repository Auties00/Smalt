package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams of dav1d's test data ({@code dav1d-test-data}: the broad 8, 10 and 12-bit corpora, the syntax
 * features, film grain at odd sizes and in 4:2:2, dav1d's regression streams, switch frames across resolutions and a
 * third-party syntax suite), read from the hosted test data as {@code dav1d-<depth>-<directory>-<file>}, and requires
 * the MD5 of the whole output to equal the one the directory's {@code meson.build} gives to {@code dav1d --verify}.
 *
 * <p>dav1d's MD5 muxer hashes every output picture in order: the luma rows, then the Cb and Cr rows unless the
 * picture is monochrome, each row of its visible width, samples above 8 bits as 16-bit little-endian values. The
 * {@code tests} and {@code tests_obu} lists run with {@code --filmgrain 0}, the {@code fg_tests} list and the plain
 * {@code test()} calls with film grain applied; a {@code --limit} caps the number of output pictures. Every list
 * decodes with dav1d's default of outputting all spatial layers.
 */
@DisplayName("AV1 dav1d test data")
class Av1Dav1dDataTest {
    private static final Pattern ENTRY = Pattern.compile(
            "\\[\\s*'([^']+)'\\s*,\\s*files\\('([^']+)'\\)\\s*,\\s*'([0-9a-f]{32})'\\s*]");

    private static final Pattern TEST_CALL = Pattern.compile(
            "test\\('([^']+)'.*?files\\('([^']+)'\\)(.*?)'--verify',\\s*'([0-9a-f]{32})'", Pattern.DOTALL);

    private static final Pattern LIST = Pattern.compile("(\\w+)\\s*\\+?=\\s*\\[");

    private static final Pattern LIMIT = Pattern.compile("'--limit=(\\d+)'");

    /** One stream with its expected output. */
    record Stream5(String vector, String md5, boolean filmGrain, boolean annexB, int limit) {
        @Override
        public String toString() {
            return vector;
        }
    }

    /** The meson.build entry of one file of a directory. */
    record Entry(String md5, boolean filmGrain, boolean obu, int limit) {
    }

    private static Map<String, Entry> entries(String meson) {
        String text;
        try {
            text = Files.readString(Conformance.vector(meson), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var entries = new HashMap<String, Entry>();
        var matcher = ENTRY.matcher(text);
        while (matcher.find()) {
            var lists = LIST.matcher(text.substring(0, matcher.start()));
            var list = "";
            while (lists.find()) {
                list = lists.group(1);
            }
            entries.put(matcher.group(2), new Entry(matcher.group(3), list.equals("fg_tests"),
                    list.equals("tests_obu"), 0));
        }
        var calls = TEST_CALL.matcher(text);
        while (calls.find()) {
            if (calls.group(3).contains("--decodeframetype")) {
                // a frame type filter of the dav1d CLI, which a decoder does not have: the list entry of the same file
                // gives its full output
                continue;
            }
            var limit = LIMIT.matcher(calls.group(3));
            entries.putIfAbsent(calls.group(2), new Entry(calls.group(4), true, false,
                    limit.find() ? Integer.parseInt(limit.group(1)) : 0));
        }
        return entries;
    }

    static Stream<Stream5> streams() {
        var cache = new HashMap<String, Map<String, Entry>>();
        var streams = new ArrayList<Stream5>();
        for (var name : Conformance.vectors("dav1d-")) {
            if (name.endsWith("meson.build") || name.startsWith("dav1d-oss-fuzz-")) {
                continue;
            }
            // dav1d-<depth>-bit-<directory>-<file>
            var parts = name.split("-", 5);
            var directory = parts[0] + "-" + parts[1] + "-" + parts[2] + "-" + parts[3];
            var file = parts[4];
            var entry = cache.computeIfAbsent(directory + "-meson.build", Av1Dav1dDataTest::entries).get(file);
            if (entry == null) {
                // 309_odd_width.ivf is in dav1d-test-data's issues directory without a line in its meson.build:
                // libaom's decode, through ffmpeg, gives the expected output instead
                streams.add(new Stream5(name, null, true, false, 0));
                continue;
            }
            streams.add(new Stream5(name, entry.md5(), entry.filmGrain(), file.equals("annexb.obu"), entry.limit()));
        }
        // the film grain lists also hash libaom's film grain vectors, hosted under their libaom names
        for (var depth : List.of("8", "10")) {
            var entries = cache.computeIfAbsent("dav1d-" + depth + "-bit-film_grain-meson.build",
                    Av1Dav1dDataTest::entries);
            var file = "av1-1-b" + depth + "-23-film_grain-50.ivf";
            var entry = entries.get(file);
            assertNotNull(entry, file);
            streams.add(new Stream5("av1-" + file, entry.md5(), entry.filmGrain(), false, entry.limit()));
        }
        return streams.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("the MD5 of every output picture equals dav1d's")
    void matchesDav1d(Stream5 stream) {
        var file = Conformance.vector(stream.vector());
        var context = new Av1Context(MediaLimits.DEFAULT);
        context.allLayers(true);
        context.filmGrain(stream.filmGrain());
        context.annexB(stream.annexB());
        var digest = md5();
        var count = new int[1];
        var out = new ArrayList<Av1Picture>();
        var limit = stream.limit() > 0 ? stream.limit() : Integer.MAX_VALUE;
        for (var unit : temporalUnits(file, stream.annexB(), context)) {
            out.clear();
            context.decode(unit, 0, unit.length, 0, 0, out);
            hash(out, digest, count, limit);
        }
        out.clear();
        context.flush(out);
        hash(out, digest, count, limit);
        assertTrue(count[0] > 0, stream.vector() + ": no pictures");
        var expected = stream.md5() != null ? stream.md5() : libaomMd5(file);
        assertEquals(expected, HexFormat.of().formatHex(digest.digest()), stream.vector() + " (" + count[0]
                + " pictures)");
    }

    /** Returns the MD5 of libaom's decode of an 8-bit 4:2:0 stream, through ffmpeg, laid out as dav1d hashes it. */
    private static String libaomMd5(Path file) {
        var raw = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                java.time.Duration.ofMinutes(5), List.of("-v", "error", "-c:v", "libaom-av1", "-i", file.toString(),
                        "-f", "rawvideo", "-pix_fmt", "yuv420p", "-fps_mode", "passthrough", "-"));
        return HexFormat.of().formatHex(md5().digest(raw));
    }

    private static void hash(List<Av1Picture> pictures, MessageDigest digest, int[] count, int limit) {
        for (var picture : pictures) {
            if (count[0] < limit) {
                update(digest, picture);
                count[0]++;
            }
            picture.release();
        }
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Hashes a picture as dav1d's MD5 muxer does. */
    static void update(MessageDigest digest, Av1Picture picture) {
        var hbd = picture.bpc > 8;
        var planes = picture.layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var ssH = picture.layout == SequenceHeader.LAYOUT_I420 || picture.layout == SequenceHeader.LAYOUT_I422 ? 1 : 0;
        var ssV = picture.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var row = new byte[picture.w * 2];
        for (var plane = 0; plane < planes; plane++) {
            var w = plane == 0 ? picture.w : (picture.w + ssH) >> ssH;
            var h = plane == 0 ? picture.h : (picture.h + ssV) >> ssV;
            var stride = picture.stride[plane == 0 ? 0 : 1];
            var data = picture.data[plane];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var v = data[y * stride + x];
                    if (hbd) {
                        row[2 * x] = (byte) v;
                        row[2 * x + 1] = (byte) (v >> 8);
                    } else {
                        row[x] = (byte) v;
                    }
                }
                digest.update(row, 0, hbd ? 2 * w : w);
            }
        }
    }

    /**
     * Returns the temporal units of a stream: the frames of an IVF file, the packets of a container (whose codec
     * configuration goes to the context), the length-delimited units of an Annex B stream, or the units of a
     * low-overhead OBU stream, each starting at a temporal delimiter.
     */
    static List<byte[]> temporalUnits(Path file, boolean annexB, Av1Context context) {
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var units = new ArrayList<byte[]>();
        if (data.length >= 4 && data[0] == 'D' && data[1] == 'K' && data[2] == 'I' && data[3] == 'F') {
            // dav1d's IVF reader skips a 32-byte header whatever its header size field says
            var position = 32;
            while (position + 12 <= data.length) {
                var size = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8
                        | (data[position + 2] & 0xFF) << 16 | (data[position + 3] & 0xFF) << 24;
                position += 12;
                if (size < 0 || size > data.length - position) {
                    break;
                }
                units.add(Arrays.copyOfRange(data, position, position + size));
                position += size;
            }
            return units;
        }
        if (annexB) {
            var position = 0;
            while (position < data.length) {
                var size = leb128(data, position);
                var end = (int) (size[1] + size[0]);
                units.add(Arrays.copyOfRange(data, position, end));
                position = end;
            }
            return units;
        }
        if ((data[0] & 0x80) == 0 && ((data[0] >> 3) & 0xF) == 2) {
            // a low-overhead OBU stream: a new temporal unit at every temporal delimiter
            var start = 0;
            var position = 0;
            while (position < data.length) {
                var header = data[position] & 0xFF;
                var type = (header >> 3) & 0xF;
                if (type == 2 && position > start) {
                    units.add(Arrays.copyOfRange(data, start, position));
                    start = position;
                }
                var size = leb128(data, position + 1 + ((header & 4) != 0 ? 1 : 0));
                position = (int) (size[1] + size[0]);
            }
            units.add(Arrays.copyOfRange(data, start, data.length));
            return units;
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst()
                    .orElseThrow();
            track.codecConfig().ifPresent(context::decodeConfig);
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    units.add(packet.data());
                }
            }
        }
        return units;
    }

    private static long[] leb128(byte[] data, int position) {
        long value = 0;
        for (var i = 0; i < 8; i++) {
            var b = data[position + i] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return new long[]{value, position + i + 1};
            }
        }
        throw new IllegalArgumentException("leb128 longer than 8 bytes at " + position);
    }
}
