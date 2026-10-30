package com.github.auties00.smalt.testkit.validate;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the files Smalt writes with checks that do not share code with Smalt's demuxers: a structural and
 * semantic validator of each container, written from its specification in this package, and, where the machine has
 * it, the format's independent reference validator run as a black box:
 * <ul>
 *   <li>ISO base media files (MP4, QuickTime, fragmented MP4, CMAF): {@link IsoBmffValidator} and GPAC's
 *       {@code MP4Box -info};</li>
 *   <li>Matroska and WebM: {@link MatroskaValidator} and the Matroska foundation's {@code mkvalidator};</li>
 *   <li>MPEG-2 transport streams: {@link TsValidator} and TSDuck's {@code tsp -P analyze};</li>
 *   <li>MPEG program streams and MPEG-1 system streams: {@link PsValidator};</li>
 *   <li>MXF: {@link MxfValidator};</li>
 *   <li>HLS: {@link HlsValidator}, which validates every segment with the validators above;</li>
 *   <li>MPEG-DASH: {@link DashValidator}, likewise, with the MPD schema of ISO/IEC 23009-1.</li>
 * </ul>
 * The reference validators run where they are on {@code PATH}, without skipping when they are not: CI provisions
 * them (MP4Box everywhere but Windows, the others on every runner, see {@code tools/testdata/oracles.tsv}) and
 * {@code smalt.test.requireOracles} makes a provisioned one that is missing fail. Every finding fails the calling
 * test with its location.
 */
public final class Validators {
    /** The directory of this fork that files given as bytes are written to for the reference validators. */
    private static final Path DIRECTORY = Scratch.directory("validated");

    /** The container formats this class validates. */
    public enum Format {
        /** An ISO base media file or segment. */
        ISOBMFF,
        /** A Matroska or WebM file. */
        MATROSKA,
        /** An MPEG-2 transport stream. */
        TRANSPORT_STREAM,
        /** An MPEG program stream or MPEG-1 system stream. */
        PROGRAM_STREAM,
        /** An MXF file. */
        MXF,
        /** An HLS playlist. */
        HLS,
        /** A DASH manifest. */
        DASH,
        /** A format without a validator here. */
        OTHER
    }

    /**
     * The mkvalidator reports a valid file can draw, each matched against a whole report line and mapped to why it is
     * accepted; mkvalidator 0.6 predates parts of RFC 9559 and of the WebM guidelines.
     */
    private static final Map<Pattern, String> ACCEPTED_MKVALIDATOR_REPORTS = Map.of(
            Pattern.compile("WRN170: .*"), "a live stream may carry a SeekHead of the elements written before its"
                    + " first Cluster, whose positions MatroskaValidator checks",
            Pattern.compile("WRN308: .*"), "its codec list predates registered codec IDs such as D_WEBVTT/*;"
                    + " MatroskaValidator checks the WebM codec set",
            Pattern.compile("WRN0B8: .*"), "a track without frames is valid",
            Pattern.compile("WRN0D0: .*"), "Void elements are valid padding",
            Pattern.compile("ERR302: Track #\\d+ type 17 not supported for profile 'webm'"),
            "the WebM container guidelines carry WebVTT in subtitle tracks",
            Pattern.compile("ERR304: Track #\\d+ codec V_AV1 not supported for profile 'webm'"),
            "the AV1 Matroska/WebM mapping of the Alliance for Open Media carries AV1 in WebM",
            Pattern.compile("ERR201: Invalid '(EditionDisplay|EditionString|EditionLanguageIETF)' for profile"
                    + " 'matroska v\\d' .*"),
            "mkvalidator predates the EditionDisplay elements RFC 9559 adds in Matroska version 5",
            Pattern.compile("ERR201: Invalid '(MaxBlockAdditionID|BlockAdditionMapping|BlockAddIDValue|BlockAddIDType"
                    + "|BlockAddIDName|BlockAddIDExtraData)' for profile 'webm' .*"),
            "the WebM container guidelines list the block addition mapping elements as supported",
            // FIXME: TagEditor and MatroskaMuxer write picture attachments into WebM, which the WebM container
            // guidelines mark unsupported; EditionUID stays accepted for the Chapters an edit keeps from other writers
            Pattern.compile("ERR201: Invalid '(EditionUID|AttachedFile|FileDescription|FileName|FileMediaType"
                    + "|FileData|FileUID)' for profile 'webm' .*"),
            "TagEditor writes picture attachments into WebM files and keeps other writers' chapters");

    private Validators() {
        throw new AssertionError();
    }

    /**
     * Returns the format of a file from its first bytes.
     *
     * @param data the file
     * @return the format
     */
    public static Format sniff(byte[] data) {
        if (data.length >= 8) {
            var type = new String(data, 4, 4, StandardCharsets.ISO_8859_1);
            if (Set.of("ftyp", "styp", "moov", "moof", "sidx", "mdat", "free", "wide", "skip").contains(type)) {
                return Format.ISOBMFF;
            }
        }
        if (data.length >= 4 && (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45 && (data[2] & 0xFF) == 0xDF
                && (data[3] & 0xFF) == 0xA3) {
            return Format.MATROSKA;
        }
        if (isTransportStream(data)) {
            return Format.TRANSPORT_STREAM;
        }
        if (data.length >= 12 && data[0] == 0 && data[1] == 0 && data[2] == 1 && (data[3] & 0xFF) == 0xBA) {
            return Format.PROGRAM_STREAM;
        }
        if (data.length >= 16 && (data[0] & 0xFF) == 0x06 && data[1] == 0x0E && data[2] == 0x2B && data[3] == 0x34
                && data[4] == 0x02 && data[5] == 0x05 && data[8] == 0x0D && data[9] == 0x01 && data[10] == 0x02
                && data[11] == 0x01 && data[12] == 0x01 && data[13] == 0x02) {
            return Format.MXF;
        }
        var head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.ISO_8859_1);
        if (head.startsWith("#EXTM3U")) {
            return Format.HLS;
        }
        if (head.contains("<MPD")) {
            return Format.DASH;
        }
        return Format.OTHER;
    }

    /**
     * Returns whether a file starts like a transport stream: sync bytes at the start of its first two packets, or a
     * file of exactly one packet. The sync bytes of the later packets are left to {@link TsValidator}, so that a
     * stream that loses its sync is still validated. A GIF signature ({@code GIF8}) is never a transport stream,
     * although its first byte is the sync byte.
     *
     * @param data the file
     * @return whether it is a transport stream
     */
    private static boolean isTransportStream(byte[] data) {
        if (data.length < 188 || data[0] != 0x47) {
            return false;
        }
        if (data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return false;
        }
        return data.length < 376 ? data.length == 188 : data[188] == 0x47;
    }

    /**
     * Validates a file of any format this class knows; a file of another format passes.
     *
     * @param file the file
     * @return the format
     */
    public static Format assertValid(Path file) {
        var data = read(file);
        var format = sniff(data);
        switch (format) {
            case ISOBMFF -> isoBmff(file, data);
            case MATROSKA -> matroska(file, data);
            case TRANSPORT_STREAM -> transportStream(file, data);
            case PROGRAM_STREAM -> programStream(file.toString(), data, false);
            case MXF -> assertValidMxf(file.toString(), data);
            case HLS -> assertValidHls(file);
            case DASH -> assertValidDash(file);
            case OTHER -> {
            }
        }
        return format;
    }

    /**
     * Validates a file held in memory, writing it under {@code target/validated} for the reference validators.
     *
     * @param name the file name, unique within the test run
     * @param data the file
     * @return the format
     */
    public static Format assertValid(String name, byte[] data) {
        var format = sniff(data);
        if (format == Format.OTHER) {
            return format;
        }
        return assertValid(save(name, data));
    }

    /**
     * Validates an ISO base media file.
     *
     * @param file the file
     * @return what validation learned of the file
     */
    public static IsoBmffValidator.Movie assertValidIsoBmff(Path file) {
        return isoBmff(file, read(file));
    }

    /**
     * Validates a Matroska or WebM file.
     *
     * @param file the file
     */
    public static void assertValidMatroska(Path file) {
        matroska(file, read(file));
    }

    /**
     * Validates an MPEG-2 transport stream.
     *
     * @param file the file
     * @return what validation learned of the stream
     */
    public static TsValidator.Summary assertValidTs(Path file) {
        return transportStream(file, read(file));
    }

    /**
     * Validates an MXF file.
     *
     * @param name the name of the file, for the failure message
     * @param data the file
     * @return what validation learned of the file
     */
    public static MxfValidator.Summary assertValidMxf(String name, byte[] data) {
        var findings = new Findings(name);
        var summary = MxfValidator.validate(data, findings);
        findings.assertNone("MxfValidator");
        return summary;
    }

    /**
     * Validates an MPEG program stream or MPEG-1 system stream.
     *
     * @param name the name of the stream, for the failure message
     * @param data the stream
     * @param strict whether the pacing of the system clock references is checked too
     * @return what validation learned of the stream
     */
    public static PsValidator.Summary assertValidPs(String name, byte[] data, boolean strict) {
        return programStream(name, data, strict);
    }

    /**
     * Validates an HLS presentation: a master playlist with everything it references, or a media playlist with its
     * segments.
     *
     * @param playlist the playlist
     */
    public static void assertValidHls(Path playlist) {
        var findings = new Findings(playlist.toString());
        HlsValidator.validate(playlist, findings);
        findings.assertNone("the HLS validator");
    }

    /**
     * Validates a DASH presentation: the manifest and every segment it references.
     *
     * @param manifest the MPD
     */
    public static void assertValidDash(Path manifest) {
        var findings = new Findings(manifest.toString());
        DashValidator.validate(manifest, findings);
        findings.assertNone("the DASH validator");
    }

    private static IsoBmffValidator.Movie isoBmff(Path file, byte[] data) {
        var findings = new Findings(file.toString());
        var movie = IsoBmffValidator.validate(data, findings, IsoBmffValidator.Kind.FILE, Map.of());
        findings.assertNone("the ISOBMFF validator");
        if (Oracle.find(Oracle.Tool.MP4BOX).isPresent()) {
            mp4Box(file);
        }
        return movie;
    }

    private static void matroska(Path file, byte[] data) {
        var findings = new Findings(file.toString());
        var summary = MatroskaValidator.validate(data, findings);
        findings.assertNone("the Matroska validator");
        if (Oracle.find(Oracle.Tool.MKVALIDATOR).isPresent()) {
            mkvalidator(file, summary.live(), !summary.media());
        }
    }

    private static PsValidator.Summary programStream(String name, byte[] data, boolean strict) {
        var findings = new Findings(name);
        var summary = PsValidator.validate(data, findings, strict);
        findings.assertNone("the program stream validator");
        return summary;
    }

    private static TsValidator.Summary transportStream(Path file, byte[] data) {
        var findings = new Findings(file.toString());
        var summary = TsValidator.validate(data, findings);
        findings.assertNone("the transport stream validator");
        if (Oracle.find(Oracle.Tool.TSP).isPresent()) {
            tsduck(file);
        }
        return summary;
    }

    /** An ANSI color sequence of GPAC's log, whose color is the level: 31 error, 33 warning, others information. */
    private static final Pattern COLOR = Pattern.compile("\u001B\\[([0-9;]*)m");

    /** GPAC's warning about an item box larger than the one value it reads. */
    private static final Pattern EXTRA_BYTES = Pattern.compile("Box \"([^\"]+)\" \\(start (\\d+)\\) has (\\d+) extra bytes");

    /** A failure GPAC reports on a box whose parsing it gave up. */
    private static final Pattern READ_FAILED = Pattern.compile("Read Box \"([^\"]+)\" \\(start \\d+\\) failed");

    /**
     * Runs {@code MP4Box -info} on a file and fails on a non-zero exit or on any error or warning its container tools
     * log (an unknown box type is information, and the codec summaries are left to the reference decoders), except
     * for the readings of GPAC 2.2 that depart from the specification on boxes {@link IsoBmffValidator} checks itself:
     * <ul>
     *   <li>an iTunes metadata item holding several {@code data} boxes, which QuickTime allows and GPAC reads one
     *       of ("has N extra bytes");</li>
     *   <li>QuickTime's base media information atom of a text track ({@code gmhd/text}), which GPAC parses as a text
     *       sample entry ("Broken text box detected");</li>
     *   <li>a {@code dOps} box of channel mapping family 3, whose RFC 8486 demixing matrix GPAC reads as the
     *       channel mapping of the other families ("has N extra bytes");</li>
     *   <li>a version 0 {@code chnl} box with explicit speaker positions, for which GPAC reads an omitted channels
     *       map ("not enough bytes in box chnl"), and the boxes it then abandons ("Read Box ... failed").</li>
     * </ul>
     *
     * @param file the file
     */
    public static void mp4Box(Path file) {
        var data = read(file);
        var boxes = IsoBmff.parse(data, new Findings(file.toString()));
        var result = Oracle.execute(Oracle.Tool.MP4BOX, Duration.ofMinutes(2), null,
                List.of("-info", file.toAbsolutePath().toString()));
        var output = new String(result.stdout(), StandardCharsets.UTF_8) + result.stderr();
        var problems = new ArrayList<String>();
        var abandoned = false;
        for (var raw : output.lines().toList()) {
            var color = COLOR.matcher(raw);
            var level = color.find() ? color.group(1) : "";
            var line = COLOR.matcher(raw).replaceAll("").strip();
            // the container tools' errors and warnings; the codec summaries of -info (RFC 6381 strings, the sample
            // GPAC reads to describe a stream) judge the media data, which the reference decoders check instead
            var container = line.contains("[iso file]") || line.contains("[isom") || line.contains("[container]");
            var flagged = container && (level.equals("31") || level.equals("33") || level.isEmpty());
            if (!flagged || line.isEmpty()) {
                continue;
            }
            var extra = EXTRA_BYTES.matcher(line);
            if (extra.find() && (isMultiValueItem(data, Long.parseLong(extra.group(2)))
                    || isDemixingOpusConfig(data, Long.parseLong(extra.group(2)), Long.parseLong(extra.group(3))))) {
                continue;
            }
            if (line.contains("Broken text box detected") && hasQuickTimeTextInfo(boxes)) {
                continue;
            }
            if (line.contains("not enough bytes in box chnl") && hasExplicitChannelLayout(data, boxes)) {
                abandoned = true;
                continue;
            }
            var failed = READ_FAILED.matcher(line);
            if (abandoned && failed.find() && Set.of("chnl", "ipcm", "fpcm", "stsd", "stbl", "minf", "mdia", "trak",
                    "moov").contains(failed.group(1))) {
                continue;
            }
            problems.add(line);
        }
        // GPAC exits with an error only when it abandons the file, as on an explicit chnl layout
        if (!problems.isEmpty() || result.exitCode() != 0 && !abandoned) {
            org.junit.jupiter.api.Assertions.fail("MP4Box -info rejects " + file + " (exit " + result.exitCode()
                    + "):\n  " + String.join("\n  ", problems.isEmpty() ? output.lines().toList() : problems));
        }
    }

    /**
     * {@return whether the box at an offset is a {@code dOps} box of channel mapping family 3 that ends with the RFC
     * 8486 demixing matrix of its channel and stream counts, and GPAC left unread exactly the part of that matrix
     * beyond the channel mapping it reads in its place}
     *
     * @param data the file
     * @param offset the offset of the box
     * @param extra the number of bytes GPAC left unread
     */
    private static boolean isDemixingOpusConfig(byte[] data, long offset, long extra) {
        if (offset + 8 + 13 > data.length || !IsoBmff.fourcc(data, offset + 4).equals("dOps")) {
            return false;
        }
        var size = IsoBmff.u32(data, offset);
        var payload = offset + 8;
        var channels = IsoBmff.u8(data, payload + 1);
        // the 11-byte header, the stream and coupled stream counts, then one 16-bit gain per channel and stream
        var matrix = 2L * channels * (IsoBmff.u8(data, payload + 11) + IsoBmff.u8(data, payload + 12));
        return IsoBmff.u8(data, payload + 10) == 3 && offset + size <= data.length && size == 8 + 13 + matrix
                && matrix - channels == extra;
    }

    /** {@return whether the box at an offset is an ilst item holding only {@code data} boxes, at least two} */
    private static boolean isMultiValueItem(byte[] data, long offset) {
        if (offset + 8 > data.length) {
            return false;
        }
        var size = IsoBmff.u32(data, offset);
        var end = offset + size;
        if (end > data.length) {
            return false;
        }
        var count = 0;
        for (var position = offset + 8; position < end; ) {
            if (position + 8 > end || !IsoBmff.fourcc(data, position + 4).equals("data")) {
                return false;
            }
            var child = IsoBmff.u32(data, position);
            if (child < 16 || position + child > end) {
                return false;
            }
            position += child;
            count++;
        }
        return count >= 2;
    }

    /** {@return whether a track carries QuickTime's base media information of a text track, a 36-byte matrix} */
    private static boolean hasQuickTimeTextInfo(List<IsoBmff.Box> boxes) {
        return IsoBmff.top(boxes, "moov").stream().flatMap(moov -> moov.all("trak").stream())
                .flatMap(trak -> trak.find("mdia", "minf", "gmhd", "text").stream())
                .anyMatch(text -> text.payloadSize() == 36);
    }

    /** {@return whether every chnl box is a valid version 0 layout of explicit speaker positions} */
    private static boolean hasExplicitChannelLayout(byte[] data, List<IsoBmff.Box> boxes) {
        var found = false;
        for (var trak : IsoBmff.top(boxes, "moov").map(moov -> moov.all("trak")).orElse(List.of())) {
            var stsd = trak.find("mdia", "minf", "stbl", "stsd");
            if (stsd.isEmpty()) {
                continue;
            }
            for (var entry : stsd.get().children()) {
                var chnl = entry.child("chnl");
                if (chnl.isEmpty()) {
                    continue;
                }
                var box = chnl.get();
                var channels = IsoBmff.u16(data, entry.offset() + 24);
                if (IsoBmff.version(data, box) != 0 || box.payloadSize() != 4 + 2 + channels
                        || IsoBmff.u8(data, box.payload() + 4) != 1 || IsoBmff.u8(data, box.payload() + 5) != 0) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    /**
     * Runs {@code mkvalidator} on a file and fails on a non-zero exit, on any error and on any warning that is not
     * accepted for a valid file.
     *
     * @param file the file
     * @param live whether the file is a live stream, written without seeking back (its Segment has an unknown size)
     * @param withoutMedia whether the file has no video or audio track, which accepts the warning about clusters
     *                     without Cues (RFC 9559 recommends Cues for seeking, MatroskaValidator requires them with
     *                     media)
     */
    public static void mkvalidator(Path file, boolean live, boolean withoutMedia) {
        var arguments = new ArrayList<String>();
        if (live) {
            arguments.add("--live");
        }
        arguments.add(file.toAbsolutePath().toString());
        var result = Oracle.execute(Oracle.Tool.MKVALIDATOR, Duration.ofMinutes(2), null, arguments);
        var output = new String(result.stdout(), StandardCharsets.UTF_8) + result.stderr();
        var problems = new ArrayList<String>();
        var errors = 0;
        for (var raw : output.split("[\r\n]+")) {
            var line = raw.strip();
            if (!line.startsWith("ERR") && !line.startsWith("WRN")) {
                continue;
            }
            if (line.startsWith("ERR")) {
                errors++;
            }
            var accepted = ACCEPTED_MKVALIDATOR_REPORTS.keySet().stream()
                    .anyMatch(pattern -> pattern.matcher(line).matches())
                    || withoutMedia && line.startsWith("WRN800");
            if (!accepted) {
                problems.add(line);
            }
        }
        if (Oracle.crashed(result.exitCode())) {
            // a crash says nothing of the file, which MatroskaValidator has accepted; the report keeps a small file
            // for a reproduction
            var report = "[mkvalidator] crashed (exit " + result.exitCode() + ") on " + file + ": " + output.strip();
            try {
                if (Files.size(file) <= 65536) {
                    report += "\n[mkvalidator] base64 " + java.util.Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            System.out.println(report);
            return;
        }
        // the exit status counts the errors, so a file whose every error is accepted exits non-zero
        if (result.exitCode() != 0 && errors == 0 || !problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail("mkvalidator" + (live ? " --live" : "") + " rejects " + file
                    + " (exit " + result.exitCode() + "):\n  " + String.join("\n  ",
                    problems.isEmpty() ? List.of(output.strip()) : problems));
        }
    }

    /**
     * Runs TSDuck's {@code analyze} plugin on a transport stream and fails on any sync, transport, continuity,
     * duplicate, scrambling, PES prefix or timestamp leap error it counts, or on a PID no PSI references.
     *
     * @param file the stream
     */
    public static void tsduck(Path file) {
        Path report;
        try {
            Files.createDirectories(DIRECTORY);
            report = Files.createTempFile(DIRECTORY, "tsanalyze-", ".txt");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try {
            var result = Oracle.execute(Oracle.Tool.TSP, Duration.ofMinutes(2), null, List.of("-I", "file",
                    file.toAbsolutePath().toString(), "-P", "analyze", "--normalized", "-o",
                    report.toAbsolutePath().toString(), "-O", "drop"));
            if (result.exitCode() != 0) {
                org.junit.jupiter.api.Assertions.fail("tsp fails on " + file + " (exit " + result.exitCode() + "): "
                        + result.stderr());
            }
            var problems = new ArrayList<String>();
            var services = 0;
            for (var line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                if (line.startsWith("ts:")) {
                    for (var key : List.of("invalidsyncs", "transporterrors", "suspectignored", "unreferencedpids")) {
                        var value = field(line, key);
                        if (value != null && !value.equals("0")) {
                            problems.add(key + "=" + value);
                        }
                    }
                    var count = field(line, "services");
                    services = count == null ? 0 : Integer.parseInt(count);
                } else if (line.startsWith("pid:")) {
                    for (var key : List.of("discontinuities", "duplicated", "invalidscrambling", "invalidpesprefix",
                            "pcrleap", "ptsleap", "dtsleap")) {
                        var value = field(line, key);
                        if (value != null && !value.equals("0")) {
                            problems.add("PID " + field(line, "pid") + " " + key + "=" + value);
                        }
                    }
                }
            }
            if (services < 1) {
                problems.add("no service");
            }
            if (!problems.isEmpty()) {
                org.junit.jupiter.api.Assertions.fail("TSDuck's analyze plugin rejects " + file + ": "
                        + String.join(", ", problems));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            try {
                Files.deleteIfExists(report);
            } catch (IOException ignored) {
                // the report is a scratch file under target
            }
        }
    }

    /** Returns the value of a {@code key=value} field of a normalized TSDuck report line, or {@code null}. */
    private static String field(String line, String key) {
        for (var part : line.split(":")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
