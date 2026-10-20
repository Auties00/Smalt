package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes MP4 files whose edit list has several segments (media edits starting between key
 * frames, an empty edit between them, a dwell edit) through the readers, and compares the result
 * with the frames and samples of the unedited file that the edits select: the decode-only
 * pre-roll of each edit must be decoded but never shown, bit for bit.
 */
@DisplayName("MP4 multi-segment edit lists through the readers")
class EditListDecodeTest {
    private static Path video() {
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=3"),
                List.of("-c:v", "libx264", "-g", "5", "-keyint_min", "5", "-sc_threshold", "0", "-bf", "2",
                        "-pix_fmt", "yuv420p", "-threads", "1", "-video_track_timescale", "1000"));
    }

    private static Path audio() {
        return Fixtures.ffmpeg("m4a", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                // perceptual noise substitution draws from a generator whose state a jump resets
                List.of("-c:a", "aac", "-b:a", "96k", "-aac_pns", "0"));
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Replaces the edit list of the first track of a file whose moov is its last box. */
    static byte[] withEdits(byte[] file, long... triples) {
        var moovStart = -1;
        for (var position = 0; position + 8 <= file.length; position += (int) u32(file, position)) {
            if (new String(file, position + 4, 4, StandardCharsets.ISO_8859_1).equals("moov")) {
                moovStart = position;
            }
        }
        var moov = new ByteArrayOutputStream();
        var firstTrack = true;
        for (var position = moovStart + 8; position < file.length; ) {
            var size = (int) u32(file, position);
            if (new String(file, position + 4, 4, StandardCharsets.ISO_8859_1).equals("trak") && firstTrack) {
                firstTrack = false;
                var trak = new ByteArrayOutputStream();
                for (var child = position + 8; child < position + size; ) {
                    var childSize = (int) u32(file, child);
                    var type = new String(file, child + 4, 4, StandardCharsets.ISO_8859_1);
                    if (!type.equals("edts")) {
                        trak.write(file, child, childSize);
                    }
                    if (type.equals("tkhd")) {
                        trak.writeBytes(edts(triples));
                    }
                    child += childSize;
                }
                moov.writeBytes(box("trak", trak.toByteArray()));
            } else {
                moov.write(file, position, size);
            }
            position += size;
        }
        var out = new ByteArrayOutputStream();
        out.write(file, 0, moovStart);
        out.writeBytes(box("moov", moov.toByteArray()));
        return out.toByteArray();
    }

    static byte[] edts(long... triples) {
        var elst = new ByteArrayOutputStream();
        elst.writeBytes(new byte[]{1, 0, 0, 0});
        writeInt(elst, triples.length / 3);
        for (var index = 0; index < triples.length; index += 3) {
            writeLong(elst, triples[index]);
            writeLong(elst, triples[index + 1]);
            writeInt(elst, (int) triples[index + 2] << 16);
        }
        return box("edts", box("elst", elst.toByteArray()));
    }

    static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        writeInt(out, 8 + payload.length);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    static void writeLong(ByteArrayOutputStream out, long value) {
        writeInt(out, (int) (value >>> 32));
        writeInt(out, (int) value);
    }

    static long u32(byte[] data, int position) {
        return (data[position] & 0xFFL) << 24 | (data[position + 1] & 0xFF) << 16 | (data[position + 2] & 0xFF) << 8
                | data[position + 3] & 0xFF;
    }

    static String digest(YuvPicture picture) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var row = 0; row < picture.height(); row++) {
                digest.update(picture.y(), row * picture.yStride(), picture.width());
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                digest.update(picture.u(), row * picture.uvStride(), picture.chromaWidth());
                digest.update(picture.v(), row * picture.uvStride(), picture.chromaWidth());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    record Frame(String digest, long pts, long duration) {
    }

    static List<Frame> frames(byte[] data) {
        var result = new ArrayList<Frame>();
        try (var source = MediaSource.of(data); var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                result.add(new Frame(digest((YuvPicture) frame.picture()), frame.pts(), frame.duration()));
            }
        }
        return result;
    }

    @Test
    @DisplayName("shows the frames the edits select, decode-only pre-roll hidden")
    void videoEdits() {
        var original = read(video());
        var reference = frames(original);
        assertEquals(30, reference.size());
        // the clip presents frame d at media time 200 + 100 * d (two frames of B-frame delay)
        var edited = withEdits(original, 300, 500, 1, 200, -1, 1, 400, 950, 0, 500, 1300, 1, 200, 400, 1);
        var actual = frames(edited);
        var expected = new ArrayList<Frame>();
        for (var index = 3; index <= 5; index++) {
            expected.add(new Frame(reference.get(index).digest(), (index - 3) * 100L, 100));
        }
        expected.add(new Frame(reference.get(7).digest(), 500, 400));
        for (var index = 11; index <= 15; index++) {
            expected.add(new Frame(reference.get(index).digest(), 900 + (index - 11) * 100L, 100));
        }
        expected.add(new Frame(reference.get(2).digest(), 1400, 100));
        expected.add(new Frame(reference.get(3).digest(), 1500, 100));
        assertEquals(expected, actual);
        try (var source = MediaSource.of(edited); var reader = VideoReader.open(source)) {
            // into the third media edit, back into the dwell, then into the last edit, which goes back in the media
            for (var target : new long[]{1_050_000, 650_000, 1_450_000, 0}) {
                reader.seek(target);
                var frame = reader.read();
                var shown = expected.stream().filter(candidate -> candidate.pts() * 1000 <= target).toList().getLast();
                assertEquals(shown, new Frame(digest((YuvPicture) frame.picture()), frame.pts(), frame.duration()),
                        "after a seek to " + target);
            }
        }
    }

    static float[] samples(byte[] data) {
        var out = new float[0];
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                var length = out.length;
                out = Arrays.copyOf(out, length + block.frames());
                System.arraycopy(block.plane(0), 0, out, length, block.frames());
            }
        }
        return out;
    }

    @Test
    @DisplayName("plays the samples the edits select, with one packet of AAC pre-roll per jump")
    void audioEdits() {
        var original = read(audio());
        var reference = samples(original);
        // the encoder priming of ffmpeg's AAC encoder, which the clip's own edit list skips
        var priming = 1024;
        var scale = movieTimescale(original);
        // durations of whole milliseconds, exact in a movie timescale of 1000 as at 48 kHz
        var edited = withEdits(original, 28_800 * scale / 48_000, priming + 10_000, 1, 4_800 * scale / 48_000, -1, 1,
                19_200 * scale / 48_000, priming + 5_000, 1, 16_800 * scale / 48_000, priming + 60_000, 1);
        var actual = samples(edited);
        var expected = new float[28_800 + 19_200 + 16_800];
        System.arraycopy(reference, 10_000, expected, 0, 28_800);
        System.arraycopy(reference, 5_000, expected, 28_800, 19_200);
        System.arraycopy(reference, 60_000, expected, 48_000, 16_800);
        assertArrayEquals(expected, actual);
    }

    /** Returns the movie timescale of a file's mvhd box. */
    static long movieTimescale(byte[] file) {
        for (var index = 0; index + 32 < file.length; index++) {
            if (file[index] == 'm' && file[index + 1] == 'v' && file[index + 2] == 'h' && file[index + 3] == 'd') {
                return u32(file, index + (file[index + 4] == 1 ? 24 : 16));
            }
        }
        throw new AssertionError("no mvhd");
    }
}
