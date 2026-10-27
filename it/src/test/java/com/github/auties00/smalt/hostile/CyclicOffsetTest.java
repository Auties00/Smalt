package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.property.Streams;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.indexOf;
import static com.github.auties00.smalt.hostile.Hostile.le16;
import static com.github.auties00.smalt.hostile.Hostile.le32;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Offsets and references that form cycles: TIFF and EXIF directories chained to themselves or to each other, sub
 * directories pointing back at their parent, HEIF derived images that use themselves, a Matroska seek head pointing
 * at itself, a Matroska ordered edition that replays one span thousands of times, ID3 chapter tables that contain
 * themselves and OS/2 bitmap arrays whose entries link back. Every entry
 * point must finish, within its deadline and on a quarter of the default thread stack, with a result or a
 * {@code MediaException}.
 */
@DisplayName("Cyclic offsets")
class CyclicOffsetTest {
    record Input(String name, Supplier<byte[]> data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Input> images() {
        return Stream.of(
                new Input("TIFF whose first directory is its own successor", () -> tiff(8, 0, 0, 0, true)),
                new Input("TIFF of two directories that chain to each other", CyclicOffsetTest::tiffPair),
                new Input("TIFF whose SubIFDs entry points at its own directory", () -> tiff(0, 8, 0, 0, false)),
                new Input("TIFF whose EXIF directory is its first directory", () -> tiff(0, 0, 8, 0, false)),
                new Input("TIFF whose EXIF and GPS directories point at each other", CyclicOffsetTest::exifGps),
                new Input("JPEG whose EXIF directories chain to themselves", CyclicOffsetTest::jpegExif),
                new Input("DNG whose SubIFDs entry points at its own directory", () -> tiff(0, 8, 0, 0, true)),
                new Input("HEIF grid that is its own tile", () -> heifGrid(true)),
                new Input("HEIF grids that are each other's tile", () -> heifGrid(false)),
                new Input("HEIF image that is its own thumbnail and alpha", CyclicOffsetTest::heifSelfReferences),
                new Input("AVIF identity derivations that are each other's input", () -> avifDerived("iden")),
                new Input("AVIF overlays that use themselves and each other", () -> avifDerived("iovl")),
                new Input("AVIF sample transforms preferred to their own input", () -> avifDerived("sato")),
                new Input("OS/2 bitmap array whose last entry is its own successor", () -> bitmapArray(true)),
                new Input("OS/2 bitmap array whose third entry links back to the second", () -> bitmapArray(false)),
                new Input("JPX composition repeating forever on the layer it reserves for itself",
                        CyclicOffsetTest::jpxLoop),
                new Input("JPX cross-reference box whose fragment is itself", CyclicOffsetTest::jpxSelfReference));
    }

    static Stream<Input> containers() {
        return Stream.of(
                new Input("Matroska seek head pointing at itself", CyclicOffsetTest::matroskaSeekHead),
                new Input("Matroska ordered edition replaying one span in 4096 chapters",
                        CyclicOffsetTest::matroskaReplays),
                new Input("MP3 whose ID3 table of contents contains itself", () -> id3Toc(true)),
                new Input("MP3 whose ID3 tables of contents contain each other", () -> id3Toc(false)));
    }

    @Nested
    @DisplayName("an image")
    class ImageFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.CyclicOffsetTest#images")
        @DisplayName("is read by every entry point in bounded time, stack and memory")
        void terminates(Input input) {
            assertEquals(List.of(), EntryPoints.image(input.name(), input.data().get(), LIMITS));
        }
    }

    @Nested
    @DisplayName("a container")
    class ContainerFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.CyclicOffsetTest#containers")
        @DisplayName("is read by every entry point in bounded time, stack and memory")
        void terminates(Input input) {
            assertEquals(List.of(), EntryPoints.container(input.name(), input.data().get(), LIMITS));
        }
    }

    @Nested
    @DisplayName("the cyclic inputs stay readable")
    class Readable {
        @org.junit.jupiter.api.Test
        @DisplayName("a TIFF directory chained to itself is one page, and a pair chained to each other two")
        void tiffPages() {
            var options = com.github.auties00.smalt.image.ImageDecoderOptions.builder().limits(LIMITS).build();
            try (var pages = com.github.auties00.smalt.image.Images.openPages(
                    com.github.auties00.smalt.io.MediaSource.of(tiff(8, 0, 0, 0, false)), options)) {
                assertEquals(1, pages.pageCount());
                assertEquals(2, pages.read(0).picture().width());
            }
            try (var pages = com.github.auties00.smalt.image.Images.openPages(
                    com.github.auties00.smalt.io.MediaSource.of(tiffPair()), options)) {
                assertEquals(2, pages.pageCount());
            }
        }

        @org.junit.jupiter.api.Test
        @DisplayName("a Matroska file whose seek head points at itself still yields every packet")
        void matroska() {
            var data = matroskaSeekHead();
            try (var demuxer = Containers.openDemuxer(com.github.auties00.smalt.io.MediaSource.of(data))) {
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(Streams.audio(Codec.PCM_S16LE, ChannelLayout.STEREO, 8000, 4000, builder -> builder)
                        .packets().size(), count);
            }
        }

        @org.junit.jupiter.api.Test
        @DisplayName("an MP3 whose ID3 table of contents contains itself still yields its audio")
        void id3() {
            try (var demuxer = Containers.openDemuxer(com.github.auties00.smalt.io.MediaSource.of(id3Toc(true)))) {
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                org.junit.jupiter.api.Assertions.assertTrue(count > 10, count + " packets");
            }
        }
    }

    /**
     * Builds a little-endian TIFF of one 2x2 gray directory at offset 8.
     *
     * @param next the next directory offset
     * @param subIfd the SubIFDs pointer, or 0 for none
     * @param exif the EXIF directory pointer, or 0 for none
     * @param gps the GPS directory pointer, or 0 for none
     * @param dng whether to tag the file as a DNG with a CFA directory
     */
    static byte[] tiff(long next, long subIfd, long exif, long gps, boolean dng) {
        var entries = new ArrayList<long[]>();
        entries.add(new long[]{254, 4, 1, 0});
        entries.add(new long[]{256, 3, 1, 2});
        entries.add(new long[]{257, 3, 1, 2});
        entries.add(new long[]{258, 3, 1, dng ? 16 : 8});
        entries.add(new long[]{259, 3, 1, 1});
        entries.add(new long[]{262, 3, 1, dng ? 32803 : 1});
        entries.add(new long[]{273, 4, 1, -1});
        entries.add(new long[]{277, 3, 1, 1});
        entries.add(new long[]{278, 3, 1, 2});
        entries.add(new long[]{279, 4, 1, dng ? 8 : 4});
        if (subIfd != 0) {
            entries.add(new long[]{330, 4, 1, subIfd});
        }
        if (dng) {
            entries.add(new long[]{33421, 3, 2, 2 | 2L << 16});
            entries.add(new long[]{33422, 1, 4, 0x02010100L});
        }
        if (exif != 0) {
            entries.add(new long[]{34665, 4, 1, exif});
        }
        if (gps != 0) {
            entries.add(new long[]{34853, 4, 1, gps});
        }
        if (dng) {
            entries.add(new long[]{50706, 1, 4, 0x00000401L});
        }
        var ifdLength = 2 + 12 * entries.size() + 4;
        var pixels = 8 + ifdLength;
        var data = new byte[pixels + 8];
        data[0] = 'I';
        data[1] = 'I';
        data[2] = 42;
        le32(data, 4, 8);
        writeIfd(data, 8, entries, pixels, next);
        for (var index = 0; index < 8; index++) {
            data[pixels + index] = (byte) (index * 40);
        }
        return data;
    }

    private static void writeIfd(byte[] data, int at, List<long[]> entries, long pixels, long next) {
        le16(data, at, entries.size());
        for (var index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var position = at + 2 + 12 * index;
            le16(data, position, (int) entry[0]);
            le16(data, position + 2, (int) entry[1]);
            le32(data, position + 4, entry[2]);
            var value = entry[3] == -1 ? pixels : entry[3];
            if (entry[1] == 3 && entry[2] == 1) {
                le16(data, position + 8, (int) value);
            } else {
                le32(data, position + 8, value);
            }
        }
        le32(data, at + 2 + 12 * entries.size(), next);
    }

    static byte[] tiffPair() {
        var single = tiff(0, 0, 0, 0, false);
        var count = le16(single, 8);
        var ifdLength = 2 + 12 * count + 4;
        var second = 8 + ifdLength + 8;
        var data = new byte[second + ifdLength];
        System.arraycopy(single, 0, data, 0, single.length);
        System.arraycopy(single, 8, data, second, ifdLength);
        le32(data, 8 + 2 + 12 * count, second);
        le32(data, second + 2 + 12 * count, 8);
        return data;
    }

    static byte[] exifGps() {
        var base = tiff(0, 0, 1, 1, false);
        var count = le16(base, 8);
        var exifAt = base.length;
        var gpsAt = exifAt + 18;
        var data = new byte[gpsAt + 18];
        System.arraycopy(base, 0, data, 0, base.length);
        for (var index = 0; index < count; index++) {
            var position = 8 + 2 + 12 * index;
            var tag = le16(data, position);
            if (tag == 34665) {
                le32(data, position + 8, exifAt);
            } else if (tag == 34853) {
                le32(data, position + 8, gpsAt);
            }
        }
        le16(data, exifAt, 1);
        le16(data, exifAt + 2, 34853);
        le16(data, exifAt + 4, 4);
        le32(data, exifAt + 6, 1);
        le32(data, exifAt + 10, gpsAt);
        le32(data, exifAt + 14, exifAt);
        le16(data, gpsAt, 1);
        le16(data, gpsAt + 2, 34665);
        le16(data, gpsAt + 4, 4);
        le32(data, gpsAt + 6, 1);
        le32(data, gpsAt + 10, exifAt);
        le32(data, gpsAt + 14, gpsAt);
        return data;
    }

    static byte[] jpegExif() {
        var jpeg = ImageBombTest.encode(com.github.auties00.smalt.image.ImageFormat.JPEG, false);
        var exif = new byte[8 + 2 + 12 * 3 + 4];
        exif[0] = 'I';
        exif[1] = 'I';
        exif[2] = 42;
        le32(exif, 4, 8);
        writeIfd(exif, 8, List.of(new long[]{274, 3, 1, 6}, new long[]{34665, 4, 1, 8}, new long[]{34853, 4, 1, 8}),
                0, 8);
        var segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE1);
        var length = 2 + 6 + exif.length;
        segment.write(length >> 8);
        segment.write(length);
        segment.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        segment.writeBytes(exif);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(segment.toByteArray());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] jp2Box(String type, byte[]... contents) {
        var body = new ByteArrayOutputStream();
        for (var content : contents) {
            body.writeBytes(content);
        }
        var header = new byte[8];
        Hostile.be32(header, 0, 8 + body.size());
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, header, 4, 4);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static byte[] jpxLoop() {
        var file = ImageBombTest.encode(com.github.auties00.smalt.image.ImageFormat.JP2, false);
        var options = new byte[9];
        Hostile.be32(options, 0, 16);
        Hostile.be32(options, 4, 16);
        options[8] = (byte) 255;
        // Ityp 9 (XO, YO, LIFE, N): repeat 65535 (forever), one tick of 1 ms; the instruction persists, lives one
        // tick and reserves its layer for the next instruction, which is itself on the next repetition
        var instructions = new byte[8 + 16];
        Hostile.be16(instructions, 0, 9);
        Hostile.be16(instructions, 2, 65535);
        Hostile.be32(instructions, 4, 1);
        Hostile.be32(instructions, 16, 0x80000001L);
        Hostile.be32(instructions, 20, 1);
        return Hostile.concat(file, jp2Box("comp", jp2Box("copt", options), jp2Box("inst", instructions)));
    }

    static byte[] jpxSelfReference() {
        var file = ImageBombTest.encode(com.github.auties00.smalt.image.ImageFormat.JP2, false);
        // jpch (8) -> cref (8 + 4) -> flst (8 + 2 + 14): the fragment covers the cross-reference box itself
        var list = new byte[2 + 14];
        Hostile.be16(list, 0, 1);
        Hostile.be32(list, 6, file.length + 8);
        Hostile.be32(list, 10, 8 + 4 + 8 + list.length);
        var reference = jp2Box("cref", "pclr".getBytes(StandardCharsets.ISO_8859_1), jp2Box("flst", list));
        return Hostile.concat(file, jp2Box("jpch", reference));
    }

    static byte[] heifGrid(boolean self) {
        var builder = new HeifBuilder().brands("heic", "mif1", "heic");
        var gridData = new byte[]{0, 0, 0, 0, 0, 32, 0, 32};
        var first = builder.item("grid", gridData, HeifBuilder.plain(HeifBuilder.ispe(32, 32)));
        if (self) {
            builder.reference("dimg", first, first);
        } else {
            var second = builder.item("grid", gridData, HeifBuilder.plain(HeifBuilder.ispe(32, 32)));
            builder.reference("dimg", first, second);
            builder.reference("dimg", second, first);
        }
        return builder.primary(first).build();
    }

    static byte[] avifDerived(String type) {
        var builder = new HeifBuilder();
        var single = type.equals("iden");
        var data = switch (type) {
            case "iovl" -> HeifBuilder.overlay(false, new int[4], 32, 32, new int[]{0, 0}, new int[]{0, 0});
            case "sato" -> HeifBuilder.sato(2, 1, 2, 128);
            default -> new byte[0];
        };
        var first = builder.idatItem(type, data, HeifBuilder.plain(HeifBuilder.ispe(32, 32)),
                HeifBuilder.plain(HeifBuilder.pixi(16)));
        var second = builder.idatItem(type, data, HeifBuilder.plain(HeifBuilder.ispe(32, 32)),
                HeifBuilder.plain(HeifBuilder.pixi(16)));
        if (single) {
            builder.reference("dimg", first, second).reference("dimg", second, first);
        } else {
            builder.reference("dimg", first, second, first).reference("dimg", second, first, second);
        }
        if (!type.equals("sato")) {
            builder.reference("thmb", second, first);
        }
        builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, second, first)));
        return builder.primary(first).build();
    }

    static byte[] heifSelfReferences() {
        var stream = ForgedHeaders.hevc(64, 64);
        var builder = new HeifBuilder().brands("heic", "mif1", "heic");
        var item = builder.item("hvc1", stream.packet(),
                HeifBuilder.essential(HeifBuilder.box("hvcC", stream.config())),
                HeifBuilder.plain(HeifBuilder.ispe(64, 64)), HeifBuilder.plain(HeifBuilder.alpha()));
        builder.reference("thmb", item, item);
        builder.reference("auxl", item, item);
        return builder.primary(item).build();
    }

    static byte[] matroskaSeekHead() {
        var stream = Streams.audio(Codec.PCM_S16LE, ChannelLayout.STEREO, 8000, 4000, builder -> builder);
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(ContainerFormat.MATROSKA, sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var data = sink.toByteArray();
        var segment = indexOf(data, new byte[]{0x18, 0x53, (byte) 0x80, 0x67}, 0);
        var sizeLength = Integer.numberOfLeadingZeros(data[segment + 4] & 0xFF) - 23;
        var segmentData = segment + 4 + sizeLength;
        var seekHead = indexOf(data, new byte[]{0x11, 0x4D, (byte) 0x9B, 0x74}, segment);
        if (seekHead < 0) {
            throw new AssertionError("the muxer wrote no seek head");
        }
        var position = seekHead;
        var patched = 0;
        while ((position = indexOf(data, new byte[]{0x53, (byte) 0xAC}, position + 1)) > 0 && position < seekHead + 256) {
            var width = (data[position + 2] & 0xFF) & 0x7F;
            var value = seekHead - segmentData;
            for (var index = 0; index < width; index++) {
                data[position + 3 + index] = (byte) (value >>> (8 * (width - 1 - index)));
            }
            patched++;
        }
        if (patched == 0) {
            throw new AssertionError("the seek head has no seek position");
        }
        return data;
    }

    static byte[] matroskaReplays() {
        var stream = Streams.audio(Codec.PCM_S16LE, ChannelLayout.STEREO, 8000, 4000, builder -> builder);
        var chapters = new ArrayList<com.github.auties00.smalt.container.Chapter>();
        for (var index = 0; index < 4096; index++) {
            chapters.add(com.github.auties00.smalt.container.Chapter.of(400_000, 410_000, null));
        }
        var tags = com.github.auties00.smalt.metadata.MediaTags.builder().editions(List.of(
                new com.github.auties00.smalt.container.ChapterEdition(chapters, true, true, List.of()))).build();
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(ContainerFormat.MATROSKA, sink)) {
            muxer.addTrack(stream.track());
            muxer.setTags(tags);
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static byte[] id3Toc(boolean self) {
        var mp3 = ContainerBombTest.encoded(ContainerFormat.MPEG_AUDIO, Codec.MP3, ChannelLayout.STEREO, 44_100);
        var frames = new ByteArrayOutputStream();
        frames.writeBytes(ctoc("toc", true, self ? "toc" : "sub", "ch0"));
        if (!self) {
            frames.writeBytes(ctoc("sub", false, "toc", "ch0"));
        }
        frames.writeBytes(chap("ch0", 0, 200));
        var body = frames.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0});
        out.writeBytes(syncsafe(body.length));
        out.writeBytes(body);
        out.writeBytes(mp3);
        return out.toByteArray();
    }

    private static byte[] ctoc(String id, boolean top, String... children) {
        var payload = new ByteArrayOutputStream();
        payload.writeBytes((id + "\0").getBytes(StandardCharsets.ISO_8859_1));
        payload.write(top ? 3 : 1);
        payload.write(children.length);
        for (var child : children) {
            payload.writeBytes((child + "\0").getBytes(StandardCharsets.ISO_8859_1));
        }
        return frame("CTOC", payload.toByteArray());
    }

    private static byte[] chap(String id, int start, int end) {
        var payload = new ByteArrayOutputStream();
        payload.writeBytes((id + "\0").getBytes(StandardCharsets.ISO_8859_1));
        for (var value : new long[]{start, end, 0xFFFF_FFFFL, 0xFFFF_FFFFL}) {
            payload.write((int) (value >>> 24));
            payload.write((int) (value >>> 16));
            payload.write((int) (value >>> 8));
            payload.write((int) value);
        }
        return frame("CHAP", payload.toByteArray());
    }

    private static byte[] frame(String id, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(syncsafe(payload.length));
        out.write(0);
        out.write(0);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] syncsafe(int value) {
        return new byte[]{(byte) (value >> 21 & 0x7F), (byte) (value >> 14 & 0x7F), (byte) (value >> 7 & 0x7F),
                (byte) (value & 0x7F)};
    }

    /** Three 1x1 bitmaps in an OS/2 bitmap array whose last entry links to itself or back to the second. */
    static byte[] bitmapArray(boolean self) {
        var entry = 14 + 14 + 40 + 4;
        var data = new byte[entry * 3];
        for (var i = 0; i < 3; i++) {
            var at = i * entry;
            data[at] = 'B';
            data[at + 1] = 'A';
            le32(data, at + 2, 14 + 14 + 40);
            le32(data, at + 6, i < 2 ? (i + 1) * entry : self ? 2 * entry : entry);
            data[at + 14] = 'B';
            data[at + 15] = 'M';
            le32(data, at + 16, 14 + 40 + 4);
            le32(data, at + 24, at + 14 + 14 + 40);
            le32(data, at + 28, 40);
            le32(data, at + 32, 1);
            le32(data, at + 36, 1);
            le16(data, at + 40, 1);
            le16(data, at + 42, 24);
        }
        return data;
    }
}
