package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Demuxes third-party files (the FATE, TSDuck and other real-world samples of the hosted test data) and compares
 * them with ffprobe: every stream, and every packet's size, data and key frame flag, with the timestamps compared
 * relative to the first packet of each stream, since demuxers may start the timeline elsewhere than ffmpeg does.
 */
public final class ThirdPartyDemux {
    private ThirdPartyDemux() {
        throw new AssertionError();
    }

    /**
     * Demuxes a vector with a demuxer and compares it with ffprobe packet by packet.
     *
     * @param vector  the vector name
     * @param factory the demuxer
     * @return what the demuxer read
     */
    public static ContainerProbe.Demuxed assertMatchesFfprobe(String vector, Function<MediaSource, Demuxer> factory) {
        return assertMatchesFfprobe(Conformance.vector(vector), factory, Options.DEFAULT);
    }

    /**
     * Demuxes a vector with a demuxer and compares it with ffprobe packet by packet.
     *
     * @param vector  the vector name
     * @param factory the demuxer
     * @param options what is compared
     * @return what the demuxer read
     */
    public static ContainerProbe.Demuxed assertMatchesFfprobe(String vector, Function<MediaSource, Demuxer> factory,
                                                              Options options) {
        return assertMatchesFfprobe(Conformance.vector(vector), factory, options);
    }

    /**
     * Demuxes a file with a demuxer and compares it with ffprobe packet by packet.
     *
     * @param file    the file
     * @param factory the demuxer
     * @param options what is compared
     * @return what the demuxer read
     */
    public static ContainerProbe.Demuxed assertMatchesFfprobe(Path file, Function<MediaSource, Demuxer> factory,
                                                              Options options) {
        var probed = ContainerProbe.probe(file, options.ffprobeOptions());
        // ffmpeg lists Matroska attachments as streams; demuxers report them as attachments, not tracks
        var probe = new ContainerProbe.Probe(probed.streams().stream()
                .filter(stream -> !"attachment".equals(stream.get("codec_type"))).toList(), probed.packets(),
                probed.format());
        var ours = options.cut() ? demuxCut(file, factory) : ContainerProbe.demux(file, factory);
        if (options.byPid()) {
            // a transport stream: ffprobe lists the streams of every program, ours those of one, by PID
            var streams = new ArrayList<ContainerProbe.ProbeStream>();
            for (var track : ours.tracks()) {
                var stream = probe.streams().stream()
                        .filter(candidate -> candidate.get("id") != null
                                && Long.decode(candidate.get("id")) == track.id())
                        .findFirst();
                assertTrue(stream.isPresent(), file.getFileName() + ": ffprobe has no stream of PID " + track.id());
                streams.add(stream.get());
            }
            probe = new ContainerProbe.Probe(streams, probe.packets(), probe.format());
        }
        var compared = DemuxAssertions.inProbeTimeBase(ours, probe);
        assertEquals(probe.streams().size(), compared.tracks().size(), file.getFileName() + " track count: "
                + compared.tracks());
        assertStreamsMatch(ours.tracks(), probe);
        for (var index = 0; index < compared.tracks().size(); index++) {
            var track = compared.tracks().get(index);
            var stream = probe.streams().get(index);
            var label = file.getFileName() + " track " + index + " (" + track.codec() + ")";
            if (options.codecs() && track.codec() != Codec.DATA && track.codec() != Codec.UNKNOWN_AUDIO
                    && track.codec() != Codec.UNKNOWN_VIDEO) {
                var name = DemuxAssertions.ffprobeName(track.codec());
                assertEquals(stream.codecName(), FFPROBE_NAMES.getOrDefault(name, name), label + " codec");
            }
            assertPackets(label, track, compared.packetsOf(track), probe.packetsOf(stream.index()), options);
        }
        return ours;
    }

    /** The packets before the first random access point of a transport stream ffprobe may return and ours drops. */
    private static final int STREAM_START = 10_000;

    /** The ffprobe codec names that differ from {@link DemuxAssertions#ffprobeName(Codec)}, by that name. */
    private static final Map<String, String> FFPROBE_NAMES = Map.of("dv", "dvvideo");

    /**
     * Reads the packets of a cut file: every packet up to the point where the demuxer reports the file's end as
     * malformed (a sample or element running past it).
     */
    private static ContainerProbe.Demuxed demuxCut(Path file, Function<MediaSource, Demuxer> factory) {
        try (var source = MediaSource.of(file); var demuxer = factory.apply(source)) {
            var packets = new LinkedHashMap<Integer, List<Packet>>();
            var order = new ArrayList<Packet>();
            for (var track : demuxer.tracks()) {
                packets.put(track.id(), new ArrayList<>());
            }
            try {
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    order.add(packet);
                    packets.computeIfAbsent(packet.trackId(), key -> new ArrayList<>()).add(packet);
                }
            } catch (MediaException.Malformed endOfCut) {
                assertTrue(!order.isEmpty(), file.getFileName() + " failed before its first packet: " + endOfCut);
            }
            return new ContainerProbe.Demuxed(demuxer.tracks(), packets, order, demuxer.metadata(),
                    demuxer.durationMicros());
        }
    }

    /**
     * Asserts the dimensions, sample rate and channels of each track against ffprobe's stream, which reports the
     * output rate of AAC with implicit SBR (twice the rate its AudioSpecificConfig declares) only after decoding.
     */
    private static void assertStreamsMatch(List<Track> tracks, ContainerProbe.Probe probe) {
        for (var index = 0; index < tracks.size(); index++) {
            var track = tracks.get(index);
            var stream = probe.streams().get(index);
            switch (track) {
                case com.github.auties00.smalt.track.VideoTrack video -> {
                    if (video.codec() != Codec.UNKNOWN_VIDEO && stream.get("width") != null && video.width() > 0) {
                        assertEquals(Integer.parseInt(stream.get("width")), video.width(), "width of track " + index);
                        assertEquals(Integer.parseInt(stream.get("height")), video.height(),
                                "height of track " + index);
                    }
                }
                case com.github.auties00.smalt.track.AudioTrack audio -> {
                    if (stream.get("sample_rate") != null && audio.sampleRate() > 0) {
                        var rate = Integer.parseInt(stream.get("sample_rate"));
                        var implicitSbr = audio.codec() == Codec.AAC && rate == 2 * audio.sampleRate();
                        assertTrue(implicitSbr || rate == audio.sampleRate(), "sample rate of track " + index + ": "
                                + audio.sampleRate() + ", ffprobe " + rate);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void assertPackets(String label, Track track, List<Packet> ours,
                                      List<ContainerProbe.ProbePacket> expected, Options options) {
        if (expected.isEmpty()) {
            return;
        }
        // ffmpeg drops packets before the first key frame of some containers, and the last of a truncated file:
        // ffprobe's packets must be a contiguous run of ours
        var first = 0;
        if (options.probeLeading() > 0 && !ours.isEmpty()) {
            // ffmpeg returns the partial first PES of a stream that starts mid-packet, which ours drops
            var start = ContainerProbe.crc32(ours.getFirst().data());
            var skipped = 0;
            while (skipped < Math.min(options.probeLeading(), expected.size() - 1)
                    && !expected.get(skipped).hash().equals(start)) {
                skipped++;
            }
            if (expected.get(skipped).hash().equals(start)) {
                expected = expected.subList(skipped, expected.size());
            }
        }
        var head = expected.getFirst();
        while (first < ours.size() && !ContainerProbe.crc32(ours.get(first).data()).equals(head.hash())) {
            first++;
        }
        assertTrue(first < ours.size(), label + ": ffprobe's first packet (" + head.size() + " bytes) is not among "
                + ours.size() + " packets");
        assertTrue(first <= options.extraLeading(), label + ": " + first + " packets before ffprobe's first");
        var count = Math.min(expected.size(), ours.size() - first);
        assertTrue(expected.size() - count <= options.missingTrailing(), label + ": " + count + " of "
                + expected.size() + " packets");
        assertTrue(ours.size() - first - count <= options.extraTrailing(), label + ": " + (ours.size() - first - count)
                + " packets after ffprobe's last");
        var origin = ours.get(first).pts();
        for (var position = 0; position < count; position++) {
            var want = expected.get(position);
            var got = ours.get(first + position);
            var where = label + " packet " + position;
            assertEquals(want.size(), got.size(), where + " size");
            assertEquals(want.hash(), ContainerProbe.crc32(got.data()), where + " data");
            if (options.keyframes() && track.codec() != Codec.DATA) {
                assertEquals(want.key(), got.keyframe(), where + " keyframe");
            }
            if (options.timestamps() && want.pts() != Packet.NO_TIMESTAMP && got.pts() != Packet.NO_TIMESTAMP
                    && head.pts() != Packet.NO_TIMESTAMP && origin != Packet.NO_TIMESTAMP) {
                assertTrue(Math.abs((want.pts() - head.pts()) - (got.pts() - origin)) <= options.ptsTolerance(),
                        where + " pts: expected " + (want.pts() - head.pts()) + " after the first, was "
                                + (got.pts() - origin));
            }
        }
    }

    /**
     * Demuxes a transport stream and compares it with ffprobe: the tracks with ffprobe's streams of the same PID,
     * and the packets of each with ffprobe's. The demuxer drops what precedes a stream's first random access point
     * and the partial PES a stream may end on, stores video as length-prefixed NAL units without access unit
     * delimiters and AAC without its ADTS header ({@code TsDemuxerTest} checks the conventions), and starts its
     * timeline elsewhere than ffmpeg: so every packet of ours must be one of ffprobe's, the payloads compared as
     * they are (MPEG audio, AC-3, E-AC-3, Opus, teletext, DVB subtitles and data), by NAL units (H.264 and HEVC) or
     * by ADTS frame size (AAC), and ffprobe may have only the packets before the first of ours and the last one
     * more. The decoding times of each track must not decrease.
     *
     * @param vector the vector name
     * @return what the demuxer read
     */
    public static ContainerProbe.Demuxed assertTransportStream(String vector) {
        var file = Conformance.vector(vector);
        var probe = ContainerProbe.probe(file);
        var ours = ContainerProbe.demux(file, Containers::openDemuxer);
        assertTrue(!ours.tracks().isEmpty(), vector + " has no track");
        var streams = new ArrayList<ContainerProbe.ProbeStream>();
        for (var track : ours.tracks()) {
            var stream = probe.streams().stream()
                    .filter(candidate -> candidate.get("id") != null && Long.decode(candidate.get("id")) == track.id())
                    .findFirst();
            assertTrue(stream.isPresent(), vector + ": ffprobe has no stream of PID " + track.id());
            streams.add(stream.get());
        }
        assertStreamsMatch(ours.tracks(), new ContainerProbe.Probe(streams, probe.packets(), probe.format()));
        List<byte[]> data = null;
        DemuxAssertions.assertMonotonicDts(ours);
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = streams.get(index);
            var label = vector + " track " + index + " (" + track.codec() + ", PID " + track.id() + ")";
            var expected = new ArrayList<String>();
            var actual = new ArrayList<String>();
            var packets = ours.packetsOf(track);
            var probed = probe.packetsOf(stream.index());
            switch (track.codec()) {
                case H264, HEVC -> {
                    if (data == null) {
                        data = ContainerProbe.packetData(file);
                    }
                    for (var position = 0; position < probe.packets().size(); position++) {
                        if (probe.packets().get(position).stream() == stream.index()) {
                            expected.add(String.join(",", nalUnits(data.get(position), track.codec(), true)));
                        }
                    }
                    for (var packet : packets) {
                        actual.add(String.join(",", nalUnits(packet.data(), track.codec(), false)));
                    }
                }
                case AAC -> {
                    if ("aac_latm".equals(stream.codecName())) {
                        continue;
                    }
                    // the ADTS header: 7 bytes, 9 with its CRC
                    for (var packet : probed) {
                        expected.add(Integer.toString(packet.size()));
                    }
                    for (var packet : packets) {
                        actual.add(Integer.toString(packet.size() + (expected.contains(
                                Integer.toString(packet.size() + 7)) ? 7 : 9)));
                    }
                }
                default -> {
                    if (!RAW_PAYLOADS.contains(track.codec())) {
                        continue;
                    }
                    for (var packet : probed) {
                        expected.add(packet.hash());
                    }
                    for (var packet : packets) {
                        actual.add(ContainerProbe.crc32(packet.data()));
                    }
                }
            }
            if (actual.isEmpty()) {
                // a stream of the program map that carries nothing (an idle SCTE 35 PID)
                assertTrue(expected.size() <= 1, label + " has no packet, ffprobe " + expected.size());
                continue;
            }
            // ours must be a contiguous run of ffprobe's, from the first of ours to ffprobe's end
            var first = expected.indexOf(actual.getFirst());
            assertTrue(first >= 0, label + ": the first packet is not among ffprobe's");
            for (var position = 0; position < actual.size(); position++) {
                var at = first + position;
                if (position == actual.size() - 1 && (at >= expected.size() || !expected.get(at).equals(actual.get(position)))) {
                    // the last PES of a stream may be incomplete in ffmpeg's reading or ours
                    continue;
                }
                assertTrue(at < expected.size(), label + ": " + actual.size() + " packets from ffprobe's " + first
                        + ", which has " + expected.size());
                assertEquals(expected.get(at), actual.get(position), label + " packet " + position);
            }
            assertTrue(expected.size() - first - actual.size() <= 1, label + ": " + actual.size() + " of "
                    + (expected.size() - first) + " packets from ffprobe's " + first);
        }
        return ours;
    }

    /** The codecs whose payload a transport stream demuxer returns as the PES carries it. */
    private static final java.util.Set<Codec> RAW_PAYLOADS = java.util.EnumSet.of(Codec.MP2, Codec.MP3, Codec.AC3,
            Codec.EAC3, Codec.OPUS, Codec.DATA, Codec.DVB_TELETEXT, Codec.DVB_SUBTITLE);

    /**
     * Returns the NAL units of an access unit without access unit delimiters, filler data and the trailing zero
     * bytes of Annex B, as hex strings.
     */
    private static List<String> nalUnits(byte[] data, Codec codec, boolean annexB) {
        var units = new ArrayList<byte[]>();
        if (annexB) {
            var position = 0;
            var start = -1;
            while (position + 2 < data.length) {
                if (data[position] == 0 && data[position + 1] == 0 && data[position + 2] == 1) {
                    if (start >= 0) {
                        units.add(java.util.Arrays.copyOfRange(data, start, position));
                    }
                    position += 3;
                    start = position;
                } else {
                    position++;
                }
            }
            if (start >= 0) {
                units.add(java.util.Arrays.copyOfRange(data, start, data.length));
            }
        } else {
            var position = 0;
            while (position + 4 <= data.length) {
                var length = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                        | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
                position += 4;
                if (length <= 0 || position + length > data.length) {
                    break;
                }
                units.add(java.util.Arrays.copyOfRange(data, position, position + length));
                position += length;
            }
        }
        var result = new ArrayList<String>();
        for (var unit : units) {
            var end = unit.length;
            while (end > 0 && unit[end - 1] == 0) {
                end--;
            }
            if (end == 0) {
                continue;
            }
            var type = codec == Codec.H264 ? unit[0] & 0x1F : (unit[0] >> 1) & 0x3F;
            var delimiter = codec == Codec.H264 ? type == 9 || type == 12 : type == 35 || type == 38;
            if (!delimiter) {
                result.add(java.util.HexFormat.of().formatHex(unit, 0, end));
            }
        }
        return result;
    }

    /**
     * Demuxes a file whose presentation ffmpeg builds otherwise (an edit list it expands into repeated samples):
     * every track holds the samples of the file's sample table, as many as ffprobe's {@code nb_frames}, and every
     * packet ffprobe returns is one of them.
     *
     * @param vector  the vector name
     * @param factory the demuxer
     * @return what the demuxer read
     */
    public static ContainerProbe.Demuxed assertSamples(String vector, Function<MediaSource, Demuxer> factory) {
        var file = Conformance.vector(vector);
        var probe = ContainerProbe.probe(file);
        var ours = ContainerProbe.demux(file, factory);
        assertEquals(probe.streams().size(), ours.tracks().size(), vector + " track count");
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = probe.streams().get(index);
            var packets = ours.packetsOf(track);
            var frames = stream.get("nb_frames");
            if (frames != null && frames.matches("\\d+")) {
                assertEquals(Integer.parseInt(frames), packets.size(), vector + " track " + index + " samples");
            }
            var hashes = new java.util.HashSet<String>();
            for (var packet : packets) {
                hashes.add(ContainerProbe.crc32(packet.data()));
            }
            for (var packet : probe.packetsOf(stream.index())) {
                assertTrue(hashes.contains(packet.hash()), vector + " track " + index + ": ffprobe's packet of "
                        + packet.size() + " bytes at " + packet.pts() + " is not among ours");
            }
        }
        return ours;
    }

    /**
     * Reads every packet of a damaged or hostile file and fails on anything but a {@link MediaException}.
     *
     * @param vector  the vector name
     * @param factory the demuxer
     * @return the packets read, or {@code -1} when the demuxer rejected the file
     */
    public static int assertRobust(String vector, Function<MediaSource, Demuxer> factory) {
        try (var source = MediaSource.of(Conformance.vector(vector)); var demuxer = factory.apply(source)) {
            var count = 0;
            while (demuxer.read() != null) {
                count++;
            }
            return count;
        } catch (MediaException expected) {
            return -1;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError unexpected) {
            return fail(vector + ": " + unexpected, unexpected);
        }
    }

    /**
     * Runs ffprobe for the side data of every stream of a file, as {@code type=field=value} keys per stream index.
     *
     * @param file the file
     * @return the side data fields, by stream index
     */
    public static Map<Integer, Map<String, String>> sideData(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "stream=index:stream_side_data", "-of", "flat", file.toString())),
                StandardCharsets.UTF_8);
        var result = new LinkedHashMap<Integer, Map<String, String>>();
        for (var line : output.split("\r?\n")) {
            // streams.stream.0.side_data_list.side_data.0.side_data_type="Spherical Mapping"
            var match = java.util.regex.Pattern.compile("streams\\.stream\\.(\\d+)\\.side_data_list\\.side_data\\.(\\d+)\\.(\\w+)=(.*)")
                    .matcher(line);
            if (match.matches()) {
                var value = match.group(4);
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.computeIfAbsent(Integer.parseInt(match.group(1)), key -> new LinkedHashMap<>())
                        .put(match.group(2) + "." + match.group(3), value);
            }
        }
        return result;
    }

    /**
     * Returns the side data of one type of a stream from {@link #sideData(Path)}, by field.
     *
     * @param sideData the side data of a stream
     * @param type     the side data type, for example {@code "Spherical Mapping"}
     * @return the fields, empty when the stream has no such side data
     */
    public static Map<String, String> sideData(Map<String, String> sideData, String type) {
        var result = new LinkedHashMap<String, String>();
        if (sideData == null) {
            return result;
        }
        var prefixes = new ArrayList<String>();
        for (var entry : sideData.entrySet()) {
            if (entry.getKey().endsWith(".side_data_type") && entry.getValue().equals(type)) {
                prefixes.add(entry.getKey().substring(0, entry.getKey().indexOf('.') + 1));
            }
        }
        for (var entry : sideData.entrySet()) {
            for (var prefix : prefixes) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey().substring(prefix.length()), entry.getValue());
                }
            }
        }
        return result;
    }

    /**
     * What {@link #assertMatchesFfprobe(Path, Function, Options)} compares.
     *
     * @param codecs          whether the codec names are compared
     * @param keyframes       whether the key frame flags are compared
     * @param timestamps      whether the presentation timestamps are compared
     * @param ptsTolerance    the largest difference of a relative presentation timestamp, in the stream's ticks
     * @param extraLeading    the packets ours may have before ffprobe's first
     * @param extraTrailing   the packets ours may have after ffprobe's last
     * @param missingTrailing the packets of ffprobe's ours may lack at the end (of a cut file)
     * @param cut             whether the file is cut: the demuxer may report its end as malformed
     * @param byPid           whether ffprobe's streams are matched to the tracks by PID (a transport stream)
     * @param probeLeading    the packets ffprobe may have before ours' first
     * @param ffprobeOptions  ffprobe's input options
     */
    public record Options(boolean codecs, boolean keyframes, boolean timestamps, long ptsTolerance, int extraLeading,
                          int extraTrailing, int missingTrailing, boolean cut, boolean byPid, int probeLeading,
                          List<String> ffprobeOptions) {
        /** Everything compared, exactly. */
        public static final Options DEFAULT = new Options(true, true, true, 0, 0, 0, 0, false, false, 0, List.of());

        /**
         * Returns these options for a transport stream: the tracks are matched to ffprobe's streams by PID, ffprobe
         * may return the packets before a stream's first random access point (and a partial first PES), which ours
         * drops, and ours may lack the incomplete last PES.
         *
         * @return the options
         */
        public Options transportStream() {
            return new Options(codecs, keyframes, timestamps, ptsTolerance, extraLeading, extraTrailing,
                    Math.max(1, missingTrailing), cut, true, Math.max(STREAM_START, probeLeading), ffprobeOptions);
        }

        /**
         * Returns these options for a file cut short (the "-part" samples of FATE): the demuxer may stop with a
         * {@link MediaException.Malformed} at the cut, and ffmpeg may return the incomplete last packet of each
         * stream that ours lacks.
         *
         * @return the options
         */
        public Options cutShort() {
            return new Options(codecs, keyframes, timestamps, ptsTolerance, extraLeading, extraTrailing,
                    Math.max(1, missingTrailing), true, byPid, probeLeading, ffprobeOptions);
        }

        /** {@return these options without the key frame flags} */
        public Options withoutKeyframes() {
            return new Options(codecs, false, timestamps, ptsTolerance, extraLeading, extraTrailing, missingTrailing,
                    cut, byPid, probeLeading, ffprobeOptions);
        }

        /** {@return these options without the codec names} */
        public Options withoutCodecs() {
            return new Options(false, keyframes, timestamps, ptsTolerance, extraLeading, extraTrailing, missingTrailing,
                    cut, byPid, probeLeading, ffprobeOptions);
        }

        /** {@return these options without the timestamps} */
        public Options withoutTimestamps() {
            return new Options(codecs, keyframes, false, ptsTolerance, extraLeading, extraTrailing, missingTrailing,
                    cut, byPid, probeLeading, ffprobeOptions);
        }

        /**
         * Returns these options with a presentation timestamp tolerance.
         *
         * @param ticks the tolerance, in the stream's ticks
         * @return the options
         */
        public Options ptsTolerance(long ticks) {
            return new Options(codecs, keyframes, timestamps, ticks, extraLeading, extraTrailing, missingTrailing,
                    cut, byPid, probeLeading, ffprobeOptions);
        }

        /**
         * Returns these options with packets ours may have at the ends, or lack at the end.
         *
         * @param leading  the packets ours may have before ffprobe's first
         * @param trailing the packets ours may have after ffprobe's last
         * @param missing  the packets ours may lack at the end
         * @return the options
         */
        public Options ends(int leading, int trailing, int missing) {
            return new Options(codecs, keyframes, timestamps, ptsTolerance, leading, trailing, missing, cut,
                    byPid, probeLeading, ffprobeOptions);
        }
    }
}
