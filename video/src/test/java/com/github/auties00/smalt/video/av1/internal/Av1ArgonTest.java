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
 * groups must decode or fail with a {@link MediaException}, never another exception.
 *
 * <p>The suite is several gigabytes (licence unstated: it is downloaded, never redistributed), so it runs only when
 * asked for: the tests carry the {@code argon} tag, which the build excludes unless {@code smalt.test.excludedGroups}
 * is overridden, and {@code smalt.test.argon} names the groups to decode (comma-separated, every supported group by
 * default). Each group is an archive set of {@code tools/testdata/archives.tsv}, fetched by range requests from the
 * suite's ZIP archive. The large-scale tile groups are left out: they need an application's tile list decoding
 * ({@code examples/lightfield_tile_list_decoder}), which the decoder does not implement.
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
            "profile_switching");

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
                Future<Verdict> verdict = executor.submit(() -> check(archive, memberSet, group, name));
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
        return tests.stream().onClose(executor::shutdownNow);
    }

    private static Verdict check(Conformance.Archive archive, java.util.Set<String> members, String group, String name)
            throws IOException {
        var base = ROOT + group + "/";
        var stream = Files.readAllBytes(archive.fetch(base + "streams/" + name + ".obu"));
        var command = new String(Files.readAllBytes(archive.fetch(base + "ref_cmd/" + name + ".sh")),
                StandardCharsets.UTF_8);
        var annexB = command.contains("--annexb");
        var units = annexB ? annexBUnits(stream) : sectionFiveUnits(stream);
        var threading = threading(name);
        if (group.endsWith("_error")) {
            try {
                decode(units, 0, true, threading);
            } catch (MediaException expected) {
                // an error stream may be rejected, as long as it is rejected as media
            }
            return new Verdict(List.of());
        }
        var problems = new ArrayList<String>();
        var reference = md5(archive.fetch(base + "md5_ref/" + name + ".md5"));
        var decoded = decode(units, 0, true, threading);
        if (!reference.equals(decoded.md5())) {
            problems.add("operating point 0: MD5 " + decoded.md5() + ", reference " + reference);
        }
        var plain = base + "md5_no_film_grain/" + name + ".md5";
        if (members.contains(plain)) {
            var withoutGrain = md5(archive.fetch(plain));
            if (!withoutGrain.equals(reference)) {
                var ungrained = decode(units, 0, false, threading).md5();
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
            var actual = decode(units, point, true, threading).md5();
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

    private static Decoded decode(List<byte[]> units, int operatingPoint, boolean filmGrain,
                                  Av1Context.Threading threading) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var context = new Av1Context(LIMITS);
        context.threading(threading);
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
     * Converts a length-delimited bitstream (AV1 specification annex B) into low-overhead temporal units: every
     * OBU gets {@code obu_has_size_field} and its size.
     */
    static List<byte[]> annexBUnits(byte[] data) {
        var units = new ArrayList<byte[]>();
        var position = 0;
        while (position < data.length) {
            var unitSize = annexBSize(data, position);
            var unitEnd = Math.toIntExact(unitSize[1] + unitSize[0]);
            var unit = new java.io.ByteArrayOutputStream();
            var frame = (int) unitSize[1];
            while (frame < unitEnd) {
                var frameSize = annexBSize(data, frame);
                var frameEnd = Math.toIntExact(frameSize[1] + frameSize[0]);
                var obu = (int) frameSize[1];
                while (obu < frameEnd) {
                    var obuSize = annexBSize(data, obu);
                    var obuStart = (int) obuSize[1];
                    var obuEnd = Math.toIntExact(obuStart + obuSize[0]);
                    var header = data[obuStart] & 0xFF;
                    var headerLength = 1 + ((header & 0x4) != 0 ? 1 : 0);
                    if ((header & 0x2) != 0) {
                        unit.write(data, obuStart, obuEnd - obuStart);
                    } else {
                        unit.write(header | 0x2);
                        unit.write(data, obuStart + 1, headerLength - 1);
                        writeLeb128(unit, obuEnd - obuStart - headerLength);
                        unit.write(data, obuStart + headerLength, obuEnd - obuStart - headerLength);
                    }
                    obu = obuEnd;
                }
                frame = frameEnd;
            }
            units.add(unit.toByteArray());
            position = unitEnd;
        }
        return units;
    }

    /** Reads a size field of an annex B stream, failing when the stream ends inside it. */
    private static long[] annexBSize(byte[] data, int position) {
        var size = leb128(data, position);
        if (size == null || size[1] + size[0] > data.length) {
            throw new IllegalStateException("annex B size field at " + position + " runs past the end of the stream");
        }
        return size;
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

    private static void writeLeb128(java.io.ByteArrayOutputStream out, long value) {
        do {
            var b = (int) (value & 0x7F);
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);
    }
}
