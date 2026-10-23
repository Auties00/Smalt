package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers QuickTime Motion-JPEG format B frames in {@link MjpegDecoder}: frames converted from interchange JPEG
 * (marker-less segments, unstuffed data, the 48-byte header of the QuickTime File Format) decode to the planes of
 * the original images, two-field frames weave, damaged headers are rejected, and QuickTime files of such frames
 * carry an {@code mjpb} sample entry that ffmpeg's format B decoder reads to the same frames.
 */
@DisplayName("MjpegDecoder format B")
class MjpegFormatBTest {
    private static byte[] jpeg(ChromaFormat format, boolean optimize, int frame) {
        return JpegEncoder.encode(MotionJpegTesting.pattern(52, 36, frame), JpegEncoderOptions.builder()
                .quality(80).chromaFormat(format).optimizeHuffman(optimize).build());
    }

    private static byte[] planes(byte[] jpeg) {
        return MotionJpegTesting.planes(JpegDecoder.decode(MediaSource.of(jpeg)).picture());
    }

    static Stream<Arguments> cases() {
        var arguments = Stream.<Arguments>builder();
        for (var format : ChromaFormat.values()) {
            arguments.add(Arguments.of(format, true, true));
            arguments.add(Arguments.of(format, false, true));
            arguments.add(Arguments.of(format, false, false));
        }
        return arguments.build();
    }

    @ParameterizedTest(name = "{0}, optimized {1}, Huffman offset {2}")
    @MethodSource("cases")
    @DisplayName("decode to the planes of the interchange images they were made from")
    void single(ChromaFormat format, boolean optimize, boolean huffmanOffset) {
        var jpeg = jpeg(format, optimize, 1);
        // a byte 0xFF in the entropy data exercises the stuffing the conversion restores
        assertTrue(indexOfStuffed(jpeg) > 0);
        var frame = MotionJpegTesting.toFormatB(huffmanOffset, jpeg);
        assertArrayEquals(planes(jpeg), MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        var padded = MotionJpegTesting.concat(new byte[3], frame, new byte[5]);
        assertArrayEquals(planes(jpeg), MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(padded, 3, frame.length)));
    }

    private static int indexOfStuffed(byte[] jpeg) {
        for (var i = MotionJpegTesting.find(jpeg, 0xDA); i + 1 < jpeg.length; i++) {
            if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == 0) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("weave two format B fields, the bottom one first when the track says so")
    void fields() {
        var top = JpegEncoder.encode(MotionJpegTesting.yuvPattern(40, 10, ChromaFormat.YUV422, 1),
                JpegEncoderOptions.ofQuality(85).toBuilder().chromaFormat(ChromaFormat.YUV422).build());
        var bottom = JpegEncoder.encode(MotionJpegTesting.yuvPattern(40, 10, ChromaFormat.YUV422, 2),
                JpegEncoderOptions.ofQuality(85).toBuilder().chromaFormat(ChromaFormat.YUV422).build());
        var frame = MotionJpegTesting.toFormatB(true, top, bottom);
        var stillTop = (YuvPicture) JpegDecoder.decode(MediaSource.of(top)).picture();
        var stillBottom = (YuvPicture) JpegDecoder.decode(MediaSource.of(bottom)).picture();
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(stillTop, stillBottom, 20)),
                MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(new Rational(1, 25)).width(40).height(20)
                .interlacing(Interlacing.BOTTOM_FIELD_FIRST_SEPARATED).build();
        var pictures = new ArrayList<Picture>();
        new MjpegDecoder(track).decode(new Packet(1, frame, 0, 0, 1, true), video -> pictures.add(video.picture()));
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(stillBottom, stillTop, 20)),
                MotionJpegTesting.planes(pictures.getFirst()));
    }

    @Test
    @DisplayName("reject headers that point outside their field, and quantization tables kept in the sample description")
    void rejects() {
        var jpeg = jpeg(ChromaFormat.YUV420, true, 2);
        var frame = MotionJpegTesting.toFormatB(true, jpeg);
        var decoder = new MjpegDecoder();
        var noTables = frame.clone();
        ByteBuffer.wrap(noTables).putInt(20, 0);
        assertEquals("mjpeg format b", assertThrows(MediaException.Unsupported.class,
                () -> decoder.decodeFrame(noTables, 0, noTables.length)).format());
        for (var slot : new int[]{20, 24, 28, 32, 36}) {
            var outside = frame.clone();
            ByteBuffer.wrap(outside).putInt(slot, frame.length + 100);
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(outside, 0, outside.length));
            var inHeader = frame.clone();
            ByteBuffer.wrap(inHeader).putInt(slot, 8);
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(inHeader, 0, inHeader.length));
        }
        var noFrame = frame.clone();
        ByteBuffer.wrap(noFrame).putInt(28, 0);
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(noFrame, 0, noFrame.length));
        var badLength = frame.clone();
        var dqt = ByteBuffer.wrap(frame).getInt(20);
        ByteBuffer.wrap(badLength).putShort(dqt, (short) 0xFFFF);
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(badLength, 0, badLength.length));
        var secondWithoutTag = MotionJpegTesting.toFormatB(true, jpeg, jpeg);
        ByteBuffer.wrap(secondWithoutTag).putInt(ByteBuffer.wrap(secondWithoutTag).getInt(16) + 4, 0);
        assertThrows(MediaException.Malformed.class,
                () -> decoder.decodeFrame(secondWithoutTag, 0, secondWithoutTag.length));
    }

    @Test
    @DisplayName("raise nothing but MediaException on random damage")
    void fuzz() {
        var frames = new byte[][]{MotionJpegTesting.toFormatB(true, jpeg(ChromaFormat.YUV420, true, 3)),
                MotionJpegTesting.toFormatB(false, jpeg(ChromaFormat.YUV422, false, 4), jpeg(ChromaFormat.YUV422, false, 5))};
        var random = new Random(3);
        var decoder = new MjpegDecoder();
        for (var trial = 0; trial < 800; trial++) {
            var damaged = frames[trial % 2].clone();
            for (var k = 0; k < 1 + random.nextInt(6); k++) {
                var at = random.nextInt(4) == 0 ? random.nextInt(48) : random.nextInt(damaged.length);
                damaged[at] = (byte) random.nextInt(256);
            }
            var input = random.nextInt(4) == 0 ? Arrays.copyOf(damaged, random.nextInt(damaged.length)) : damaged;
            try {
                decoder.decodeFrame(input, 0, input.length);
            } catch (MediaException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }

    @Test
    @DisplayName("go into QuickTime as mjpb, demux and decode, and ffmpeg decodes the file to the same frames")
    void quickTime(@TempDir Path directory) throws IOException {
        var originals = new ArrayList<byte[]>();
        var frames = new ArrayList<byte[]>();
        for (var i = 0; i < 3; i++) {
            var jpeg = JpegEncoder.encode(MotionJpegTesting.pattern(64, 48, i), JpegEncoderOptions.builder()
                    .chromaFormat(ChromaFormat.YUV422).build());
            originals.add(jpeg);
            frames.add(MotionJpegTesting.toFormatB(true, jpeg));
        }
        var sink = MediaSink.memory();
        var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(new Rational(1, 25)).width(64).height(48)
                .build();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(track);
            for (var i = 0; i < frames.size(); i++) {
                muxer.writePacket(new Packet(1, frames.get(i), i, i, 1, true));
            }
            muxer.finish();
        }
        var file = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("mjpeg-format-b.mov", file);
        var text = new String(file, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("mjpb"));
        var stream = MotionJpegTesting.demux(file);
        assertEquals(Codec.MJPEG, stream.track().codec());
        var pictures = new ArrayList<Picture>();
        try (var decoder = VideoDecoders.create(stream.track())) {
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(frames.get(i), stream.packets().get(i).data());
                decoder.decode(stream.packets().get(i), video -> pictures.add(video.picture()));
                assertArrayEquals(planes(originals.get(i)), MotionJpegTesting.planes(pictures.get(i)));
            }
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        var path = directory.resolve("mjpb.mov");
        Files.write(path, file);
        assertTrue(Oracle.probe(path).contains("\"codec_name\": \"mjpegb\""));
        MotionJpegTesting.assertCloseToFfmpeg(Oracle.decodeVideo(path, "yuvj422p", 0), pictures, 3, 0.5);
    }
}
