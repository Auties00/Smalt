package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg2.Mpeg2TestStreams.Split;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Picture;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what the decoder hands out besides the samples: the display order, the timestamps and durations, the scan
 * and repeated fields of {@code progressive_sequence}, {@code progressive_frame}, {@code top_field_first} and
 * {@code repeat_first_field}, the weaving of field pictures, the closed captions, the key frames, and which pictures
 * are dropped when their references are missing (after a flush, at a broken link) or kept (the leading B-pictures of
 * a closed group of pictures). The streams come from {@link SyntaxStream}, whose display flags are set per picture.
 */
@DisplayName("MPEG video decoder output")
class Mpeg2OutputTest {
    /** The frame period of {@code frame_rate_code} 3 (25 Hz) on the 90 kHz time base. */
    static final long PERIOD = 3600;

    static Sequence sequence(boolean mpeg2, boolean progressive) {
        var s = new Sequence();
        s.mpeg2 = mpeg2;
        s.width = 48;
        s.height = 32;
        s.progressive = progressive || !mpeg2;
        s.aspect = mpeg2 ? 2 : 1;
        return s;
    }

    static Picture picture(int type, int reference) {
        var p = new Picture(type);
        p.temporalReference = reference;
        p.framePredFrameDct = true;
        p.intraRate = 0.5;
        return p;
    }

    /** Decodes packets, each with the timestamp given (or none for {@link Packet#NO_TIMESTAMP}). */
    static List<VideoFrame> decode(byte[] stream, Codec codec, Split split, long... timestamps) {
        var packets = Mpeg2TestStreams.packets(stream, split);
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(codec))) {
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                var pts = i < timestamps.length ? timestamps[i] : Packet.NO_TIMESTAMP;
                decoder.decode(new Packet(packet.trackId(), packet.data(), pts, pts, Packet.UNKNOWN_DURATION,
                        false), frames::add);
            }
            decoder.flush(frames::add);
        }
        return frames;
    }

    @Nested
    @DisplayName("scan and repeated fields")
    class Scan {
        @ParameterizedTest(name = "top_field_first {0}, repeat_first_field {1}")
        @CsvSource({"false, false, 0", "false, true, 2", "true, true, 4"})
        @DisplayName("a progressive sequence shows each frame once, twice or three times")
        void progressiveSequence(boolean topFirst, boolean repeat, int repeated) {
            var list = new ArrayList<Picture>();
            for (var i = 0; i < 3; i++) {
                var p = picture(SyntaxStream.I, i);
                p.progressiveFrame = true;
                p.topFieldFirst = topFirst;
                p.repeatFirstField = repeat;
                list.add(p);
            }
            var frames = decode(SyntaxStream.write(sequence(true, true), list, 1), Codec.MPEG2_VIDEO, Split.PICTURES);
            assertEquals(3, frames.size());
            for (var frame : frames) {
                assertEquals(Optional.of(FieldOrder.PROGRESSIVE), frame.sideData().fieldOrder());
                assertEquals(repeated, frame.sideData().repeatedFields());
                assertEquals(PERIOD * (2 + repeated) / 2, frame.duration());
            }
        }

        @ParameterizedTest(name = "progressive_frame {0}, top_field_first {1}, repeat_first_field {2}")
        @CsvSource({"false, true, false, TOP_FIRST, 0", "false, false, false, BOTTOM_FIRST, 0",
                "true, true, false, PROGRESSIVE, 0", "true, true, true, TOP_FIRST, 1",
                "true, false, true, BOTTOM_FIRST, 1"})
        @DisplayName("an interlaced sequence signals each frame's field order and its repeated first field")
        void interlacedSequence(boolean progressiveFrame, boolean topFirst, boolean repeat, FieldOrder order,
                                int repeated) {
            var list = new ArrayList<Picture>();
            for (var i = 0; i < 3; i++) {
                var p = picture(i == 0 ? SyntaxStream.I : SyntaxStream.P, i);
                p.progressiveFrame = progressiveFrame;
                p.framePredFrameDct = progressiveFrame;
                p.topFieldFirst = topFirst;
                p.repeatFirstField = repeat;
                list.add(p);
            }
            var frames = decode(SyntaxStream.write(sequence(true, false), list, 2), Codec.MPEG2_VIDEO,
                    Split.PICTURES);
            assertEquals(3, frames.size());
            for (var frame : frames) {
                assertEquals(Optional.of(order), frame.sideData().fieldOrder());
                assertEquals(repeated, frame.sideData().repeatedFields());
                assertEquals(PERIOD * (2 + repeated) / 2, frame.duration());
            }
        }

        @ParameterizedTest(name = "first field top: {0}")
        @CsvSource({"true, TOP_FIRST", "false, BOTTOM_FIRST"})
        @DisplayName("field pictures woven into a frame take the order of the first field")
        void fieldPictures(boolean topFirst, FieldOrder order) {
            var list = new ArrayList<Picture>();
            for (var i = 0; i < 2; i++) {
                for (var field = 0; field < 2; field++) {
                    var type = i == 0 && field == 0 ? SyntaxStream.I : SyntaxStream.P;
                    var p = new Picture(type, (field == 0) == topFirst ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                    p.temporalReference = i;
                    list.add(p);
                }
            }
            var stream = SyntaxStream.write(sequence(true, false), list, 3);
            for (var split : Split.values()) {
                var frames = decode(stream, Codec.MPEG2_VIDEO, split);
                assertEquals(2, frames.size(), split + " frame count");
                for (var frame : frames) {
                    assertEquals(Optional.of(order), frame.sideData().fieldOrder(), split + " field order");
                    assertEquals(0, frame.sideData().repeatedFields());
                    assertEquals(48, frame.picture().width());
                    assertEquals(32, frame.picture().height());
                }
            }
        }

        @Test
        @DisplayName("MPEG-1 frames carry no scan")
        void mpeg1() {
            var list = List.of(picture(SyntaxStream.I, 0), picture(SyntaxStream.P, 1));
            var frames = decode(SyntaxStream.write(sequence(false, true), list, 4), Codec.MPEG1_VIDEO,
                    Split.PICTURES);
            assertEquals(2, frames.size());
            for (var frame : frames) {
                assertTrue(frame.sideData().fieldOrder().isEmpty());
                assertEquals(PERIOD, frame.duration());
            }
        }
    }

    @Nested
    @DisplayName("order and timestamps")
    class Timing {
        /** I0 P3 B1 B2 P6 B4 B5 in coding order. */
        static List<Picture> ipbb() {
            var list = new ArrayList<Picture>();
            list.add(picture(SyntaxStream.I, 0));
            list.add(picture(SyntaxStream.P, 3));
            list.add(picture(SyntaxStream.B, 1));
            list.add(picture(SyntaxStream.B, 2));
            list.add(picture(SyntaxStream.P, 6));
            list.add(picture(SyntaxStream.B, 4));
            list.add(picture(SyntaxStream.B, 5));
            return list;
        }

        @Test
        @DisplayName("presentation timestamps of a reordered stream come out in display order")
        void presentationTimestamps() {
            var stream = SyntaxStream.write(sequence(true, true), progressive(ipbb()), 5);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES, 0, 3 * PERIOD, PERIOD, 2 * PERIOD,
                    6 * PERIOD, 4 * PERIOD, 5 * PERIOD);
            assertEquals(List.of(0L, PERIOD, 2 * PERIOD, 3 * PERIOD, 4 * PERIOD, 5 * PERIOD, 6 * PERIOD),
                    frames.stream().map(VideoFrame::pts).toList());
            assertEquals(List.of(true, false, false, false, false, false, false),
                    frames.stream().map(VideoFrame::keyframe).toList());
        }

        @Test
        @DisplayName("timestamps in decoding order, as AVI stores them, become display times")
        void decodingOrderTimestamps() {
            var stream = SyntaxStream.write(sequence(true, true), progressive(ipbb()), 6);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES, 0, 1, 2, 3, 4, 5, 6);
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L), frames.stream().map(VideoFrame::pts).toList());
        }

        @Test
        @DisplayName("frames without timestamps follow the previous one by the frame period")
        void missingTimestamps() {
            var stream = SyntaxStream.write(sequence(true, true), progressive(ipbb()), 7);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES, 1000);
            var expected = new ArrayList<Long>();
            for (var i = 0; i < 7; i++) {
                expected.add(1000 + i * PERIOD);
            }
            assertEquals(expected, frames.stream().map(VideoFrame::pts).toList());
        }

        @Test
        @DisplayName("frames without timestamps follow the previous one by its duration, repeated fields included")
        void missingTimestampsPulldown() {
            // 3:2 pulldown of progressive frames: three fields, two fields, three fields, two fields
            var list = new ArrayList<Picture>();
            boolean[] topFirst = {true, false, false, true};
            boolean[] repeat = {true, false, true, false};
            for (var i = 0; i < 4; i++) {
                var p = picture(i == 0 ? SyntaxStream.I : SyntaxStream.P, i);
                p.progressiveFrame = true;
                p.topFieldFirst = topFirst[i];
                p.repeatFirstField = repeat[i];
                list.add(p);
            }
            var stream = SyntaxStream.write(sequence(true, false), list, 17);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES, 1000);
            var half = PERIOD / 2;
            assertEquals(List.of(1000L, 1000 + 3 * half, 1000 + 5 * half, 1000 + 8 * half),
                    frames.stream().map(VideoFrame::pts).toList());
            assertEquals(List.of(3 * half, PERIOD, 3 * half, PERIOD),
                    frames.stream().map(VideoFrame::duration).toList());
        }

        @Test
        @DisplayName("a second field in a packet of its own adds no timestamp")
        void secondFieldPacket() {
            var list = new ArrayList<Picture>();
            for (var i = 0; i < 3; i++) {
                for (var field = 0; field < 2; field++) {
                    var p = new Picture(i == 0 && field == 0 ? SyntaxStream.I : SyntaxStream.P,
                            field == 0 ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                    p.temporalReference = i;
                    list.add(p);
                }
            }
            var stream = SyntaxStream.write(sequence(true, false), list, 8);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES, 0, Packet.NO_TIMESTAMP, PERIOD,
                    Packet.NO_TIMESTAMP, 2 * PERIOD, Packet.NO_TIMESTAMP);
            assertEquals(List.of(0L, PERIOD, 2 * PERIOD), frames.stream().map(VideoFrame::pts).toList());
        }

        @Test
        @DisplayName("a low delay stream hands out each frame as soon as it is decoded")
        void lowDelay() {
            var s = sequence(true, true);
            s.lowDelay = true;
            var list = progressive(List.of(picture(SyntaxStream.I, 0), picture(SyntaxStream.P, 1),
                    picture(SyntaxStream.P, 2)));
            var packets = Mpeg2TestStreams.packets(SyntaxStream.write(s, list, 9), Split.PICTURES);
            try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
                for (var packet : packets) {
                    var count = new int[1];
                    decoder.decode(packet, frame -> count[0]++);
                    assertEquals(1, count[0], "frames handed out by each packet");
                }
            }
        }

        @Test
        @DisplayName("a reference frame is held until the next reference frame starts or the stream is flushed")
        void heldReference() {
            var list = progressive(List.of(picture(SyntaxStream.I, 0), picture(SyntaxStream.P, 1),
                    picture(SyntaxStream.P, 2)));
            var stream = SyntaxStream.write(sequence(true, true), list, 10);
            // without the sequence end code, which hands out the last reference frame itself
            var packets = Mpeg2TestStreams.packets(Arrays.copyOf(stream, stream.length - 4), Split.PICTURES);
            try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
                var count = new int[1];
                decoder.decode(packets.get(0), frame -> count[0]++);
                assertEquals(0, count[0], "the I-frame is held");
                decoder.decode(packets.get(1), frame -> count[0]++);
                assertEquals(1, count[0], "the first P-frame releases the I-frame");
                decoder.decode(packets.get(2), frame -> count[0]++);
                assertEquals(2, count[0], "the second P-frame releases the first");
                decoder.flush(frame -> count[0]++);
                assertEquals(3, count[0], "the flush releases the last P-frame");
            }
        }

        static List<Picture> progressive(List<Picture> list) {
            for (var p : list) {
                p.progressiveFrame = true;
                p.topFieldFirst = false;
            }
            return list;
        }
    }

    @Nested
    @DisplayName("missing references")
    class References {
        static List<Picture> openGop(boolean closed, boolean broken) {
            var list = new ArrayList<Picture>();
            list.add(picture(SyntaxStream.I, 2));
            list.add(picture(SyntaxStream.B, 0));
            list.add(picture(SyntaxStream.B, 1));
            list.add(picture(SyntaxStream.P, 5));
            list.add(picture(SyntaxStream.B, 3));
            list.add(picture(SyntaxStream.B, 4));
            list.getFirst().closedGop = closed;
            list.getFirst().brokenLink = broken;
            for (var p : list) {
                p.progressiveFrame = true;
                p.topFieldFirst = false;
            }
            return list;
        }

        @Test
        @DisplayName("the leading B-pictures of a closed group of pictures predict backward only and are kept")
        void closedGop() {
            var stream = SyntaxStream.write(sequence(true, true), backwardOnly(openGop(true, false)), 11);
            assertEquals(6, decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES).size());
        }

        @Test
        @DisplayName("the leading B-pictures of an open group of pictures without a previous reference are dropped")
        void openGopStart() {
            var stream = SyntaxStream.write(sequence(true, true), openGop(false, false), 12);
            var frames = decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES);
            assertEquals(4, frames.size());
            assertTrue(frames.getFirst().keyframe());
        }

        @Test
        @DisplayName("the leading B-pictures after a broken link are dropped")
        void brokenLink() {
            var stream = SyntaxStream.write(sequence(true, true), openGop(false, true), 13);
            assertEquals(4, decode(stream, Codec.MPEG2_VIDEO, Split.PICTURES).size());
        }

        @Test
        @DisplayName("P-pictures after a flush wait for the next I-picture")
        void afterFlush() {
            var list = Timing.progressive(new ArrayList<>(List.of(picture(SyntaxStream.I, 0),
                    picture(SyntaxStream.P, 1), picture(SyntaxStream.P, 2), picture(SyntaxStream.I, 3),
                    picture(SyntaxStream.P, 4))));
            list.get(3).group = true;
            var packets = Mpeg2TestStreams.packets(SyntaxStream.write(sequence(true, true), list, 14),
                    Split.PICTURES);
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
                decoder.decode(packets.get(0), frames::add);
                decoder.flush(frames::add);
                assertEquals(1, frames.size());
                for (var i = 2; i < packets.size(); i++) {
                    decoder.decode(packets.get(i), frames::add);
                }
                decoder.flush(frames::add);
            }
            assertEquals(3, frames.size(), "the I-frame, then the second group's I- and P-frames");
            assertTrue(frames.get(1).keyframe());
        }

        /** Turns the B-pictures before the first P-picture into backward-only pictures, as a closed group needs. */
        static List<Picture> backwardOnly(List<Picture> list) {
            for (var i = 1; i < 3; i++) {
                list.get(i).intraRate = 1.0;
            }
            return list;
        }
    }

    @Nested
    @DisplayName("sequence changes")
    class SequenceChanges {
        /** Decodes a stream in one packet per picture, copying out each frame's size, chroma format and samples. */
        static List<String> samples(byte[] stream) {
            var frames = new ArrayList<String>();
            try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
                Consumer<VideoFrame> copy = frame -> {
                    var picture = (YuvPicture) frame.picture();
                    var text = new StringBuilder(picture.width() + "x" + picture.height() + " "
                            + picture.chromaFormat() + " " + frame.keyframe());
                    for (var row = 0; row < picture.height(); row++) {
                        text.append(' ').append(Arrays.hashCode(Arrays.copyOfRange(picture.y(),
                                row * picture.yStride(), row * picture.yStride() + picture.width())));
                    }
                    for (var row = 0; row < picture.chromaHeight(); row++) {
                        var offset = row * picture.uvStride();
                        text.append(' ').append(Arrays.hashCode(Arrays.copyOfRange(picture.u(), offset,
                                offset + picture.chromaWidth())));
                        text.append(' ').append(Arrays.hashCode(Arrays.copyOfRange(picture.v(), offset,
                                offset + picture.chromaWidth())));
                    }
                    frames.add(text.toString());
                };
                for (var packet : Mpeg2TestStreams.packets(stream, Split.PICTURES)) {
                    decoder.decode(packet, copy);
                }
                decoder.flush(copy);
            }
            return frames;
        }

        @ParameterizedTest(name = "progressive second sequence: {0}")
        @CsvSource({"true", "false"})
        @DisplayName("a sequence header of another size and chroma format hands out the held frame and starts afresh")
        void sizeChange(boolean progressive) {
            var first = sequence(true, true);
            var second = sequence(true, progressive);
            second.width = 80;
            second.height = 64;
            second.chroma = 2;
            var a = SyntaxStream.write(first, Timing.progressive(Timing.ipbb()), 18);
            var b = SyntaxStream.write(second, Timing.progressive(Timing.ipbb()), 19);
            var expectedA = samples(a);
            var expectedB = samples(b);
            assertEquals(7, expectedA.size());
            assertEquals(7, expectedB.size());
            // the first stream without its sequence end code, so the second sequence header itself releases the
            // last reference frame of the first
            var joined = Arrays.copyOf(a, a.length - 4 + b.length);
            System.arraycopy(b, 0, joined, a.length - 4, b.length);
            var expected = new ArrayList<>(expectedA);
            expected.addAll(expectedB);
            assertEquals(expected, samples(joined));
        }
    }

    @Nested
    @DisplayName("side data and weaving")
    class SideData {
        @Test
        @DisplayName("the ATSC closed captions of a frame's pictures are attached to it")
        void captions() {
            var list = new ArrayList<Picture>();
            for (var field = 0; field < 2; field++) {
                var p = new Picture(field == 0 ? SyntaxStream.I : SyntaxStream.P,
                        field == 0 ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                p.captions = field == 0 ? new byte[]{(byte) 0xFC, 0x14, 0x20} : new byte[]{(byte) 0xFC, 0x41, 0x42,
                        (byte) 0xFD, (byte) 0x80, (byte) 0x80};
                list.add(p);
            }
            var frame = decode(SyntaxStream.write(sequence(true, false), list, 15), Codec.MPEG2_VIDEO,
                    Split.PICTURES).getFirst();
            assertArrayEquals(new byte[]{(byte) 0xFC, 0x14, 0x20, (byte) 0xFC, 0x41, 0x42, (byte) 0xFD, (byte) 0x80,
                    (byte) 0x80}, frame.sideData().closedCaptions().orElseThrow());
        }

        @Test
        @DisplayName("a field whose second field never comes is completed by repeating each of its rows")
        void unpairedField() {
            var list = new ArrayList<Picture>();
            list.add(new Picture(SyntaxStream.I, SyntaxStream.BOTTOM));
            var frames = decode(SyntaxStream.write(sequence(true, false), list, 16), Codec.MPEG2_VIDEO,
                    Split.PICTURES);
            assertEquals(1, frames.size());
            var picture = (YuvPicture) frames.getFirst().picture();
            var y = picture.y();
            for (var row = 0; row + 1 < picture.height(); row += 2) {
                var top = Arrays.copyOfRange(y, row * picture.yStride(), row * picture.yStride() + picture.width());
                var bottom = Arrays.copyOfRange(y, (row + 1) * picture.yStride(),
                        (row + 1) * picture.yStride() + picture.width());
                assertArrayEquals(bottom, top, "row " + row);
            }
            assertFalse(frames.getFirst().sideData().fieldOrder().isEmpty());
        }
    }
}
