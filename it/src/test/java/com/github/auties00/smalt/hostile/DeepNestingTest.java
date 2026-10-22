package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReader;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.be32;
import static com.github.auties00.smalt.hostile.Hostile.indexOf;
import static com.github.auties00.smalt.hostile.Hostile.le32;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Inputs nested tens of thousands of levels deep: XML in XMP packets, subtitle markup, Matroska tags and chapters,
 * MP4 user data and metadata boxes, RIFF lists, TIFF sub directories, HEIF derived images and ID3 tables of contents.
 * Every entry point runs on a thread with a quarter of the JVM's default stack, so a parser whose recursion follows
 * the input overflows it (and reports the input as nesting too deeply) where a bounded or iterative one finishes.
 */
@DisplayName("Deep nesting")
class DeepNestingTest {
    private static final int DEPTH = 20_000;

    record Input(String name, Supplier<byte[]> data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Input> images() {
        return Stream.of(
                new Input("PNG with an XMP packet nested " + 100_000 + " elements deep", () -> pngXmp(100_000)),
                new Input("JPEG with an XMP packet nested 6000 elements deep", () -> jpegXmp(6000)),
                new Input("JPEG with an XMP structure nested 3000 values deep", DeepNestingTest::jpegXmpStructure),
                new Input("TIFF whose sub directories nest 2000 deep", () -> tiffChain(2000, false)),
                new Input("DNG whose sub directories nest 2000 deep", () -> tiffChain(2000, true)),
                new Input("HEIF whose grids derive from grids 300 deep", () -> heifChain(300)));
    }

    static Stream<Input> containers() {
        return Stream.of(
                new Input("Matroska simple tags nested " + DEPTH + " deep", () -> matroska(true)),
                new Input("Matroska chapter atoms nested " + DEPTH + " deep", () -> matroska(false)),
                new Input("MP4 user data boxes nested " + DEPTH + " deep", () -> mp4("udta", false)),
                new Input("MP4 metadata boxes nested " + DEPTH + " deep", () -> mp4("meta", true)),
                new Input("AVI lists nested " + DEPTH + " deep", DeepNestingTest::avi),
                new Input("MP3 whose ID3 tables of contents nest 2000 deep", () -> id3Chain(2000)));
    }

    @Nested
    @DisplayName("an image")
    class ImageFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.DeepNestingTest#images")
        @DisplayName("is read by every entry point on a small stack")
        void bounded(Input input) {
            assertEquals(List.of(), EntryPoints.image(input.name(), input.data().get(), LIMITS));
        }
    }

    @Nested
    @DisplayName("a container")
    class ContainerFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.DeepNestingTest#containers")
        @DisplayName("is read by every entry point on a small stack")
        void bounded(Input input) {
            assertEquals(List.of(), EntryPoints.container(input.name(), input.data().get(), LIMITS));
        }
    }

    @Nested
    @DisplayName("subtitle markup")
    class Markup {
        @Test
        @DisplayName("nested 100000 tags deep is read and converted on a small stack")
        void nestedTags() {
            var open = "<b>".repeat(50_000) + "<i>".repeat(50_000);
            var text = open + "deep" + "</i>".repeat(50_000) + "</b>".repeat(50_000);
            var file = ("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n" + text + "\n").getBytes(StandardCharsets.UTF_8);
            var thrown = Hostile.onSmallStack("WebVTT", () -> {
                try (var reader = SubtitleReader.open(MediaSource.of(file))) {
                    var cue = reader.read();
                    for (var from : SubtitleMarkup.values()) {
                        Subtitles.plainText(cue.text(), from);
                        for (var to : SubtitleMarkup.values()) {
                            Subtitles.convertText(cue.text(), from, to);
                        }
                    }
                }
            });
            if (thrown instanceof MediaException media) {
                Hostile.assertNoHiddenFailure("WebVTT", media);
            } else if (thrown != null) {
                throw new AssertionError("WebVTT threw " + thrown, thrown);
            }
        }
    }

    @Nested
    @DisplayName("an XMP packet")
    class Xmp {
        @Test
        @DisplayName("nested 100000 elements deep is rejected by the depth check, not by running out of stack")
        void depthChecked() {
            var thrown = Hostile.onSmallStack("XMP", () -> com.github.auties00.smalt.metadata.XmpPacket.parse(xml(100_000)));
            if (!(thrown instanceof MediaException.Malformed malformed)) {
                throw new AssertionError("expected Malformed, got " + thrown, thrown);
            }
            Hostile.assertNoHiddenFailure("XMP", malformed);
        }
    }

    static byte[] xml(int depth) {
        var builder = new StringBuilder("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\">");
        builder.append("<a>".repeat(depth)).append("</a>".repeat(depth));
        builder.append("</rdf:Description></rdf:RDF></x:xmpmeta>");
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] pngXmp(int depth) {
        var png = ImageBombTest.encode(ImageFormat.PNG, false);
        var payload = new ByteArrayOutputStream();
        payload.writeBytes("XML:com.adobe.xmp".getBytes(StandardCharsets.ISO_8859_1));
        payload.writeBytes(new byte[]{0, 0, 0, 0, 0});
        payload.writeBytes(xml(depth));
        var body = payload.toByteArray();
        var chunk = new byte[12 + body.length];
        be32(chunk, 0, body.length);
        chunk[4] = 'i';
        chunk[5] = 'T';
        chunk[6] = 'X';
        chunk[7] = 't';
        System.arraycopy(body, 0, chunk, 8, body.length);
        be32(chunk, 8 + body.length, Hostile.crc32(chunk, 4, 4 + body.length));
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33);
        out.writeBytes(chunk);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }

    static byte[] jpegXmp(int depth) {
        return jpegWithXmp(xml(depth));
    }

    static byte[] jpegXmpStructure() {
        var builder = new StringBuilder("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
                + "xmlns:d=\"http://purl.org/dc/elements/1.1/\"><d:subject>");
        builder.append("<rdf:Bag><rdf:li>".repeat(1500)).append("x").append("</rdf:li></rdf:Bag>".repeat(1500));
        builder.append("</d:subject></rdf:Description></rdf:RDF></x:xmpmeta>");
        return jpegWithXmp(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] jpegWithXmp(byte[] xml) {
        var jpeg = ImageBombTest.encode(ImageFormat.JPEG, false);
        var header = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
        var length = 2 + header.length + xml.length;
        if (length > 0xFFFF) {
            throw new AssertionError("XMP segment of " + length + " bytes");
        }
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length);
        out.writeBytes(header);
        out.writeBytes(xml);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static byte[] tiffChain(int depth, boolean dng) {
        var base = CyclicOffsetTest.tiff(0, 1, 0, 0, dng);
        var count = Hostile.le16(base, 8);
        var ifdLength = 2 + 12 * count + 4;
        var data = new byte[base.length + depth * ifdLength];
        System.arraycopy(base, 0, data, 0, base.length);
        var previous = 8;
        for (var level = 0; level < depth; level++) {
            var at = base.length + level * ifdLength;
            System.arraycopy(base, 8, data, at, ifdLength);
            for (var index = 0; index < count; index++) {
                var position = 8 + 2 + 12 * index;
                if (Hostile.le16(base, position) == 330) {
                    le32(data, previous + 2 + 12 * index + 8, at);
                    le32(data, at + 2 + 12 * index + 8, level == depth - 1 ? 0 : 1);
                }
            }
            previous = at;
        }
        return data;
    }

    static byte[] heifChain(int depth) {
        var stream = ForgedHeaders.hevc(64, 64);
        var builder = new HeifBuilder().brands("heic", "mif1", "heic");
        var gridData = new byte[]{0, 0, 0, 0, 0, 64, 0, 64};
        var items = new ArrayList<Integer>();
        for (var level = 0; level < depth; level++) {
            items.add(builder.item("grid", gridData, HeifBuilder.plain(HeifBuilder.ispe(64, 64))));
        }
        var tile = builder.item("hvc1", stream.packet(),
                HeifBuilder.essential(HeifBuilder.box("hvcC", stream.config())),
                HeifBuilder.plain(HeifBuilder.ispe(64, 64)));
        items.add(tile);
        for (var level = 0; level < depth; level++) {
            builder.reference("dimg", items.get(level), items.get(level + 1));
            if (level > 0) {
                builder.hidden(items.get(level));
            }
        }
        builder.hidden(tile);
        return builder.primary(items.getFirst()).build();
    }

    /**
     * Writes elements nested {@code depth} deep, each made of an identifier, an eight-byte size, a fixed prefix and
     * the next level, with {@code innermost} inside the deepest.
     */
    static void nestEbml(ByteArrayOutputStream out, byte[] id, byte[] prefix, int depth, byte[] innermost) {
        var sizes = new long[depth];
        var inner = (long) innermost.length;
        for (var level = depth - 1; level >= 0; level--) {
            sizes[level] = prefix.length + inner;
            inner = id.length + 8 + sizes[level];
        }
        for (var level = 0; level < depth; level++) {
            out.writeBytes(id);
            out.write(0x01);
            for (var shift = 48; shift >= 0; shift -= 8) {
                out.write((int) (sizes[level] >>> shift));
            }
            out.writeBytes(prefix);
        }
        out.writeBytes(innermost);
    }

    static byte[] ebml(byte[] id, byte[] payload) {
        var out = new ByteArrayOutputStream();
        nestEbml(out, id, new byte[0], 1, payload);
        return out.toByteArray();
    }

    static byte[] matroska(boolean tags) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0xA3,
                0x42, (byte) 0x86, (byte) 0x81, 1, 0x42, (byte) 0xF7, (byte) 0x81, 1, 0x42, (byte) 0xF2, (byte) 0x81, 4,
                0x42, (byte) 0xF3, (byte) 0x81, 8, 0x42, (byte) 0x82, (byte) 0x88, 'm', 'a', 't', 'r', 'o', 's', 'k',
                'a', 0x42, (byte) 0x87, (byte) 0x81, 4, 0x42, (byte) 0x85, (byte) 0x81, 2});
        var segment = new ByteArrayOutputStream();
        segment.writeBytes(new byte[]{0x15, 0x49, (byte) 0xA9, 0x66, (byte) 0x8B, 0x2A, (byte) 0xD7, (byte) 0xB1,
                (byte) 0x83, 0x0F, 0x42, 0x40, 0x4D, (byte) 0x80, (byte) 0x81, 'x'});
        var audio = Hostile.concat(new byte[]{(byte) 0xB5, (byte) 0x84, 0x45, (byte) 0xFA, 0, 0},
                new byte[]{(byte) 0x9F, (byte) 0x81, 1}, new byte[]{0x62, 0x64, (byte) 0x81, 16});
        var entry = Hostile.concat(new byte[]{(byte) 0xD7, (byte) 0x81, 1, 0x73, (byte) 0xC5, (byte) 0x81, 1,
                (byte) 0x83, (byte) 0x81, 2, (byte) 0x86, (byte) 0x8D},
                "A_PCM/INT/LIT".getBytes(StandardCharsets.ISO_8859_1), ebml(new byte[]{(byte) 0xE1}, audio));
        segment.writeBytes(ebml(new byte[]{0x16, 0x54, (byte) 0xAE, 0x6B}, ebml(new byte[]{(byte) 0xAE}, entry)));
        var nested = new ByteArrayOutputStream();
        if (tags) {
            var simple = Hostile.concat(new byte[]{0x45, (byte) 0xA3, (byte) 0x81, 'a'},
                    new byte[]{0x44, (byte) 0x87, (byte) 0x81, 'b'});
            nestEbml(nested, new byte[]{0x67, (byte) 0xC8}, simple, DEPTH, new byte[0]);
            var tag = Hostile.concat(new byte[]{0x63, (byte) 0xC0, (byte) 0x80}, nested.toByteArray());
            segment.writeBytes(ebml(new byte[]{0x12, 0x54, (byte) 0xC3, 0x67}, ebml(new byte[]{0x73, 0x73}, tag)));
        } else {
            var atom = new byte[]{0x73, (byte) 0xC4, (byte) 0x81, 1, (byte) 0x91, (byte) 0x81, 0};
            nestEbml(nested, new byte[]{(byte) 0xB6}, atom, DEPTH, new byte[0]);
            segment.writeBytes(ebml(new byte[]{0x10, 0x43, (byte) 0xA7, 0x70},
                    ebml(new byte[]{0x45, (byte) 0xB9}, nested.toByteArray())));
        }
        var block = new byte[]{(byte) 0x81, 0, 0, (byte) 0x80, 1, 2, 3, 4};
        segment.writeBytes(ebml(new byte[]{0x1F, 0x43, (byte) 0xB6, 0x75}, Hostile.concat(
                new byte[]{(byte) 0xE7, (byte) 0x81, 0}, ebml(new byte[]{(byte) 0xA3}, block))));
        out.writeBytes(ebml(new byte[]{0x18, 0x53, (byte) 0x80, 0x67}, segment.toByteArray()));
        return out.toByteArray();
    }

    static byte[] mp4(String type, boolean fullBox) {
        var file = ContainerBombTest.pcm(ContainerFormat.MP4, Codec.PCM_S16LE);
        var moov = indexOf(file, "moov", 0) - 4;
        var moovSize = (int) Hostile.be32(file, moov);
        var chain = new ByteArrayOutputStream();
        var prefix = fullBox ? new byte[4] : new byte[0];
        var sizes = new long[DEPTH];
        var inner = 0L;
        for (var level = DEPTH - 1; level >= 0; level--) {
            sizes[level] = 8 + prefix.length + inner;
            inner = sizes[level];
        }
        for (var level = 0; level < DEPTH; level++) {
            var header = new byte[8];
            be32(header, 0, sizes[level]);
            System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, header, 4, 4);
            chain.writeBytes(header);
            chain.writeBytes(prefix);
        }
        var deep = chain.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(file, 0, moov + moovSize);
        out.writeBytes(deep);
        out.write(file, moov + moovSize, file.length - moov - moovSize);
        var data = out.toByteArray();
        be32(data, moov, moovSize + deep.length);
        var stco = indexOf(data, "stco", moov);
        if (stco > 0 && indexOf(data, "mdat", 0) > moov) {
            var entries = (int) Hostile.be32(data, stco + 8);
            for (var index = 0; index < entries; index++) {
                var at = stco + 12 + 4 * index;
                be32(data, at, Hostile.be32(data, at) + deep.length);
            }
        }
        return data;
    }

    static byte[] avi() {
        var file = ContainerBombTest.pcm(ContainerFormat.AVI, Codec.PCM_S16LE);
        var chain = new ByteArrayOutputStream();
        var sizes = new long[DEPTH];
        var inner = 0L;
        for (var level = DEPTH - 1; level >= 0; level--) {
            sizes[level] = 4 + inner;
            inner = 8 + sizes[level];
        }
        for (var level = 0; level < DEPTH; level++) {
            var header = new byte[]{'L', 'I', 'S', 'T', 0, 0, 0, 0, 'n', 'e', 's', 't'};
            le32(header, 4, sizes[level]);
            chain.writeBytes(header);
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(file);
        out.writeBytes(chain.toByteArray());
        var data = out.toByteArray();
        le32(data, 4, data.length - 8);
        return data;
    }

    static byte[] id3Chain(int depth) {
        var mp3 = ContainerBombTest.encoded(ContainerFormat.MPEG_AUDIO, Codec.MP3, ChannelLayout.STEREO, 44_100);
        var frames = new ByteArrayOutputStream();
        for (var level = 0; level < depth; level++) {
            var payload = new ByteArrayOutputStream();
            payload.writeBytes(("t" + level + "\0").getBytes(StandardCharsets.ISO_8859_1));
            payload.write(level == 0 ? 3 : 1);
            payload.write(1);
            payload.writeBytes(((level == depth - 1 ? "c" : "t" + (level + 1)) + "\0")
                    .getBytes(StandardCharsets.ISO_8859_1));
            frames.writeBytes(id3Frame("CTOC", payload.toByteArray()));
        }
        var chap = new ByteArrayOutputStream();
        chap.writeBytes("c\0".getBytes(StandardCharsets.ISO_8859_1));
        chap.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 100, -1, -1, -1, -1, -1, -1, -1, -1});
        frames.writeBytes(id3Frame("CHAP", chap.toByteArray()));
        var body = frames.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0});
        out.writeBytes(syncsafe(body.length));
        out.writeBytes(body);
        out.writeBytes(mp3);
        return out.toByteArray();
    }

    private static byte[] id3Frame(String id, byte[] payload) {
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
}
