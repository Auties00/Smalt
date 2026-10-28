package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.HevcRandomVectors.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the output of field coded sequences ({@code field_seq_flag}) and the picture timing {@code pic_struct}
 * (ITU-T H.265 D.3.3). The woven frames of the HM and x265 field vectors are checked against the HM decoder's output
 * by {@link HevcRandomAccessTest}; this suite edits the {@code pic_struct} of a bottom field first x265 vector with
 * {@code field_seq_flag} set (fields of picture order count 0, 2, 4 and so on are bottom fields, the others top
 * fields, output in decoding order) and
 * builds the expected frames from the fields of the unedited decode, so that parity, the pairing hints and the
 * fields left without a pair are checked sample by sample. Timestamps and durations follow the first field and the
 * sum of the two, and the frame repetition structures scale the nominal duration of frame pictures.
 */
@DisplayName("HEVC field output and picture timing")
class HevcFieldTest {
    private static final HevcDecoderOptions WIDE = HevcDecoderOptions.builder().highBitDepth(true)
            .hashVerification(true).build();

    private static final String FIELDS = "field-x265-ld-bff-10";

    private static final int TOP = 0;

    private static final int BOTTOM = 1;

    @Test
    @DisplayName("weaves the fields of the bottom field first vector, bottom fields in the odd rows")
    void weaves() {
        var vector = HevcRandomVectors.named(FIELDS);
        var frames = HevcRandomVectors.decode(WIDE, vector.accessUnits());
        assertEquals(vector.frameMd5s(), HevcRandomVectors.md5s(frames));
        var fields = fields(frames);
        for (var index = 0; index < frames.size(); index++) {
            assertEquals(frames.get(index).md5(), md5(weave(fields.get(2 * index + 1), fields.get(2 * index))));
        }
    }

    @Test
    @DisplayName("rounds woven 10-bit fields to 8 bits as it rounds frames")
    void narrows() {
        var vector = HevcRandomVectors.named(FIELDS);
        var wide = HevcRandomVectors.decode(WIDE, vector.accessUnits());
        var narrow = HevcRandomVectors.decode(HevcDecoderOptions.builder().hashVerification(true).build(),
                vector.accessUnits());
        assertEquals(wide.size(), narrow.size());
        for (var index = 0; index < wide.size(); index++) {
            var deep = (Yuv16Picture) wide.get(index).picture();
            var eight = (YuvPicture) narrow.get(index).picture();
            assertEquals(deep.width(), eight.width());
            assertEquals(deep.height(), eight.height());
            for (var y = 0; y < deep.height(); y++) {
                for (var x = 0; x < deep.width(); x++) {
                    var expected = Math.min((deep.y()[y * deep.yStride() + x] + 2) >> 2, 255);
                    assertEquals(expected, eight.y()[y * eight.yStride() + x] & 0xFF, "luma at " + x + "," + y);
                }
            }
            for (var y = 0; y < deep.chromaHeight(); y++) {
                for (var x = 0; x < deep.chromaWidth(); x++) {
                    assertEquals(Math.min((deep.u()[y * deep.uvStride() + x] + 2) >> 2, 255),
                            eight.u()[y * eight.uvStride() + x] & 0xFF);
                    assertEquals(Math.min((deep.v()[y * deep.uvStride() + x] + 2) >> 2, 255),
                            eight.v()[y * eight.uvStride() + x] & 0xFF);
                }
            }
        }
    }

    @Nested
    @DisplayName("pairs fields by pic_struct")
    class Pairing {
        @Test
        @DisplayName("without picture timing messages alternates parity from a top field")
        void withoutPictureTiming() {
            var fields = baseFields();
            var expected = new ArrayList<String>();
            for (var index = 0; index < fields.size(); index += 2) {
                expected.add(md5(weave(fields.get(index), fields.get(index + 1))));
            }
            assertEquals(expected, decodeWith(filled(-1)));
        }

        @Test
        @DisplayName("pairs a field hinted to its previous or next field only with that field")
        void hints() {
            var fields = baseFields();
            var picStructs = new int[fields.size()];
            for (var index = 0; index < picStructs.length; index++) {
                // bottom fields pair with the previous top field, top fields with the next bottom field
                picStructs[index] = index % 2 == 0 ? 10 : 11;
            }
            var expected = new ArrayList<String>();
            expected.add(md5(weave(null, fields.getFirst())));
            for (var index = 1; index + 1 < fields.size(); index += 2) {
                expected.add(md5(weave(fields.get(index), fields.get(index + 1))));
            }
            expected.add(md5(weave(fields.getLast(), null)));
            assertEquals(expected, decodeWith(picStructs));
        }

        @Test
        @DisplayName("pairs the fields of explicit hints that agree with the parities as with plain parities")
        void agreeingHints() {
            var picStructs = new int[16];
            for (var index = 0; index < picStructs.length; index++) {
                picStructs[index] = index % 2 == 0 ? 12 : 9;
            }
            assertEquals(HevcRandomVectors.named(FIELDS).frameMd5s(), decodeWith(picStructs));
        }

        @ParameterizedTest(name = "pic_struct {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("outputs every field of a run of one parity alone, the other field mid-gray")
        void sameParity(int picStruct) {
            var fields = baseFields();
            var expected = new ArrayList<String>();
            for (var field : fields) {
                expected.add(md5(picStruct == 1 ? weave(field, null) : weave(null, field)));
            }
            assertEquals(expected, decodeWith(filled(picStruct)));
        }

        @Test
        @DisplayName("treats a frame pic_struct in a field sequence as an unknown parity")
        void frameValues() {
            var fields = baseFields();
            var expected = new ArrayList<String>();
            for (var index = 0; index < fields.size(); index += 2) {
                expected.add(md5(weave(fields.get(index), fields.get(index + 1))));
            }
            assertEquals(expected, decodeWith(filled(0)));
            assertEquals(expected, decodeWith(filled(7)));
        }
    }

    @Nested
    @DisplayName("times woven frames")
    class Timing {
        @Test
        @DisplayName("gives a frame the timestamp of its first field and the sum of the field durations")
        void packetTimestamps() {
            var units = HevcRandomVectors.named(FIELDS).accessUnits();
            var timing = new long[units.size()][];
            for (var index = 0; index < timing.length; index++) {
                timing[index] = new long[]{1000 + 20L * index, 20};
            }
            var frames = HevcRandomVectors.decode(track(null), WIDE, units, timing);
            assertEquals(8, frames.size());
            for (var index = 0; index < frames.size(); index++) {
                assertEquals(1000 + 40L * index, frames.get(index).pts());
                assertEquals(40, frames.get(index).duration());
                assertEquals(index == 0, frames.get(index).keyframe());
            }
        }

        @Test
        @DisplayName("gives a field left without a pair its own timestamp and duration")
        void unpairedTimestamps() {
            var units = HevcRandomVectors.withPicStructs(HevcRandomVectors.named(FIELDS).accessUnits(), filled(1));
            var timing = new long[units.size()][];
            for (var index = 0; index < timing.length; index++) {
                timing[index] = new long[]{20L * index, 20};
            }
            var frames = HevcRandomVectors.decode(track(null), WIDE, units, timing);
            assertEquals(16, frames.size());
            for (var index = 0; index < frames.size(); index++) {
                assertEquals(20L * index, frames.get(index).pts());
                assertEquals(20, frames.get(index).duration());
            }
        }

        @Test
        @DisplayName("derives timestamps from the track's field rate when packets carry none")
        void trackRate() {
            var units = HevcRandomVectors.named(FIELDS).accessUnits();
            var frames = HevcRandomVectors.decode(track(new Rational(50, 1)), WIDE, units, null);
            var pts = frames.stream().mapToLong(Frame::pts).toArray();
            assertArrayEquals(new long[]{0, 40, 80, 120, 160, 200, 240, 280}, pts);
            assertTrue(frames.stream().allMatch(frame -> frame.duration() == 40));
        }

        @Test
        @DisplayName("scales the nominal duration of frame pictures by their repetition structure")
        void repetition() {
            var vector = HevcRandomVectors.named("frame-timing");
            var units = HevcRandomVectors.withPicStructs(vector.accessUnits(), new int[]{7, 8, 5, 6, 3, 0});
            var frames = HevcRandomVectors.decode(track(new Rational(25, 1)), WIDE, units, null);
            assertEquals(vector.frameMd5s(), HevcRandomVectors.md5s(frames));
            assertArrayEquals(new long[]{80, 120, 60, 60, 40, 40},
                    frames.stream().mapToLong(Frame::duration).toArray());
            assertArrayEquals(new long[]{0, 80, 200, 260, 320, 360}, frames.stream().mapToLong(Frame::pts).toArray());
        }

        @Test
        @DisplayName("keeps packet durations of frame pictures as they are")
        void repetitionWithPacketDurations() {
            var vector = HevcRandomVectors.named("frame-timing");
            var units = HevcRandomVectors.withPicStructs(vector.accessUnits(), new int[]{7, 8, 5, 6, 3, 0});
            var timing = new long[units.size()][];
            for (var index = 0; index < timing.length; index++) {
                timing[index] = new long[]{40L * index, 40};
            }
            var frames = HevcRandomVectors.decode(track(new Rational(25, 1)), WIDE, units, timing);
            assertTrue(frames.stream().allMatch(frame -> frame.duration() == 40));
        }
    }

    @Test
    @DisplayName("outputs the pictures of a sequence without field_seq_flag as frames once pic_struct is gone")
    void fieldsOnlyByPictureTiming() {
        var vector = HevcRandomVectors.named("field-ld-bff-10");
        var units = HevcRandomVectors.withPicStructs(vector.accessUnits(), filled(-1));
        var frames = HevcRandomVectors.decode(WIDE, units);
        assertEquals(16, frames.size());
        assertEquals(64, frames.getFirst().picture().height());
        var woven = HevcRandomVectors.decode(WIDE, vector.accessUnits());
        var fields = fields(woven);
        for (var index = 0; index < frames.size(); index++) {
            assertEquals(md5(fields.get(index)), frames.get(index).md5(), "field " + index);
        }
    }

    @Test
    @DisplayName("outputs frame pictures between fields as frames, releasing a waiting field first")
    void fieldsBetweenFrames() {
        var vector = HevcRandomVectors.named("field-ld-bff-10");
        var fields = fields(HevcRandomVectors.decode(WIDE, vector.accessUnits()));
        var picStructs = new int[16];
        picStructs[0] = 2;
        picStructs[1] = 1;
        picStructs[3] = 2;
        picStructs[5] = 1;
        picStructs[6] = 2;
        var expected = new ArrayList<String>();
        expected.add(md5(weave(fields.get(1), fields.get(0))));
        expected.add(md5(fields.get(2)));
        expected.add(md5(weave(null, fields.get(3))));
        expected.add(md5(fields.get(4)));
        expected.add(md5(weave(fields.get(5), fields.get(6))));
        for (var index = 7; index < fields.size(); index++) {
            expected.add(md5(fields.get(index)));
        }
        var frames = HevcRandomVectors.decode(WIDE, HevcRandomVectors.withPicStructs(vector.accessUnits(), picStructs));
        assertEquals(expected, HevcRandomVectors.md5s(frames));
        assertEquals(vector.frameMd5s().getFirst(), frames.getFirst().md5());
        assertTrue(frames.getFirst().keyframe());
        assertFalse(frames.get(1).keyframe());
    }

    @Test
    @DisplayName("outputs a waiting field at flush and starts the next decode call with no field waiting")
    void flushReleasesField() {
        var vector = HevcRandomVectors.named(FIELDS);
        var fields = baseFields();
        var units = vector.accessUnits();
        var frames = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> frames.add(md5((Yuv16Picture) frame.picture()));
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), WIDE)) {
            for (var index = 0; index < 3; index++) {
                decoder.decode(HevcTestStreams.packet(HevcRandomVectors.annexB(units.get(index))), sink);
            }
            decoder.flush(sink);
            assertEquals(List.of(vector.frameMd5s().getFirst(), md5(weave(null, fields.get(2)))), frames);
            frames.clear();
            for (var unit : units) {
                decoder.decode(HevcTestStreams.packet(HevcRandomVectors.annexB(unit)), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frameMd5s(), frames);
    }

    @Test
    @DisplayName("ignores a picture timing message with no payload")
    void emptyPictureTiming() {
        var vector = HevcRandomVectors.named(FIELDS);
        var units = new ArrayList<List<byte[]>>();
        for (var unit : vector.accessUnits()) {
            var edited = new ArrayList<byte[]>();
            for (var nal : unit) {
                edited.add(HevcRandomVectors.isSei(nal, 1) ? HevcRandomVectors.sei(1, new byte[0]) : nal);
            }
            units.add(edited);
        }
        var fields = baseFields();
        var expected = new ArrayList<String>();
        for (var index = 0; index < fields.size(); index += 2) {
            expected.add(md5(weave(fields.get(index), fields.get(index + 1))));
        }
        assertEquals(expected, HevcRandomVectors.md5s(HevcRandomVectors.decode(WIDE, units)));
        assertFalse(expected.equals(vector.frameMd5s()), "the edit changes the pairing");
    }

    private static int[] filled(int picStruct) {
        var values = new int[16];
        Arrays.fill(values, picStruct);
        return values;
    }

    private static List<String> decodeWith(int[] picStructs) {
        var units = HevcRandomVectors.withPicStructs(HevcRandomVectors.named(FIELDS).accessUnits(), picStructs);
        return HevcRandomVectors.md5s(HevcRandomVectors.decode(WIDE, units));
    }

    private static VideoTrack track(Rational frameRate) {
        var builder = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 1000));
        if (frameRate != null) {
            builder.frameRate(frameRate);
        }
        return builder.build();
    }

    /**
     * Returns the fields of the unedited vector in output order, from its woven frames: the bottom field of each
     * frame comes first.
     */
    private static List<Yuv16Picture> baseFields() {
        return fields(HevcRandomVectors.decode(WIDE, HevcRandomVectors.named(FIELDS).accessUnits()));
    }

    private static List<Yuv16Picture> fields(List<Frame> frames) {
        var fields = new ArrayList<Yuv16Picture>();
        for (var frame : frames) {
            var picture = (Yuv16Picture) frame.picture();
            fields.add(field(picture, BOTTOM));
            fields.add(field(picture, TOP));
        }
        return fields;
    }

    private static Yuv16Picture field(Yuv16Picture frame, int parity) {
        var field = Yuv16Picture.allocate(frame.width(), frame.height() / 2, frame.chromaFormat(), frame.bitDepth(),
                false, frame.color());
        rows(frame.y(), frame.yStride() * 2, parity * frame.yStride(), field.y(), field.yStride(), 0,
                field.width(), field.height());
        rows(frame.u(), frame.uvStride() * 2, parity * frame.uvStride(), field.u(), field.uvStride(), 0,
                field.chromaWidth(), field.chromaHeight());
        rows(frame.v(), frame.uvStride() * 2, parity * frame.uvStride(), field.v(), field.uvStride(), 0,
                field.chromaWidth(), field.chromaHeight());
        return field;
    }

    /**
     * Weaves a top and a bottom field into a frame; a missing field is mid-gray.
     */
    private static Yuv16Picture weave(Yuv16Picture top, Yuv16Picture bottom) {
        var model = top != null ? top : bottom;
        var frame = Yuv16Picture.allocate(model.width(), model.height() * 2, model.chromaFormat(), model.bitDepth(),
                false, model.color());
        var gray = (short) (1 << (model.bitDepth() - 1));
        Arrays.fill(frame.y(), gray);
        Arrays.fill(frame.u(), gray);
        Arrays.fill(frame.v(), gray);
        for (var parity = 0; parity < 2; parity++) {
            var field = parity == TOP ? top : bottom;
            if (field == null) {
                continue;
            }
            rows(field.y(), field.yStride(), 0, frame.y(), frame.yStride() * 2, parity * frame.yStride(),
                    field.width(), field.height());
            rows(field.u(), field.uvStride(), 0, frame.u(), frame.uvStride() * 2, parity * frame.uvStride(),
                    field.chromaWidth(), field.chromaHeight());
            rows(field.v(), field.uvStride(), 0, frame.v(), frame.uvStride() * 2, parity * frame.uvStride(),
                    field.chromaWidth(), field.chromaHeight());
        }
        return frame;
    }

    private static void rows(short[] src, int srcStride, int srcOffset, short[] dst, int dstStride, int dstOffset,
                             int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, srcOffset + y * srcStride, dst, dstOffset + y * dstStride, width);
        }
    }

    private static String md5(Yuv16Picture picture) {
        var digest = HevcRandomVectors.digest("MD5");
        HevcTestStreams.hash(digest, picture);
        return HevcRandomVectors.hex(digest.digest());
    }
}
