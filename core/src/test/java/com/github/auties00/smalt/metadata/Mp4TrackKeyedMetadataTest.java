package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Validators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the QuickTime keyed metadata of tracks ({@code trak/meta} and {@code trak/udta/meta} of handler
 * {@code mdta}), read into track tags and rewritten from them. The fixtures add the boxes to an ffmpeg movie whose
 * movie box is the last box, as a camera writes them: a QuickTime-form box (no version and flags) in the video
 * {@code trak} with text, integer and float items, and an ISO-form box in the audio track's {@code udta}.
 */
@DisplayName("MP4 track keyed metadata")
class Mp4TrackKeyedMetadataTest {
    private static final int UTF8 = 1;
    private static final int SIGNED = 21;
    private static final int FLOAT32 = 23;

    private static byte[] box(String type, byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        var payload = out.toByteArray();
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array();
    }

    private static byte[] data(int type, byte[] value) {
        return box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array(), value);
    }

    /** A keyed metadata box of keys and values: text, an {@link Integer} (32-bit signed) or a {@link Float}. */
    private static byte[] keyedMeta(boolean fullBox, Object... keysAndValues) {
        var keys = new ByteArrayOutputStream();
        var items = new ByteArrayOutputStream();
        for (var index = 0; index < keysAndValues.length; index += 2) {
            var key = ((String) keysAndValues[index]).getBytes(StandardCharsets.UTF_8);
            keys.writeBytes(ByteBuffer.allocate(8).putInt(8 + key.length).put("mdta".getBytes(StandardCharsets.US_ASCII))
                    .array());
            keys.writeBytes(key);
            var value = keysAndValues[index + 1];
            var atom = switch (value) {
                case Integer number -> data(SIGNED, ByteBuffer.allocate(4).putInt(number).array());
                case Float number -> data(FLOAT32, ByteBuffer.allocate(4).putFloat(number).array());
                default -> data(UTF8, value.toString().getBytes(StandardCharsets.UTF_8));
            };
            items.writeBytes(ByteBuffer.allocate(8).putInt(8 + atom.length).putInt(index / 2 + 1).array());
            items.writeBytes(atom);
        }
        var handler = box("hdlr", new byte[8], "mdta".getBytes(StandardCharsets.US_ASCII), new byte[13]);
        var keysBox = box("keys", ByteBuffer.allocate(8).putInt(0).putInt(keysAndValues.length / 2).array(),
                keys.toByteArray());
        return box("meta", fullBox ? new byte[4] : new byte[0], handler, keysBox, box("ilst", items.toByteArray()));
    }

    /** Appends boxes to the {@code trak} boxes of a movie whose movie box is the last box, in track order. */
    private static byte[] withTrackBoxes(byte[] file, byte[] videoTrak, byte[] audioUdta) {
        var top = Mp4Atoms.top(file);
        var moov = top.getLast();
        assertEquals("moov", moov.type(), "the movie box is the last box");
        var body = new ByteArrayOutputStream();
        var trak = 0;
        for (var child : Mp4Atoms.children(file, moov.payload(), moov.end())) {
            var bytes = Arrays.copyOfRange(file, child.offset(), child.end());
            if (child.type().equals("trak")) {
                var extra = trak == 0 ? videoTrak : audioUdta.length == 0 ? new byte[0] : box("udta", audioUdta);
                bytes = box("trak", Arrays.copyOfRange(bytes, 8, bytes.length), extra);
                trak++;
            }
            body.writeBytes(bytes);
        }
        var out = new ByteArrayOutputStream();
        out.write(file, 0, moov.offset());
        out.writeBytes(box("moov", body.toByteArray()));
        return out.toByteArray();
    }

    private static byte[] plain() {
        return Fixtures.read(Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                TagFixtures.AUDIO), List.of("-c:v", "mpeg4", "-c:a", "aac", "-shortest")));
    }

    private static byte[] fixture() {
        return withTrackBoxes(plain(), keyedMeta(false, "com.example.camera", "front", "com.example.exposure", -3,
                "com.example.gain", 1.5f, "com.example.lens", "wide"), keyedMeta(true, "com.example.microphone", "left"));
    }

    private static TrackTags track(MediaTags tags, int index) {
        return tags.tracks().get(index);
    }

    private static byte[] write(byte[] file, MediaTags tags) {
        var written = TagEditor.write(file, tags, TagWriteOptions.defaults());
        Validators.assertValid("track-keyed-" + Arrays.hashCode(written) + ".mov", written);
        assertEquals(MuxSupport.demux(file).order().size(), MuxSupport.demux(written).order().size());
        var before = MuxSupport.demux(file);
        var after = MuxSupport.demux(written);
        var map = new HashMap<Integer, Integer>();
        for (var index = 0; index < before.tracks().size(); index++) {
            map.put(before.tracks().get(index).id(), after.tracks().get(index).id());
        }
        MuxSupport.assertSamePayloads(before, after, map);
        return written;
    }

    @Test
    @DisplayName("reads the keyed metadata of the trak and of its udta into the track tags, numbers as decimal text")
    void reads() {
        var tags = TagEditor.read(MediaSource.of(fixture()));
        var video = track(tags, 0).tags();
        assertEquals(Optional.of("front"), video.get("com.example.camera"), video.toString());
        assertEquals(Optional.of("-3"), video.get("com.example.exposure"));
        assertEquals(Optional.of("1.5"), video.get("com.example.gain"));
        assertEquals(Optional.of("wide"), video.get("com.example.lens"));
        assertEquals(Optional.of("left"), track(tags, 1).tags().get("com.example.microphone"));
        assertTrue(tags.get("com.example.camera").isEmpty(), "track keys are not movie tags");
    }

    @Test
    @DisplayName("rewrites a changed key in the box that held it, keeping the data type of a number")
    void edits() {
        var file = fixture();
        var tags = TagEditor.read(MediaSource.of(file));
        var video = track(tags, 0);
        var edited = tags.toBuilder().track(video.withTags(video.tags().toBuilder().set("com.example.camera", "back")
                .set("com.example.exposure", "7").remove("com.example.lens").build())).build();
        var written = write(file, edited);
        var reread = TagEditor.read(MediaSource.of(written));
        var tracks = reread.tracks();
        assertEquals(Optional.of("back"), tracks.getFirst().tags().get("com.example.camera"));
        assertEquals(Optional.of("7"), tracks.getFirst().tags().get("com.example.exposure"));
        assertEquals(Optional.of("1.5"), tracks.getFirst().tags().get("com.example.gain"));
        assertTrue(tracks.getFirst().tags().get("com.example.lens").isEmpty());
        assertEquals(Optional.of("left"), tracks.get(1).tags().get("com.example.microphone"));
        var trak = Mp4Atoms.all(written, "moov/trak").getFirst();
        var meta = Mp4Atoms.all(written, trak, "meta");
        assertEquals(1, meta.size(), "the key stays in the trak's keyed metadata box");
        assertTrue(Mp4Atoms.all(written, trak, "udta/meta").isEmpty(), "no iTunes item takes the key");
        var text = new String(written, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("com.example.lens"), "the removed key is gone from the keys box");
        // the exposure keeps its 32-bit signed integer form
        var ilst = keyedChildren(written, trak).stream().filter(box -> box.type().equals("ilst")).findFirst().orElseThrow();
        var found = false;
        for (var item : Mp4Atoms.children(written, ilst.payload(), ilst.end())) {
            var atom = Mp4Atoms.children(written, item.payload(), item.end()).getFirst();
            if (Mp4Atoms.u32(written, atom.payload()) == SIGNED) {
                assertEquals(7, ByteBuffer.wrap(written, atom.payload() + 8, 4).getInt());
                found = true;
            }
        }
        assertTrue(found, "the integer item");
        // the audio track, whose tags do not change, is copied
        var audioBefore = Mp4Atoms.all(file, "moov/trak").get(1);
        var audioAfter = Mp4Atoms.all(written, "moov/trak").get(1);
        assertTrue(Arrays.equals(file, audioBefore.offset(), audioBefore.end(), written, audioAfter.offset(),
                audioAfter.end()));
    }

    @Test
    @DisplayName("stores a new reverse-DNS key of a track without keyed metadata in a new trak/meta box")
    void adds() {
        var file = plain();
        var tags = TagEditor.read(MediaSource.of(file));
        var video = track(tags, 0);
        var edited = tags.toBuilder().track(video.withTags(video.tags().toBuilder()
                .set("com.example.camera", "front").set("comment", "a note").build())).build();
        var written = write(file, edited);
        var reread = track(TagEditor.read(MediaSource.of(written)), 0).tags();
        assertEquals(Optional.of("front"), reread.get("com.example.camera"));
        assertEquals(Optional.of("a note"), reread.get("comment"));
        var trak = Mp4Atoms.all(written, "moov/trak").getFirst();
        var meta = Mp4Atoms.all(written, trak, "meta");
        assertEquals(1, meta.size());
        assertEquals("mdta", handler(written, trak));
        assertFalse(Mp4Atoms.all(written, trak, "udta/meta").isEmpty(), "the comment is an iTunes item");
    }

    /** Returns the children of the keyed metadata box of a trak, in QuickTime form (no version and flags) or not. */
    private static List<Mp4Atoms.Box> keyedChildren(byte[] file, Mp4Atoms.Box trak) {
        var meta = Mp4Atoms.children(file, trak.payload(), trak.end()).stream().filter(box -> box.type().equals("meta"))
                .findFirst().orElseThrow();
        var start = new String(file, meta.payload() + 4, 4, StandardCharsets.ISO_8859_1).equals("hdlr") ? meta.payload()
                : meta.payload() + 4;
        return Mp4Atoms.children(file, start, meta.end());
    }

    private static String handler(byte[] file, Mp4Atoms.Box trak) {
        var hdlr = keyedChildren(file, trak).getFirst();
        return new String(file, hdlr.payload() + 8, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("removes the keyed metadata box of a track when none of its keys remains")
    void removesBox() {
        var file = fixture();
        var tags = TagEditor.read(MediaSource.of(file));
        var audio = track(tags, 1);
        var edited = tags.toBuilder().track(audio.withTags(audio.tags().toBuilder().remove("com.example.microphone")
                .build())).build();
        var written = write(file, edited);
        assertTrue(track(TagEditor.read(MediaSource.of(written)), 1).tags().get("com.example.microphone").isEmpty());
        assertFalse(new String(written, StandardCharsets.ISO_8859_1).contains("com.example.microphone"));
        assertEquals(Optional.of("front"), track(TagEditor.read(MediaSource.of(written)), 0).tags()
                .get("com.example.camera"));
    }

    @Test
    @DisplayName("keeps every track byte for byte when the tags are written back unchanged")
    void unchanged() {
        var file = fixture();
        var written = write(file, TagEditor.read(MediaSource.of(file)));
        var before = Mp4Atoms.all(file, "moov/trak");
        var after = Mp4Atoms.all(written, "moov/trak");
        assertEquals(before.size(), after.size());
        for (var index = 0; index < before.size(); index++) {
            assertTrue(Arrays.equals(file, before.get(index).offset(), before.get(index).end(), written,
                    after.get(index).offset(), after.get(index).end()), "track " + index);
        }
    }

    @Test
    @DisplayName("an edited track key and a new one are read by ExifTool")
    void exiftool(@TempDir Path directory) throws IOException {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        // ExifTool reads the keyed metadata of a track in the QuickTime form only, which new boxes take
        var file = withTrackBoxes(plain(), keyedMeta(false, "com.example.camera", "front"), new byte[0]);
        var tags = TagEditor.read(MediaSource.of(file));
        var video = track(tags, 0);
        var audio = track(tags, 1);
        var written = write(file, tags.toBuilder().track(video.withTags(video.tags().toBuilder()
                        .set("com.example.camera", "rear-wide").build()))
                .track(audio.withTags(audio.tags().toBuilder().set("com.example.microphone", "stereo-pair").build()))
                .build());
        Map<String, String> read = PhoneMetadataTest.exiftool(PhoneMetadataTest.save(directory, "keyed.mov", written));
        assertEquals("rear-wide", read.get("VideoKeys:ExampleCamera"), read.toString());
        assertTrue(read.entrySet().stream().anyMatch(entry -> entry.getValue().equals("stereo-pair")
                && entry.getKey().contains("Keys:")), read.toString());
        assertTrue(read.values().stream().noneMatch(value -> value.equals("front")), read.toString());
        assertArrayEquals(MuxSupport.demux(file).order().getFirst().data(), MuxSupport.demux(written).order().getFirst().data());
    }
}
