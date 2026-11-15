package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.aiff.AiffMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the XMP packets {@link TagEditor} reads and writes in every container that stores one (XMP Specification
 * Part 3): a {@code uuid} box or {@code udta/XMP_} atom in MP4 and MOV, a {@code _PMX} chunk in WAV, an {@code APPL}
 * chunk in AIFF and a {@code PRIV} frame in ID3v2 tags, with ExifTool as the independent reader and the muxers as a
 * second writer. The audio and video data must be unchanged (ffmpeg's packet hashes).
 */
@DisplayName("XMP in audio and video files")
class XmpTagsTest {
    /** A container under test: its fixture, the place the packet goes and a muxer that writes one. */
    record Case(String name, Path file, String marker, Function<MediaSink, Muxer> muxer) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> containers() {
        return Stream.of(
                new Case("mp4", TagFixtures.video("mp4", List.of("-c:v", "mpeg4", "-c:a", "aac")), "uuid", null),
                new Case("mov", TagFixtures.video("mov", List.of("-c:v", "mpeg4", "-c:a", "aac")), "XMP_", null),
                new Case("m4a", TagFixtures.audio("m4a", List.of("-c:a", "aac")), "uuid", null),
                new Case("wav", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le")), "_PMX", WavMuxer::new),
                new Case("wav id3", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-write_id3v2", "1")), "_PMX",
                        null),
                new Case("aiff", TagFixtures.audio("aiff", List.of("-c:a", "pcm_s16be")), "APPL", AiffMuxer::new),
                new Case("mp3", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k")), "PRIV",
                        RawMuxer::new),
                new Case("mp3 id3v2.3", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k",
                        "-id3v2_version", "3")), "PRIV", null),
                new Case("adts", TagFixtures.audio("aac", List.of("-c:a", "aac", "-f", "adts", "-write_id3v2", "1")),
                        "PRIV", AdtsMuxer::new)
        ).map(Arguments::of);
    }

    static XmpPacket packet(String title) {
        return XmpPacket.empty().with(XmpPacket.DC, "title", title).with(XmpPacket.XMP, "CreatorTool", "Smalt")
                .with(XmpPacket.EXIF, "GPSLatitude", "45,27.852N").with(XmpPacket.PHOTOSHOP, "City", "Milano");
    }

    static boolean contains(byte[] data, String text) {
        return new String(data, StandardCharsets.ISO_8859_1).contains(text);
    }

    /**
     * Returns whether ExifTool reads a value in a file's XMP packet. ExifTool reads no XMP in AIFF files: it reports
     * the {@code APPL} chunk as application data, which must then be the {@code XMP } signature and the packet.
     */
    static boolean exiftoolReads(Path file, String value) {
        var exif = PhoneMetadataTest.exiftool(file);
        if (file.toString().endsWith(".aiff")) {
            return exif.entrySet().stream().anyMatch(entry -> entry.getKey().endsWith(":ApplicationData")
                    && entry.getValue().startsWith("XMP <?xpacket") && entry.getValue().contains(value));
        }
        return exif.containsValue(value);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("writes a packet that TagEditor and ExifTool read back, rewrites it unchanged and replaces it")
    void writes(Case container, @TempDir Path directory) throws IOException {
        var file = TagFixtures.copy(container.file(), directory);
        var packets = TagFixtures.packetHash(file);
        var tags = TagEditor.read(file).toBuilder().xmp(packet("First \u00E8")).build();
        TagEditor.write(file, tags);
        var data = Files.readAllBytes(file);
        assertTrue(contains(data, container.marker()), container.marker());
        var read = TagEditor.read(file);
        assertEquals(tags.xmp(), read.xmp());
        assertEquals(tags.get(StandardTag.TITLE), read.get(StandardTag.TITLE));
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, read));
        assertTrue(exiftoolReads(file, "First \u00E8"), PhoneMetadataTest.exiftool(file).toString());
        assertTrue(exiftoolReads(file, "Milano"));
        TagEditor.write(file, read.toBuilder().xmp(packet("Second")).build());
        var second = Files.readAllBytes(file);
        assertEquals(Optional.of("Second"), TagEditor.read(file).xmp().orElseThrow().text(XmpPacket.DC, "title"));
        assertFalse(contains(second, "First"), "the old packet is gone");
        assertTrue(exiftoolReads(file, "Second"));
        assertEquals(packets, TagFixtures.packetHash(file));
        Oracle.assertDecodesCleanly(file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("strips the place from the packet, keeps the rest, and removes the packet")
    void strips(Case container, @TempDir Path directory) throws IOException {
        var file = TagFixtures.copy(container.file(), directory);
        TagEditor.write(file, TagEditor.read(file).toBuilder().xmp(packet("Kept")).build());
        assertTrue(TagEditor.read(file).hasLocation());
        TagEditor.stripLocation(file);
        var read = TagEditor.read(file);
        assertFalse(read.hasLocation());
        assertEquals(Optional.of("Kept"), read.xmp().orElseThrow().text(XmpPacket.DC, "title"));
        var data = Files.readAllBytes(file);
        assertFalse(contains(data, "Milano") || contains(data, "45,27.852N"), "the place is left in the file");
        assertEquals(PhoneMetadataTest.exiftoolLocations(file), java.util.Map.of());
        TagEditor.write(file, read.toBuilder().noXmp().build());
        assertTrue(TagEditor.read(file).xmp().isEmpty());
        assertFalse(contains(Files.readAllBytes(file), "Kept"));
        Oracle.assertDecodesCleanly(file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("a muxer writes the packet where TagEditor and ExifTool find it")
    void muxes(Case container, @TempDir Path directory) throws IOException {
        if (container.muxer() == null) {
            return;
        }
        var extension = container.file().getFileName().toString();
        extension = extension.substring(extension.lastIndexOf('.') + 1);
        var input = MuxedTagsTest.untagged(extension, container.name().equals("aiff") ? List.of("-c:a", "pcm_s16be")
                : container.name().equals("wav") ? List.of("-c:a", "pcm_s16le") : container.name().equals("adts")
                ? List.of("-c:a", "aac", "-f", "adts") : List.of("-c:a", "libmp3lame", "-write_xing", "0"));
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Muxed").xmp(packet("Muxed packet")).build();
        var output = MuxedTagsTest.remux(input, container.muxer(), tags);
        assertTrue(contains(output, container.marker()), container.marker());
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals(tags.xmp(), read.xmp());
        assertArrayEquals(output, TagEditor.write(output, read, TagWriteOptions.defaults()));
        var file = directory.resolve("muxed." + extension);
        Files.write(file, output);
        assertTrue(exiftoolReads(file, "Muxed packet"));
        Oracle.assertDecodesCleanly(file);
    }


    /** Encodes an ID3v2.4 frame. */
    static byte[] frame(String id, byte[] content) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(syncsafe(content.length));
        out.writeBytes(new byte[2]);
        out.writeBytes(content);
        return out.toByteArray();
    }

    static byte[] syncsafe(int value) {
        return new byte[]{(byte) ((value >>> 21) & 0x7F), (byte) ((value >>> 14) & 0x7F), (byte) ((value >>> 7) & 0x7F),
                (byte) (value & 0x7F)};
    }

    /** Appends a chunk to a little-endian RIFF file and corrects its RIFF size. */
    static byte[] appendChunk(byte[] wav, String id, byte[] payload) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(wav);
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{(byte) payload.length, (byte) (payload.length >>> 8), (byte) (payload.length >>> 16),
                (byte) (payload.length >>> 24)});
        out.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            out.write(0);
        }
        var file = out.toByteArray();
        var riff = file.length - 8;
        file[4] = (byte) riff;
        file[5] = (byte) (riff >>> 8);
        file[6] = (byte) (riff >>> 16);
        file[7] = (byte) (riff >>> 24);
        return file;
    }

    @Test
    @DisplayName("reads a WAV packet kept only in a PRIV frame of the ID3v2 chunk and writes it back there")
    void wavId3(@TempDir Path directory) throws IOException {
        var priv = new java.io.ByteArrayOutputStream();
        priv.writeBytes("XMP\0".getBytes(StandardCharsets.US_ASCII));
        priv.writeBytes(packet("Id3").toBytes());
        var frames = new java.io.ByteArrayOutputStream();
        frames.writeBytes(frame("TIT2", "\u0003Id3 title".getBytes(StandardCharsets.UTF_8)));
        frames.writeBytes(frame("PRIV", priv.toByteArray()));
        var tag = new java.io.ByteArrayOutputStream();
        tag.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0});
        tag.writeBytes(syncsafe(frames.size()));
        tag.writeBytes(frames.toByteArray());
        var wav = appendChunk(Fixtures.read(TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le"))), "id3 ",
                tag.toByteArray());
        var file = directory.resolve("id3-xmp.wav");
        Files.write(file, wav);
        var read = TagEditor.read(file);
        assertEquals(Optional.of("Id3 title"), read.get(StandardTag.TITLE));
        assertEquals(Optional.of("Id3"), read.xmp().orElseThrow().text(XmpPacket.DC, "title"));
        assertTrue(PhoneMetadataTest.exiftool(file).containsValue("Id3"));
        TagEditor.write(file, read.toBuilder().xmp(packet("Moved")).build());
        var data = Files.readAllBytes(file);
        assertFalse(contains(data, "_PMX"), "a packet the file kept in its ID3v2 chunk stays there");
        assertFalse(contains(data, ">Id3<"), "the old packet is gone");
        assertEquals(Optional.of("Moved"), TagEditor.read(file).xmp().orElseThrow().text(XmpPacket.DC, "title"));
        assertTrue(PhoneMetadataTest.exiftool(file).containsValue("Moved"));
        TagEditor.write(file, TagEditor.read(file).toBuilder().noXmp().build());
        assertFalse(contains(Files.readAllBytes(file), "XMP\0"));
        Oracle.assertDecodesCleanly(file);
    }

    @Test
    @DisplayName("keeps a chunk that holds no XMP packet as an unknown chunk")
    void malformedChunk(@TempDir Path directory) throws IOException {
        var file = appendChunk(Fixtures.read(TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le"))), "_PMX",
                "not xml".getBytes(StandardCharsets.US_ASCII));
        var read = TagEditor.read(MediaSource.of(file));
        assertTrue(read.xmp().isEmpty());
        var written = TagEditor.write(file, read.toBuilder().set(StandardTag.TITLE, "edited").xmp(packet("New"))
                .build(), TagWriteOptions.defaults());
        assertTrue(contains(written, "not xml"), "the unknown chunk is kept");
        assertEquals(Optional.of("New"), TagEditor.read(MediaSource.of(written)).xmp().orElseThrow()
                .text(XmpPacket.DC, "title"));
        Files.write(directory.resolve("kept.wav"), written);
        Oracle.assertDecodesCleanly(directory.resolve("kept.wav"));
    }
}
