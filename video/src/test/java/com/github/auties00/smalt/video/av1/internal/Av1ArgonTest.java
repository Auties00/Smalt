package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the Argon AV1 coverage streams (AOMedia's Argon Streams AV1, base and extended profiles, version 2.1.1),
 * which exercise every syntax element and decoding process of the specification at its limits, and requires the MD5
 * of the decoded output to match the reference: the frames of every layer of operating point 0, in output order, as
 * libaom's {@code aomdec --rawvideo --all-layers} writes them (8-bit samples for 8-bit streams, 16-bit little-endian
 * ones otherwise, luma only for monochrome streams), with film grain and, where the stream carries grain, without it;
 * and the output of every other operating point the sequence header declares. The streams of the {@code error}
 * groups must decode or fail with a {@link MediaException}, never another exception. Streams in the length-delimited
 * format of Annex B are decoded as such, one temporal unit per call.
 *
 * <p>The large scale tile groups are decoded as libaom's {@code examples/lightfield_tile_list_decoder} decodes them,
 * with the arguments of their reference command: the frame each of the first temporal units shows (without film
 * grain) becomes an anchor frame, the next temporal unit is the camera frame header, and the tiles of the tile lists
 * that follow, each tile's luma rows then its Cb and Cr rows, must have the reference MD5.
 *
 * <p>The suite is several gigabytes (licence unstated: it is downloaded, never redistributed), so it runs only when
 * asked for: the tests carry the {@code argon} tag, which the build excludes unless {@code smalt.test.excludedGroups}
 * is overridden, and {@code smalt.test.argon} names the groups to decode (comma-separated, every supported group by
 * default). Each group is an archive set of {@code tools/testdata/archives.tsv}, fetched by range requests from the
 * suite's ZIP archive.
 */
@Tag("argon")
@DisplayName("Argon AV1 coverage streams")
class Av1ArgonTest {
    private static final String ROOT = "argon_coveragetool_av1_base_and_extended_profiles_v2.1/";

    /** The groups this suite decodes, in the order of the archive. */
    private static final List<String> GROUPS = List.of(
            "profile0_core", "profile0_core_special", "profile0_not_annexb", "profile0_not_annexb_special",
            "profile0_error", "profile0_stress",
            "profile1_core", "profile1_core_special", "profile1_not_annexb", "profile1_not_annexb_special",
            "profile1_error", "profile1_stress",
            "profile2_core", "profile2_core_special", "profile2_not_annexb", "profile2_not_annexb_special",
            "profile2_error", "profile2_stress",
            "profile_switching",
            "profile0_large_scale_tile", "profile0_large_scale_tile_special",
            "profile1_large_scale_tile", "profile1_large_scale_tile_special",
            "profile2_large_scale_tile", "profile2_large_scale_tile_special");

    private static final Pattern TILE_LIST_COMMAND = Pattern.compile(
            "lightfield_tile_list_decoder\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d+)");

    private static final Pattern STREAM = Pattern.compile("/streams/([^/]+)\\.obu$");

    /** A stream may take this long to decode, every pass included. */
    private static final long TIMEOUT_MINUTES = 30;

    /**
     * The resource limits of the decodes: some streams code frames 16640 samples wide, and AV1 allows 65536, beyond
     * the default limits.
     */
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT.toBuilder()
            .maxDimension(65_536)
            .maxPixels(1L << 30)
            .build();

    /** The outcome of decoding one stream: the problems found, empty when it conforms. */
    private record Verdict(List<String> problems) {
    }

    @TestFactory
    @DisplayName("decode to the reference MD5")
    Stream<DynamicTest> streams() {
        var requested = System.getProperty("smalt.test.argon", "");
        var groups = requested.isBlank() ? GROUPS : Arrays.stream(requested.split(",")).map(String::strip)
                .filter(name -> !name.isEmpty()).toList();
        for (var group : groups) {
            if (!GROUPS.contains(group)) {
                throw new IllegalArgumentException("unknown or unsupported Argon group " + group);
            }
        }
        // each decode of a large stream holds up to a gigabyte of reference pictures
        var parallelism = Integer.getInteger("smalt.test.argon.threads",
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(),
                        (int) (Runtime.getRuntime().maxMemory() / (3L << 30)))));
        var executor = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().daemon().name("argon-", 0).factory());
        var serial = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("argon-serial").factory());
        var tests = new ArrayList<DynamicTest>();
        for (var group : groups) {
            var archive = Conformance.archive("argon-av1-" + group);
            var members = archive.members();
            var streams = new ArrayList<String>();
            for (var member : members) {
                if (STREAM.matcher(member).find()) {
                    streams.add(member);
                }
            }
            var memberSet = new java.util.HashSet<>(members);
            for (var stream : streams) {
                var name = stream.substring(stream.lastIndexOf('/') + 1, stream.length() - 4);
                // the anchor frames of a large scale tile special stream can fill most of the heap on their own
                var pool = group.contains("large_scale_tile_special") ? serial : executor;
                Future<Verdict> verdict = pool.submit(() -> check(archive, memberSet, group, name));
                tests.add(DynamicTest.dynamicTest(group + "/" + name, () -> {
                    try {
                        var problems = verdict.get(TIMEOUT_MINUTES, TimeUnit.MINUTES).problems();
                        if (!problems.isEmpty()) {
                            fail(String.join("; ", problems));
                        }
                    } catch (TimeoutException exception) {
                        verdict.cancel(true);
                        fail("no verdict in " + TIMEOUT_MINUTES + " minutes");
                    } catch (ExecutionException exception) {
                        if (exception.getCause() instanceof Error error) {
                            throw error;
                        }
                        throw exception.getCause();
                    }
                }));
            }
        }
        executor.shutdown();
        serial.shutdown();
        return tests.stream().onClose(() -> {
            executor.shutdownNow();
            serial.shutdownNow();
        });
    }

    private static Verdict check(Conformance.Archive archive, java.util.Set<String> members, String group, String name)
            throws IOException {
        var base = ROOT + group + "/";
        var stream = Files.readAllBytes(archive.fetch(base + "streams/" + name + ".obu"));
        var command = new String(Files.readAllBytes(archive.fetch(base + "ref_cmd/" + name + ".sh")),
                StandardCharsets.UTF_8);
        var threading = threading(name);
        if (group.contains("large_scale_tile")) {
            return checkTileLists(stream, command, md5(archive.fetch(base + "md5_ref/" + name + ".md5")), threading);
        }
        var annexB = command.contains("--annexb");
        var units = annexB ? annexBUnits(stream) : sectionFiveUnits(stream);
        if (group.endsWith("_error")) {
            try {
                decode(units, annexB, 0, true, threading);
            } catch (MediaException expected) {
                // an error stream may be rejected, as long as it is rejected as media
            }
            return new Verdict(List.of());
        }
        var problems = new ArrayList<String>();
        var reference = md5(archive.fetch(base + "md5_ref/" + name + ".md5"));
        var decoded = decode(units, annexB, 0, true, threading);
        if (!reference.equals(decoded.md5())) {
            problems.add("operating point 0: MD5 " + decoded.md5() + ", reference " + reference);
        }
        var plain = base + "md5_no_film_grain/" + name + ".md5";
        if (members.contains(plain)) {
            var withoutGrain = md5(archive.fetch(plain));
            if (!withoutGrain.equals(reference)) {
                var ungrained = decode(units, annexB, 0, false, threading).md5();
                if (!withoutGrain.equals(ungrained)) {
                    problems.add("without film grain: MD5 " + ungrained + ", reference " + withoutGrain);
                }
            }
        }
        for (var point = 1; point < 32; point++) {
            var layer = base + "md5_ref/layers/" + point + "/" + name + "_layer" + point + ".md5";
            if (!members.contains(layer)) {
                continue;
            }
            var expected = md5(archive.fetch(layer));
            if (point >= decoded.operatingPoints()) {
                // an undeclared operating point selects operating point 0
                if (!expected.equals(reference)) {
                    problems.add("undeclared operating point " + point + " has its own reference " + expected);
                }
                continue;
            }
            var actual = decode(units, annexB, point, true, threading).md5();
            if (!expected.equals(actual)) {
                problems.add("operating point " + point + ": MD5 " + actual + ", reference " + expected);
            }
        }
        return new Verdict(problems);
    }

    /** Spreads the streams over the threading modes, so that each mode decodes a share of every group. */
    private static Av1Context.Threading threading(String name) {
        return switch (Math.floorMod(name.hashCode(), 4)) {
            case 0 -> Av1Context.Threading.FORCED;
            default -> Av1Context.Threading.OFF;
        };
    }

    private static String md5(java.nio.file.Path file) throws IOException {
        var text = Files.readString(file, StandardCharsets.US_ASCII).strip();
        var hash = text.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("not an MD5 reference: " + file);
        }
        return hash;
    }

    /** The output of a decode: the MD5 of every frame and the number of operating points of the stream. */
    private record Decoded(String md5, int operatingPoints) {
    }

    private static Decoded decode(List<byte[]> units, boolean annexB, int operatingPoint, boolean filmGrain,
                                  Av1Context.Threading threading) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var context = new Av1Context(LIMITS);
        context.threading(threading);
        context.annexB(annexB);
        context.operatingPoint(operatingPoint);
        context.allLayers(true);
        context.filmGrain(filmGrain);
        var out = new ArrayList<Av1Picture>();
        var operatingPoints = 1;
        for (var unit : units) {
            out.clear();
            context.decode(unit, 0, unit.length, 0, 0, out);
            operatingPoints = write(out, digest, operatingPoints);
        }
        out.clear();
        context.flush(out);
        operatingPoints = write(out, digest, operatingPoints);
        return new Decoded(HexFormat.of().formatHex(digest.digest()), operatingPoints);
    }

    /** Hashes the pictures as aomdec's raw output, releasing them; returns the operating point count seen. */
    private static int write(List<Av1Picture> pictures, MessageDigest digest, int operatingPoints) {
        var count = operatingPoints;
        try {
            for (var picture : pictures) {
                if (picture.seqHdr != null) {
                    count = Math.max(count, picture.seqHdr.numOperatingPoints);
                }
                hash(picture, digest);
            }
        } finally {
            for (var picture : pictures) {
                picture.release();
            }
        }
        return count;
    }

    private static void hash(Av1Picture picture, MessageDigest digest) {
        var wide = picture.bpc > 8;
        var planes = picture.layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var ssH = picture.layout == SequenceHeader.LAYOUT_I420 || picture.layout == SequenceHeader.LAYOUT_I422 ? 1 : 0;
        var ssV = picture.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var row = new byte[picture.w * 2];
        for (var plane = 0; plane < planes; plane++) {
            var width = plane == 0 ? picture.w : (picture.w + ssH) >> ssH;
            var height = plane == 0 ? picture.h : (picture.h + ssV) >> ssV;
            var stride = picture.stride[plane == 0 ? 0 : 1];
            var samples = Objects.requireNonNull(picture.data[plane], "plane " + plane);
            for (var y = 0; y < height; y++) {
                var offset = y * stride;
                if (wide) {
                    for (var x = 0; x < width; x++) {
                        var value = samples[offset + x];
                        row[2 * x] = (byte) value;
                        row[2 * x + 1] = (byte) (value >> 8);
                    }
                    digest.update(row, 0, 2 * width);
                } else {
                    for (var x = 0; x < width; x++) {
                        row[x] = (byte) samples[offset + x];
                    }
                    digest.update(row, 0, width);
                }
            }
        }
    }

    /**
     * Splits a low-overhead bitstream (AV1 specification section 5) into temporal units, each starting at a
     * temporal delimiter. From an OBU whose size the splitter cannot read (no {@code obu_has_size_field}, a size
     * past the end, as the streams of the error groups have), the rest of the stream is one last unit, for the
     * decoder to judge.
     */
    static List<byte[]> sectionFiveUnits(byte[] data) {
        var units = new ArrayList<byte[]>();
        var start = 0;
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            var type = (header >> 3) & 0xF;
            var cursor = position + 1 + ((header & 0x4) != 0 ? 1 : 0);
            long end = -1;
            if ((header & 0x2) != 0 && cursor < data.length) {
                var size = leb128(data, cursor);
                end = size == null ? -1 : size[1] + size[0];
            }
            if (end <= position || end > data.length) {
                break;
            }
            if (type == Obu.OBU_TD && position > start) {
                units.add(Arrays.copyOfRange(data, start, position));
                start = position;
            }
            position = (int) end;
        }
        if (data.length > start) {
            units.add(Arrays.copyOfRange(data, start, data.length));
        }
        return units;
    }

    /**
     * Splits a length-delimited bitstream (AV1 specification Annex B) into its temporal units, each with its
     * {@code temporal_unit_size}. From a size field the splitter cannot read or a size past the end, as the streams of
     * the error groups have, the rest of the stream is one last unit, for the decoder to judge.
     */
    static List<byte[]> annexBUnits(byte[] data) {
        var units = new ArrayList<byte[]>();
        var position = 0;
        while (position < data.length) {
            var size = leb128(data, position);
            if (size == null || size[1] + size[0] > data.length) {
                units.add(Arrays.copyOfRange(data, position, data.length));
                break;
            }
            var end = Math.toIntExact(size[1] + size[0]);
            units.add(Arrays.copyOfRange(data, position, end));
            position = end;
        }
        return units;
    }

    /**
     * Decodes a large scale tile stream as {@code lightfield_tile_list_decoder <stream> <output> <anchors> <lists>}
     * does and compares the MD5 of the tiles it writes with the reference.
     */
    private static Verdict checkTileLists(byte[] stream, String command, String reference,
                                          Av1Context.Threading threading) {
        var arguments = TILE_LIST_COMMAND.matcher(command);
        if (!arguments.find()) {
            throw new IllegalStateException("not a tile list decoder command: " + command);
        }
        var anchorCount = Integer.parseInt(arguments.group(1));
        var listCount = Integer.parseInt(arguments.group(2));
        var annexB = !(stream.length > 1 && stream[0] == 0x12 && stream[1] == 0x00);
        var units = annexB ? annexBUnits(stream) : sectionFiveUnits(stream);
        if (units.size() < anchorCount + 1 + listCount) {
            return new Verdict(List.of("the stream holds " + units.size() + " temporal units, the command decodes "
                    + (anchorCount + 1 + listCount)));
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var context = new Av1Context(LIMITS);
        context.threading(threading);
        context.annexB(annexB);
        context.filmGrain(false);
        var anchors = new Av1Picture[anchorCount];
        var out = new ArrayList<Av1Picture>();
        for (var i = 0; i < anchorCount; i++) {
            out.clear();
            context.decode(units.get(i), 0, units.get(i).length, i, 0, out);
            if (out.isEmpty()) {
                return new Verdict(List.of("anchor temporal unit " + i + " shows no frame"));
            }
            var shown = out.getLast();
            var anchor = Av1Picture.allocate(shown.w, shown.h, shown.layout, shown.bpc);
            for (var plane = 0; plane < 3; plane++) {
                if (shown.data[plane] != null) {
                    System.arraycopy(shown.data[plane], 0, anchor.data[plane], 0,
                            Math.min(shown.data[plane].length, anchor.data[plane].length));
                }
            }
            anchors[i] = anchor;
            for (var picture : out) {
                picture.release();
            }
        }
        context.anchorFrames(anchors);
        out.clear();
        context.decode(units.get(anchorCount), 0, units.get(anchorCount).length, 0, 0, out);
        if (!out.isEmpty()) {
            return new Verdict(List.of("the camera frame header shows a frame"));
        }
        for (var list = 0; list < listCount; list++) {
            var unit = units.get(anchorCount + 1 + list);
            var layout = tileListLayout(unit, annexB);
            out.clear();
            context.decode(unit, 0, unit.length, list, 0, out);
            if (out.size() != 1) {
                return new Verdict(List.of("tile list " + list + " yields " + out.size() + " frames"));
            }
            writeTiles(out.getFirst(), layout, digest);
            out.getFirst().release();
        }
        var actual = HexFormat.of().formatHex(digest.digest());
        return new Verdict(actual.equals(reference) ? List.of()
                : List.of("tiles: MD5 " + actual + ", reference " + reference));
    }

    /**
     * Returns the output frame width and height in tiles and the tile count of the first tile list OBU of a temporal
     * unit.
     */
    private static int[] tileListLayout(byte[] unit, boolean annexB) {
        if (!annexB) {
            var position = 0;
            while (position < unit.length) {
                var header = unit[position] & 0xFF;
                var size = leb128(unit, position + ((header & 0x4) != 0 ? 2 : 1));
                var end = Math.toIntExact(size[1] + size[0]);
                var layout = tileListAt(unit, position);
                if (layout != null) {
                    return layout;
                }
                position = end;
            }
        } else {
            var position = (int) leb128(unit, 0)[1];
            while (position < unit.length) {
                var frame = leb128(unit, position);
                var frameEnd = Math.toIntExact(frame[1] + frame[0]);
                var obu = (int) frame[1];
                while (obu < frameEnd) {
                    var length = leb128(unit, obu);
                    var start = (int) length[1];
                    var layout = tileListAt(unit, start);
                    if (layout != null) {
                        return layout;
                    }
                    obu = Math.toIntExact(start + length[0]);
                }
                position = frameEnd;
            }
        }
        throw new IllegalStateException("no tile list OBU");
    }

    /** Returns the layout of the OBU at an index when it is a tile list, or {@code null} for another OBU. */
    private static int[] tileListAt(byte[] unit, int start) {
        var header = unit[start] & 0xFF;
        if (((header >> 3) & 0xF) != Obu.OBU_TILE_LIST) {
            return null;
        }
        var payload = start + ((header & 0x4) != 0 ? 2 : 1);
        if ((header & 0x2) != 0) {
            payload = (int) leb128(unit, payload)[1];
        }
        return new int[]{(unit[payload] & 0xFF) + 1, (unit[payload + 1] & 0xFF) + 1,
                ((unit[payload + 2] & 0xFF) << 8 | (unit[payload + 3] & 0xFF)) + 1};
    }

    /** Hashes the tiles of a tile list's output frame as {@code lightfield_tile_list_decoder} writes them. */
    private static void writeTiles(Av1Picture picture, int[] layout, MessageDigest digest) {
        var columns = layout[0];
        var rows = layout[1];
        var count = layout[2];
        var tileWidth = picture.w / columns;
        var tileHeight = picture.h / rows;
        var ssH = picture.layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        var ssV = picture.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var wide = picture.bpc > 8;
        var row = new byte[2 * tileWidth];
        for (var tile = 0; tile < count; tile++) {
            var x0 = (tile % columns) * tileWidth;
            var y0 = (tile / columns) * tileHeight;
            for (var plane = 0; plane < 3; plane++) {
                var w = plane == 0 ? tileWidth : tileWidth >> ssH;
                var h = plane == 0 ? tileHeight : tileHeight >> ssV;
                var x = plane == 0 ? x0 : x0 >> ssH;
                var y = plane == 0 ? y0 : y0 >> ssV;
                var stride = picture.stride[plane == 0 ? 0 : 1];
                var samples = Objects.requireNonNull(picture.data[plane], "plane " + plane);
                for (var r = 0; r < h; r++) {
                    var offset = (y + r) * stride + x;
                    for (var c = 0; c < w; c++) {
                        var value = samples[offset + c];
                        if (wide) {
                            row[2 * c] = (byte) value;
                            row[2 * c + 1] = (byte) (value >> 8);
                        } else {
                            row[c] = (byte) value;
                        }
                    }
                    digest.update(row, 0, wide ? 2 * w : w);
                }
            }
        }
    }

    /** Reads a leb128 value: returns {value, index after it}, or {@code null} past the end or past 8 bytes. */
    private static long[] leb128(byte[] data, int position) {
        long value = 0;
        for (var i = 0; i < 8 && position + i < data.length; i++) {
            var b = data[position + i] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return new long[]{value, position + i + 1};
            }
        }
        return null;
    }
}
