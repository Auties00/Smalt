package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.ftyp;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.hdlr;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mdhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.tkhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u8;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.visualEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the scalable and multiview H.264 storage of ISO/IEC 14496-15: {@code svcC} and {@code mvcC}
 * configurations merged into the track configuration, layered tracks that reference the tracks they extend
 * through {@code tref/scal}, implicit reconstruction ({@code mvc1}, {@code svc1}), extractors and aggregators
 * ({@code svc2}, {@code mvc2}, {@code avc2}), from seekable and forward-only sources. The files are built
 * byte by byte with sample {@code i} of the base track holding a prefix NAL unit and a slice whose payload
 * bytes are {@code i}, interleaved with the samples of the layered tracks in decoding order.
 */
@DisplayName("MP4 scalable and multiview H.264 tracks")
class Mp4LayeredTrackTest {
    private static final byte[] SPS = {0x67, 66, 0, 30, 1};

    private static final byte[] PPS = {0x68, 0x40};

    private static final byte[] SUBSET_SPS = {0x6F, 83, 0, 30, 2, 3};

    private static final byte[] SUBSET_PPS = {0x68, 0x41, 0x11};

    private static final int SAMPLES = 4;

    /** A length-prefixed NAL unit. */
    static byte[] unit(int... bytes) {
        var out = new byte[4 + bytes.length];
        System.arraycopy(u32(bytes.length), 0, out, 0, 4);
        for (var index = 0; index < bytes.length; index++) {
            out[4 + index] = (byte) bytes[index];
        }
        return out;
    }

    /** The base layer sample {@code i}: a prefix NAL unit and a slice. */
    static byte[] baseSample(int i) {
        return concat(unit(0x6E, 0x40, 0, 0x08), unit(0x21, i, i, i, 0x80));
    }

    /** The NAL unit of the enhancement layer of sample {@code i}. */
    static byte[] enhancementUnit(int i) {
        return unit(0x74, 0x80, 0x10, 0x08, i, 0x40 + i);
    }

    /** An extractor of a track reference, a sample offset, a data offset and a data length. */
    static byte[] extractor(int reference, int sampleOffset, long dataOffset, long dataLength) {
        return unit(concatInts(new int[]{0x7F, 0x80, 0x10, 0x08, reference, sampleOffset & 0xFF},
                bytes(u32(dataOffset)), bytes(u32(dataLength))));
    }

    static int[] bytes(byte[] values) {
        var out = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = values[index] & 0xFF;
        }
        return out;
    }

    static int[] concatInts(int[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            for (var value : part) {
                out.write(value);
            }
        }
        return bytes(out.toByteArray());
    }

    static byte[] avcC() {
        return box("avcC", u8(1), u8(66), u8(0), u8(30), u8(0xFF), u8(0xE1), u8(0), u8(SPS.length), SPS, u8(1), u8(0),
                u8(PPS.length), PPS);
    }

    /** An {@code svcC} or {@code mvcC} record, whose 7-bit count leaves the top bit of its byte clear. */
    static byte[] layerConfig(String type) {
        return box(type, u8(1), u8(83), u8(0), u8(30), u8(0xFF), u8(1), u8(0), u8(SUBSET_SPS.length), SUBSET_SPS, u8(1),
                u8(0), u8(SUBSET_PPS.length), SUBSET_PPS);
    }

    /** A track whose samples sit at the given offsets, one per chunk, referencing tracks through scal. */
    static byte[] track(int id, byte[] entry, List<byte[]> samples, List<Long> offsets, int... references) {
        var sizes = samples.stream().mapToInt(sample -> sample.length).toArray();
        var chunkOffsets = offsets.stream().mapToLong(Long::longValue).toArray();
        var tref = references.length == 0 ? new byte[0] : box("tref", box("scal", concat(java.util.Arrays.stream(
                references).mapToObj(reference -> u32(reference)).toArray(byte[][]::new))));
        return box("trak", tkhd(id, 64, 48, new int[]{0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000}), tref,
                box("mdia", mdhd(1000, samples.size() * 40L), hdlr("vide"), box("minf", full("vmhd", 0, 1,
                        new byte[8]), box("stbl", stsd(entry), table("stts", 0, 2, samples.size(), 40),
                        table("stsc", 0, 3, 1, 1, 1), stsz(0, sizes), table("stco", 0, 1, chunkOffsets)))));
    }

    /**
     * Builds a file of a base track and layered tracks, their samples interleaved in decoding order (base
     * first), each layered track given by its sample entry, its samples and its references.
     */
    static byte[] layeredFile(List<byte[]> entries, List<List<byte[]>> layers, List<int[]> references) {
        var tracks = new ArrayList<List<byte[]>>();
        var base = new ArrayList<byte[]>();
        for (var i = 0; i < SAMPLES; i++) {
            base.add(baseSample(i));
        }
        tracks.add(base);
        tracks.addAll(layers);
        var offsets = new ArrayList<List<Long>>();
        for (var ignored : tracks) {
            offsets.add(new ArrayList<>());
        }
        var mdat = new ByteArrayOutputStream();
        var start = 20 + 8;
        for (var i = 0; i < SAMPLES; i++) {
            for (var t = 0; t < tracks.size(); t++) {
                offsets.get(t).add((long) start + mdat.size());
                mdat.writeBytes(tracks.get(t).get(i));
            }
        }
        var moov = new ByteArrayOutputStream();
        moov.writeBytes(mvhd(1000, SAMPLES * 40L));
        moov.writeBytes(track(1, visualEntry("avc1", 64, 48, avcC()), base, offsets.getFirst()));
        for (var t = 0; t < layers.size(); t++) {
            moov.writeBytes(track(t + 2, entries.get(t), layers.get(t), offsets.get(t + 1), references.get(t)));
        }
        return concat(ftyp(), box("mdat", mdat.toByteArray()), box("moov", moov.toByteArray()));
    }

    static List<byte[]> packets(byte[] file, int trackId, boolean forwardOnly) throws Exception {
        var result = new ArrayList<byte[]>();
        try (var stream = new ByteArrayInputStream(file);
             var source = forwardOnly ? MediaSource.of(stream, file.length) : MediaSource.of(file);
             var demuxer = new Mp4Demuxer(source)) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == trackId) {
                    result.add(packet.data());
                }
            }
        }
        return result;
    }

    static Track track(byte[] file, int trackId) throws Exception {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            return demuxer.tracks().stream().filter(track -> track.id() == trackId).findFirst().orElseThrow();
        }
    }

    static void assertPackets(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(HexFormat.of().formatHex(expected.get(index)), HexFormat.of().formatHex(actual.get(index)),
                    "packet " + index);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"svcC", "mvcC"})
        @DisplayName("merges the parameter sets of an svcC or mvcC box into those of the avcC box of the same entry")
        void sameEntry(String type) throws Exception {
            var entry = visualEntry("avc1", 64, 48, avcC(), layerConfig(type));
            var file = concat(ftyp(), box("mdat", baseSample(0)), box("moov", mvhd(1000, 40),
                    track(1, entry, List.of(baseSample(0)), List.of(28L))));
            var config = track(file, 1).codecConfig().orElseThrow();
            var parsed = NalRecords.parseAvcStrict(config);
            assertEquals(4, parsed.lengthSize());
            assertEquals(List.of(HexFormat.of().formatHex(SPS), HexFormat.of().formatHex(SUBSET_SPS),
                    HexFormat.of().formatHex(PPS), HexFormat.of().formatHex(SUBSET_PPS)),
                    parsed.parameterSets().stream().map(HexFormat.of()::formatHex).toList());
            assertEquals(66, config[1]);
            assertEquals(2, config[5] & 0x1F, "two sequence parameter sets");
        }

        @Test
        @DisplayName("gives a layered track the parameter sets of the tracks it extends, each once")
        void layeredTrack() throws Exception {
            var layer = new ArrayList<byte[]>();
            for (var i = 0; i < SAMPLES; i++) {
                layer.add(enhancementUnit(i));
            }
            var file = layeredFile(List.of(visualEntry("mvc1", 64, 48, layerConfig("mvcC"))), List.of(layer),
                    List.of(new int[]{1}));
            var track = track(file, 2);
            assertEquals(Codec.H264, track.codec());
            var parsed = NalRecords.parseAvcStrict(track.codecConfig().orElseThrow());
            assertEquals(List.of(HexFormat.of().formatHex(SPS), HexFormat.of().formatHex(SUBSET_SPS),
                    HexFormat.of().formatHex(PPS), HexFormat.of().formatHex(SUBSET_PPS)),
                    parsed.parameterSets().stream().map(HexFormat.of()::formatHex).toList());
            var avcC = avcC();
            assertArrayEquals(java.util.Arrays.copyOfRange(avcC, 8, avcC.length), track(file, 1).codecConfig()
                    .orElseThrow(), "the base track keeps its avcC");
        }
    }

    @Nested
    @DisplayName("reconstruction")
    class Reconstruction {
        @ParameterizedTest(name = "forward-only: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("prefixes the samples of an mvc1 track with the time-aligned samples of the track it extends")
        void implicit(boolean forwardOnly) throws Exception {
            var layer = new ArrayList<byte[]>();
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < SAMPLES; i++) {
                layer.add(enhancementUnit(i));
                expected.add(concat(baseSample(i), enhancementUnit(i)));
            }
            var file = layeredFile(List.of(visualEntry("mvc1", 64, 48, layerConfig("mvcC"))), List.of(layer),
                    List.of(new int[]{1}));
            assertPackets(expected, packets(file, 2, forwardOnly));
            var base = packets(file, 1, forwardOnly);
            for (var i = 0; i < SAMPLES; i++) {
                assertArrayEquals(baseSample(i), base.get(i), "the base track is unchanged");
            }
        }

        @ParameterizedTest(name = "forward-only: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("replaces the extractors of an svc2 track by the bytes they name in the tracks it references")
        void extractors(boolean forwardOnly) throws Exception {
            var layer = new ArrayList<byte[]>();
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < SAMPLES; i++) {
                layer.add(concat(extractor(1, 0, 0, 0), enhancementUnit(i)));
                expected.add(concat(baseSample(i), enhancementUnit(i)));
            }
            var file = layeredFile(List.of(visualEntry("svc2", 64, 48, layerConfig("svcC"))), List.of(layer),
                    List.of(new int[]{1}));
            assertPackets(expected, packets(file, 2, forwardOnly));
        }

        @Test
        @DisplayName("takes a range of a referenced sample, at a sample offset, and through two levels of tracks")
        void ranges() throws Exception {
            var first = new ArrayList<byte[]>();
            var second = new ArrayList<byte[]>();
            var expectedFirst = new ArrayList<byte[]>();
            var expectedSecond = new ArrayList<byte[]>();
            var prefix = unit(0x6E, 0x40, 0, 0x08).length;
            for (var i = 0; i < SAMPLES; i++) {
                // the slice of the previous sample (the first sample has none before it), then the layer's own unit
                first.add(concat(extractor(1, -1, prefix, 9), enhancementUnit(i)));
                expectedFirst.add(i == 0 ? enhancementUnit(i) : concat(unit(0x21, i - 1, i - 1, i - 1, 0x80),
                        enhancementUnit(i)));
                // the base sample, then the unit of the first layer behind its extractor, then its own
                var extractorSize = extractor(1, 0, 0, 0).length;
                second.add(concat(extractor(1, 0, 0, 0), extractor(2, 0, extractorSize, 0),
                        unit(0x74, 0x80, 0x20, 0x08, i)));
                expectedSecond.add(concat(baseSample(i), enhancementUnit(i), unit(0x74, 0x80, 0x20, 0x08, i)));
            }
            var entry = visualEntry("svc2", 64, 48, layerConfig("svcC"));
            var file = layeredFile(List.of(entry, entry), List.of(first, second), List.of(new int[]{1},
                    new int[]{1, 2}));
            assertPackets(expectedFirst, packets(file, 2, false));
            assertPackets(expectedSecond, packets(file, 3, false));
        }

        @Test
        @DisplayName("unwraps aggregators, resolving the extractors they hold")
        void aggregators() throws Exception {
            var layer = new ArrayList<byte[]>();
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < SAMPLES; i++) {
                var inner = concat(extractor(1, 0, 0, 0), enhancementUnit(i));
                var aggregator = concat(u32(4 + inner.length), new byte[]{0x7E, (byte) 0x80, 0x10, 0x08}, inner);
                layer.add(aggregator);
                expected.add(concat(baseSample(i), enhancementUnit(i)));
            }
            var file = layeredFile(List.of(visualEntry("avc2", 64, 48, avcC(), layerConfig("svcC"))),
                    List.of(layer), List.of(new int[]{1}));
            assertPackets(expected, packets(file, 2, false));
        }

        @Test
        @DisplayName("leaves out extractors that name no track, no sample or bytes past the sample")
        void brokenExtractors() throws Exception {
            var layer = new ArrayList<byte[]>();
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < SAMPLES; i++) {
                layer.add(concat(extractor(3, 0, 0, 0), extractor(1, 10, 0, 0), extractor(1, 0, 1000, 4),
                        extractor(0, 0, 0, 0), enhancementUnit(i)));
                expected.add(enhancementUnit(i));
            }
            var file = layeredFile(List.of(visualEntry("svc2", 64, 48, layerConfig("svcC"))), List.of(layer),
                    List.of(new int[]{1}));
            assertPackets(expected, packets(file, 2, false));
        }
    }
}
