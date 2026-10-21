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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Returns the media time of a file's first edit, or 0 without an edit list. */
    static long firstMediaTime(byte[] file) {
        for (var index = 0; index + 24 < file.length; index++) {
            if (file[index] == 'e' && file[index + 1] == 'l' && file[index + 2] == 's' && file[index + 3] == 't') {
                return file[index + 4] == 1 ? u32(file, index + 24) : u32(file, index + 16);
            }
        }
        return 0;
    }

    /** Returns the media timescale of a file's first mdhd box. */
    static long mediaTimescale(byte[] file) {
        for (var index = 0; index + 32 < file.length; index++) {
            if (file[index] == 'm' && file[index + 1] == 'd' && file[index + 2] == 'h' && file[index + 3] == 'd') {
                return u32(file, index + (file[index + 4] == 1 ? 24 : 16));
            }
        }
        throw new AssertionError("no mdhd");
    }

    /**
     * Gives the first track of a file an edit list, wherever its moov is: the old moov becomes a
     * free box, so that no sample moves, and the new one is appended.
     */
    static byte[] withAppendedEdits(byte[] file, long... triples) {
        var copy = file.clone();
        var moovStart = -1;
        for (var position = 0; position + 8 <= copy.length; position += (int) u32(copy, position)) {
            if (new String(copy, position + 4, 4, StandardCharsets.ISO_8859_1).equals("moov")) {
                moovStart = position;
            }
        }
        var moovEnd = moovStart + (int) u32(copy, moovStart);
        var tail = withEdits(Arrays.copyOf(copy, moovEnd), triples);
        var moov = Arrays.copyOfRange(tail, moovStart, tail.length);
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, copy, moovStart + 4, 4);
        var out = new ByteArrayOutputStream();
        out.writeBytes(copy);
        out.writeBytes(moov);
        return out.toByteArray();
    }

    /**
     * Edits an audio clip into three media edits with an empty edit after the first (the second
     * edit going back in the media), and returns the decoded result and the reference samples
     * those edits select, both at the decoder's output rate.
     */
    static float[][] editedAndExpected(byte[] original, int outputRate) {
        return editedAndExpected(original, outputRate, new long[][]{{10_000, 30}, {-1, 5}, {5_000, 20}, {60_000, 17}});
    }

    /**
     * Edits an audio clip and returns the decoded result and the reference samples the edits
     * select, both at the decoder's output rate.
     *
     * @param segments the edits: each the offset of its first sample in output samples ({@code -1}
     * for an empty edit) and its length in units of 20 ms, exact in every timescale used
     */
    static float[][] editedAndExpected(byte[] original, int outputRate, long[][] segments) {
        var reference = samples(original);
        var priming = firstMediaTime(original);
        var movie = movieTimescale(original);
        var media = mediaTimescale(original);
        var triples = new long[segments.length * 3];
        var expected = new float[0];
        for (var index = 0; index < segments.length; index++) {
            var length = segments[index][1] * outputRate / 50;
            triples[index * 3] = segments[index][1] * movie / 50;
            triples[index * 3 + 1] = segments[index][0] < 0 ? -1 : priming + segments[index][0] * media / outputRate;
            triples[index * 3 + 2] = 1;
            if (segments[index][0] >= 0) {
                var start = expected.length;
                expected = Arrays.copyOf(expected, start + (int) length);
                System.arraycopy(reference, (int) segments[index][0], expected, start, (int) length);
            }
        }
        var edited = firstMediaTime(original) != 0 ? withEdits(original, triples) : withAppendedEdits(original, triples);
        return new float[][]{samples(edited), expected};
    }

    /** Describes where two decodes differ: the length of the leading mismatch of each 1000-sample block. */
    static String mismatches(float[] actual, float[] expected) {
        var report = new StringBuilder("lengths " + actual.length + " and " + expected.length + ";");
        for (var block = 0; block < Math.min(actual.length, expected.length); block += 1000) {
            var differing = 0;
            for (var index = block; index < Math.min(block + 1000, actual.length); index++) {
                if (actual[index] != expected[index]) {
                    differing++;
                }
            }
            if (differing > 0) {
                report.append(' ').append(block).append(':').append(differing);
            }
        }
        return report.toString();
    }

    static byte[] sine(String... encoder) {
        var options = new ArrayList<>(List.of("-c:a"));
        options.addAll(List.of(encoder));
        return read(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                options));
    }

    @Test
    @DisplayName("plays the samples MP3 edits select bit-exactly, its pre-roll reaching the main data of the bit reservoir")
    void mp3Edits() {
        // at 32 kbit/s a frame holds about 60 bytes of main data, so main_data_begin reaches back several frames
        for (var bitrate : List.of("32k", "128k")) {
            var result = editedAndExpected(sine("libmp3lame", "-b:a", bitrate), 48_000);
            assertArrayEquals(result[1], result[0], bitrate + ": " + mismatches(result[0], result[1]));
        }
    }

    @Test
    @DisplayName("plays the samples MP2 edits select bit-exactly")
    void mp2Edits() {
        var result = editedAndExpected(sine("mp2", "-b:a", "128k"), 48_000);
        assertArrayEquals(result[1], result[0], mismatches(result[0], result[1]));
    }

    @Test
    @DisplayName("plays the samples Opus edits select, with 80 ms of pre-roll per jump and the pre-skip applied once")
    void opusEdits() {
        var result = editedAndExpected(sine("libopus", "-b:a", "64k"), 48_000);
        var actual = result[0];
        var expected = result[1];
        assertEquals(expected.length, actual.length);
        // Opus converges during its pre-roll rather than restarting bit-exactly; a misplaced segment
        // (a pre-skip applied twice or not at all) would put the 440 Hz sine out of phase
        var signal = 0.0;
        var noise = 0.0;
        for (var index = 0; index < expected.length; index++) {
            signal += (double) expected[index] * expected[index];
            noise += (double) (expected[index] - actual[index]) * (expected[index] - actual[index]);
        }
        var snr = 10 * Math.log10(signal / Math.max(noise, 1e-30));
        assertTrue(snr > 30, "SNR " + snr + " dB");
    }

    @Test
    @DisplayName("plays the samples HE-AAC edits select bit-exactly, its pre-roll rebuilding the SBR state")
    void heAacEdits() {
        var explicit = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetch("mpeg4audio-al_sbr_sig_48_2_sig0.mp4",
                "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/DVD1/"
                        + "mpeg4audio-conformance/compressedMp4/al_sbr_sig_48_2_sig0.mp4",
                "f115fd344f97ea6bc63e5975556a6e54dc46f4e18ae0f83f408c1488049f74f5"));
        var result = editedAndExpected(explicit, 48_000);
        assertArrayEquals(result[1], result[0], mismatches(result[0], result[1]));
        var parametricStereo = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetch("mpeg4audio-al_sbr_i_44_1.mp4",
                "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/DVD1/"
                        + "mpeg4audio-conformance/compressedMp4/al_sbr_i_44_1.mp4",
                "93f9a8e33ca65f529139ed4827bf37214fe28a17aa786f3a23ba6cfd571cf36c"));
        result = editedAndExpected(parametricStereo, 44_100);
        assertArrayEquals(result[1], result[0], mismatches(result[0], result[1]));
    }

    @Test
    @DisplayName("plays the samples edits of a predictive AAC object type select bit-exactly, decoding every packet up to a forward edit")
    void predictiveAacEdits() {
        // AAC Main: backward-adaptive prediction, whose state a few packets of pre-roll do not rebuild
        var main = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetch("mpeg4audio-am00_48.mp4",
                "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/DVD1/"
                        + "mpeg4audio-conformance/compressedMp4/am00_48.mp4",
                "c7a8afd61ec5ff6a10d96458022006a2d8eea3a8bce4f709684bb4e7d1dfe5f6"));
        var result = editedAndExpected(main, 48_000, new long[][]{{10_000, 30}, {-1, 5}, {50_000, 20}, {120_000, 17}});
        assertArrayEquals(result[1], result[0], mismatches(result[0], result[1]));
        // AAC-ELD without SBR: a window of four frames, rebuilt by three packets of pre-roll even after a jump back
        var eld = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetch("mpeg4audio-er_eld1000np_48_ep0.mp4",
                "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/DVD1/"
                        + "mpeg4audio-conformance/compressedMp4/er_eld1000np_48_ep0.mp4",
                "64acfe4c29a7733a2f09a0e1d6ba297f932e0ca7c888cdd650b4da69055f7f37"));
        result = editedAndExpected(eld, 48_000);
        assertArrayEquals(result[1], result[0], mismatches(result[0], result[1]));
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
