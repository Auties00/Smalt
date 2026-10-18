package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the metadata the probe reads where ffmpeg cannot write a fixture: Vorbis comment chapters
 * and pictures in Ogg, and the normalization of tag names.
 */
@DisplayName("Probed metadata")
class MetadataProbeTest {
    /** Builds a FLAC picture block, the form METADATA_BLOCK_PICTURE base64-encodes. */
    static byte[] pictureBlock(int type, String mime, String description, byte[] data) {
        var mimeBytes = mime.getBytes(StandardCharsets.US_ASCII);
        var text = description.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(32 + mimeBytes.length + text.length + data.length);
        buffer.putInt(type).putInt(mimeBytes.length).put(mimeBytes).putInt(text.length).put(text);
        buffer.putInt(64).putInt(48).putInt(24).putInt(0).putInt(data.length).put(data);
        return buffer.array();
    }

    @Test
    @DisplayName("reads Ogg Vorbis comment chapters and pictures and keeps them out of the tags")
    void oggComments() {
        var cover = Fixtures.read(Fixtures.testImage("png", 64, 48, List.of()));
        var source = Fixtures.read(Fixtures.tone("opus", 440, 48_000, 1, 3, List.of("-c:a", "libopus", "-f", "ogg")));
        var comments = new LinkedHashMap<String, String>();
        comments.put("TITLE", "Chaptered");
        comments.put("ARTIST", "Somebody");
        comments.put("CHAPTER001", "00:00:00.000");
        comments.put("CHAPTER001NAME", "Opening");
        comments.put("CHAPTER002", "00:00:01.250");
        comments.put("CHAPTER002NAME", "Second");
        comments.put("METADATA_BLOCK_PICTURE", Base64.getEncoder().encodeToString(pictureBlock(3, "image/png", "front", cover)));
        var output = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(source));
             var muxer = new OggMuxer(output, OggMuxerOptions.builder().comments(comments).build())) {
            demuxer.tracks().forEach(muxer::addTrack);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var info = Media.probe(MediaSource.of(output.toByteArray()));
        assertEquals("Chaptered", info.tags().get("title"));
        assertEquals("Somebody", info.tags().get("artist"));
        assertFalse(info.tags().containsKey("CHAPTER001"));
        assertFalse(info.tags().containsKey("METADATA_BLOCK_PICTURE"));
        assertEquals(List.of(Chapter.of(0, 1_250_000, "Opening"), Chapter.of(1_250_000, info.durationMicros().orElseThrow(),
                "Second")), info.chapters());
        assertEquals(1, info.attachedPictures().size());
        var picture = info.attachedPictures().getFirst();
        assertEquals(AttachedPicture.Type.FRONT_COVER, picture.type());
        assertEquals("image/png", picture.mediaType());
        assertEquals("front", picture.description());
        assertEquals(ImageFormat.PNG, picture.format().orElseThrow());
        assertArrayEquals(cover, picture.data());
    }

    @Test
    @DisplayName("describes a Minolta MRW file from its picture description and TIFF blocks")
    void minoltaRaw() {
        var make = "KONICA MINOLTA\0".getBytes(StandardCharsets.US_ASCII);
        var model = "DYNAX 7D\0".getBytes(StandardCharsets.US_ASCII);
        var values = 8 + 2 + 2 * 12 + 4;
        var tiff = ByteBuffer.allocate(values + make.length + model.length)
                .put(new byte[]{'M', 'M', 0, 42}).putInt(8).putShort((short) 2)
                .putShort((short) 271).putShort((short) 2).putInt(make.length).putInt(values)
                .putShort((short) 272).putShort((short) 2).putInt(model.length).putInt(values + make.length)
                .putInt(0).put(make).put(model).array();
        var prd = ByteBuffer.allocate(24).put("21810002".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 6).putShort((short) 16).putShort((short) 4).putShort((short) 8)
                .put((byte) 12).put((byte) 12).put((byte) 0x59).put(new byte[4]).put((byte) 1).array();
        var header = ByteBuffer.allocate(8 + prd.length + 8 + tiff.length)
                .put(new byte[]{0, 'P', 'R', 'D'}).putInt(prd.length).put(prd)
                .put(new byte[]{0, 'T', 'T', 'W'}).putInt(tiff.length).put(tiff).array();
        var file = ByteBuffer.allocate(8 + header.length + 16 * 6 * 3 / 2)
                .put(new byte[]{0, 'M', 'R', 'M'}).putInt(header.length).put(header).array();
        var info = Media.probe(MediaSource.of(file));
        assertEquals(ImageFormat.RAW, info.imageFormat().orElseThrow());
        assertEquals("KONICA MINOLTA", info.tags().get("make"));
        assertEquals("DYNAX 7D", info.tags().get("model"));
        var image = info.image().orElseThrow();
        assertEquals(8, image.width());
        assertEquals(4, image.height());
        assertEquals(12, image.bitDepth().orElseThrow());
    }

    @Test
    @DisplayName("describes a Sigma X3F file from the EXIF block of its largest preview and its header")
    void sigmaRaw() {
        var little = java.nio.ByteOrder.LITTLE_ENDIAN;
        var make = "SIGMA\0".getBytes(StandardCharsets.US_ASCII);
        var model = "SIGMA DP1\0".getBytes(StandardCharsets.US_ASCII);
        var values = 8 + 2 + 2 * 12 + 4;
        var tiff = ByteBuffer.allocate(values + make.length + model.length)
                .put(new byte[]{'M', 'M', 0, 42}).putInt(8).putShort((short) 2)
                .putShort((short) 271).putShort((short) 2).putInt(make.length).putInt(values)
                .putShort((short) 272).putShort((short) 2).putInt(model.length).putInt(values + make.length)
                .putInt(0).put(make).put(model).array();
        var jpeg = ByteBuffer.allocate(2 + 10 + tiff.length + 2).put(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                (byte) 0xE1}).putShort((short) (8 + tiff.length)).put("Exif\0\0".getBytes(StandardCharsets.US_ASCII))
                .put(tiff).put(new byte[]{(byte) 0xFF, (byte) 0xD9}).array();
        var section = ByteBuffer.allocate(28 + jpeg.length + 3 & ~3).order(little).put("SECi".getBytes(StandardCharsets.US_ASCII))
                .putInt(0x20000).putInt(2).putInt(18).putInt(8).putInt(4).putInt(0).put(jpeg).array();
        var header = ByteBuffer.allocate(232).order(little).put("FOVb".getBytes(StandardCharsets.US_ASCII)).putInt(0x20002)
                .put(new byte[20]).putInt(8).putInt(4).putInt(0).array();
        var directory = header.length + section.length;
        var file = ByteBuffer.allocate(directory + 12 + 12 + 4).order(little).put(header).put(section)
                .put("SECd".getBytes(StandardCharsets.US_ASCII)).putInt(0x20000).putInt(1)
                .putInt(header.length).putInt(section.length).put("IMA2".getBytes(StandardCharsets.US_ASCII))
                .putInt(directory).array();
        var info = Media.probe(MediaSource.of(file));
        assertEquals(ImageFormat.RAW, info.imageFormat().orElseThrow());
        assertEquals("SIGMA", info.tags().get("make"));
        assertEquals("SIGMA DP1", info.tags().get("model"));
        var image = info.image().orElseThrow();
        assertEquals(8, image.width());
        assertEquals(4, image.height());
    }

    @Test
    @DisplayName("describes a Canon CRW file from the records of its CIFF heap")
    void canonRaw() {
        var little = java.nio.ByteOrder.LITTLE_ENDIAN;
        var records = new java.io.ByteArrayOutputStream();
        var entries = ByteBuffer.allocate(2 + 7 * 10).order(little).putShort((short) 7);
        var names = "Canon\0Canon EOS 10D\0".getBytes(StandardCharsets.US_ASCII);
        var info = ByteBuffer.allocate(34).order(little).putShort((short) 34).putShort((short) 3152)
                .putShort((short) 2068).putInt(0).putShort((short) 72).putShort((short) 16).putShort((short) 3143)
                .putShort((short) 2063).array();
        var shot = ByteBuffer.allocate(50).order(little).putShort((short) 50).putShort((short) 0).putShort((short) 192)
                .putShort((short) 0).putShort((short) 159).putShort((short) 192).array();
        var spec = ByteBuffer.allocate(28).order(little).putInt(3072).putInt(2048).putFloat(1).putInt(270).array();
        var time = ByteBuffer.allocate(12).order(little).putInt(1_000_000_000).array();
        var focal = ByteBuffer.allocate(8).order(little).putShort((short) 0).putShort((short) 24).array();
        var sample = ByteBuffer.allocate(14).order(little).putShort((short) 14).putInt(0).putInt(0).putShort((short) 12)
                .array();
        var values = new byte[][]{names, info, shot, spec, time, focal, sample};
        var kinds = new int[]{0x080A, 0x1031, 0x102A, 0x1810, 0x180E, 0x1029, 0x1030};
        for (var index = 0; index < values.length; index++) {
            entries.putShort((short) kinds[index]).putInt(values[index].length).putInt(records.size());
            records.writeBytes(values[index]);
        }
        var heap = ByteBuffer.allocate(records.size() + entries.capacity() + 4).order(little)
                .put(records.toByteArray()).put(entries.array()).putInt(records.size()).array();
        var root = ByteBuffer.allocate(heap.length + 12 + 4).order(little).put(heap).putShort((short) 1)
                .putShort((short) 0x300A).putInt(heap.length).putInt(0).putInt(heap.length).array();
        var file = ByteBuffer.allocate(26 + root.length).order(little).put((byte) 'I').put((byte) 'I').putInt(26)
                .put("HEAPCCDR".getBytes(StandardCharsets.US_ASCII)).putInt(0x00010002).putLong(0).put(root).array();
        var probed = Media.probe(MediaSource.of(file));
        assertEquals(ImageFormat.RAW, probed.imageFormat().orElseThrow());
        assertEquals("Canon", probed.tags().get("make"));
        assertEquals("Canon EOS 10D", probed.tags().get("model"));
        assertEquals("200", probed.tags().get("iso"));
        assertEquals("1/64", probed.tags().get("exposure_time"));
        assertEquals("5.6", probed.tags().get("f_number"));
        assertEquals("24", probed.tags().get("focal_length"));
        assertEquals("2001-09-09T01:46:40", probed.tags().get("creation_time"));
        var image = probed.image().orElseThrow();
        assertEquals(3072, image.width());
        assertEquals(2048, image.height());
        assertEquals(12, image.bitDepth().orElseThrow());
    }

    @Test
    @DisplayName("normalizes the common tag names of every container")
    void tagNames() {
        var tags = new LinkedHashMap<String, String>();
        tags.put("TIT2", "a");
        tags.put("INAM", "b");
        tags.put("ALBUMARTIST", "c");
        tags.put("TRACKNUMBER", "4");
        tags.put("TXXX:MOOD", "calm");
        tags.put("custom", "kept");
        assertEquals(Map.of("title", "a", "album_artist", "c", "track", "4", "MOOD", "calm", "custom", "kept"),
                TagNames.normalize(tags));
    }
}
