package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code cabac_zero_word}s of ITU-T H.264 clause 7.4.2.10 in CABAC streams with a slice
 * size limit: low-quantizer noise codes more bins per byte than the constraint allows, so pictures
 * need words, and the encoder must place them without pushing a slice past the limit. The check of
 * the constraint is the test's own: the bins come from the encoder (CABAC is deterministic, so they
 * are the bins a decoder decodes) and the bytes are counted in the output NAL units.
 */
@DisplayName("H.264 cabac_zero_words within maxSliceBytes")
class CabacZeroWordsTest {
    /**
     * The slice units of one coded picture.
     *
     * @param sizes the NAL unit sizes, header and emulation prevention bytes included
     * @param firsts the first_mb_in_slice of each unit
     * @param words the cabac_zero_words at the end of each unit
     */
    record PictureUnits(List<Integer> sizes, List<Integer> firsts, List<Integer> words) {
        long bytes() {
            return sizes.stream().mapToLong(Integer::longValue).sum();
        }

        int totalWords() {
            return words.stream().mapToInt(Integer::intValue).sum();
        }

        int slicesWithWords() {
            return (int) words.stream().filter(count -> count > 0).count();
        }
    }

    /** Splits the slice NAL units of an access unit into pictures: first_mb_in_slice restarts at 0 for a field. */
    static List<PictureUnits> pictures(Packet packet) {
        var result = new ArrayList<PictureUnits>();
        PictureUnits current = null;
        for (var unit : StreamToolsTest.units(packet.data())) {
            var type = unit[0] & 0x1F;
            if (type != 1 && type != 5) {
                continue;
            }
            var first = StreamToolsTest.firstMb(unit);
            if (current == null || first == 0) {
                current = new PictureUnits(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                result.add(current);
            }
            current.sizes().add(unit.length);
            current.firsts().add(first);
            current.words().add(trailingWords(unit));
        }
        return result;
    }

    /** Counts the escaped cabac_zero_words, 00 00 03 each, at the end of a slice NAL unit. */
    static int trailingWords(byte[] unit) {
        var words = 0;
        var end = unit.length;
        while (end >= 4 && unit[end - 1] == 3 && unit[end - 2] == 0 && unit[end - 3] == 0) {
            words++;
            end -= 3;
        }
        return words;
    }

    /** Whether a picture meets 7.4.2.10: {@code bins <= 32 / 3 * bytes + rawBits / 32}, in exact arithmetic. */
    static boolean meetsBinConstraint(long bins, long bytes, long rawBits) {
        return 96 * bins <= 1024 * bytes + 3 * rawBits;
    }

    /** Low-quantizer content: a gradient under uniform noise of an amplitude, on every plane. */
    static List<Picture> noise(int width, int height, ChromaFormat format, int depth, int count, int amplitude,
                               long seed) {
        var list = new ArrayList<Picture>();
        var max = (1 << depth) - 1;
        var scale = 1 << (depth - 8);
        for (var f = 0; f < count; f++) {
            var random = new Random(seed * 131 + f);
            if (depth == 8 && format == ChromaFormat.YUV420) {
                var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                fill(picture.y(), null, picture.yStride(), width, height, 64 + f, amplitude, 255, random);
                fill(picture.u(), null, picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 100,
                        amplitude, 255, random);
                fill(picture.v(), null, picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 150,
                        amplitude, 255, random);
                list.add(picture);
            } else {
                var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
                fill(null, picture.y(), picture.yStride(), width, height, (64 + f) * scale, amplitude * scale, max,
                        random);
                fill(null, picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 100 * scale,
                        amplitude * scale, max, random);
                fill(null, picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 150 * scale,
                        amplitude * scale, max, random);
                list.add(picture);
            }
        }
        return list;
    }

    private static void fill(byte[] bytes, short[] shorts, int stride, int width, int height, int base, int amplitude,
                             int max, Random random) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = Math.clamp(base + x + y / 2 + random.nextInt(2 * amplitude + 1) - amplitude, 0, max);
                if (bytes != null) {
                    bytes[y * stride + x] = (byte) value;
                } else {
                    shorts[y * stride + x] = (short) value;
                }
            }
        }
    }

    /** Codes 8-bit 4:2:0 pictures, returning the packets, the bins of each picture and, if given, the reconstructions. */
    static List<Packet> encode8(int width, int height, H264EncoderOptions options, List<Picture> pictures,
                                List<Long> bins, List<byte[]> reconstructions) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(width, height, options)) {
            encoder.core().setReconstructUnreferenced(true);
            var pts = 0L;
            for (var picture : pictures) {
                var before = packets.size();
                encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                pts += 3000;
                assertEquals(before + 1, packets.size(), "one packet per frame");
                bins.add(encoder.core().lastPictureBins());
                if (reconstructions != null) {
                    reconstructions.add(encoder.core().lastReconstructionI420());
                }
            }
        }
        return packets;
    }

    /** Codes pictures with the extended engine, returning the packets and the bins of each picture in coding order. */
    static List<Packet> encodeWide(int width, int height, H264EncoderOptions options, List<Picture> pictures,
                                   boolean fields, List<Long> bins, List<short[][]> reconstructions) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(width, height, options)) {
            var pts = 0L;
            for (var picture : pictures) {
                var before = packets.size();
                encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                pts += 3000;
                assertEquals(before + 1, packets.size(), "one packet per frame");
                if (fields) {
                    bins.add(encoder.wide().lastPictureBins(1));
                    bins.add(encoder.wide().lastPictureBins(2));
                } else {
                    bins.add(encoder.wide().lastPictureBins(0));
                }
                reconstructions.add(encoder.wide().lastReconstruction());
            }
        }
        return packets;
    }

    /**
     * Checks every picture of a stream: each slice NAL unit of more than one macroblock within the limit, and the
     * bin count constraint met by the bytes of the output with the fewest words that meet it; returns the words of
     * the stream, the pictures that need words and those whose words are spread over several slices. With a limit,
     * some slices must hold several macroblocks, since a slice of one may exceed it.
     */
    static int[] check(List<Packet> packets, List<Long> bins, long rawBitsPerPicture, int unitsPerPicture, int limit) {
        var picture = 0;
        var words = 0;
        var spread = 0;
        var needed = 0;
        var limited = 0;
        for (var packet : packets) {
            for (var units : pictures(packet)) {
                var sizes = units.sizes();
                for (var k = 0; k < sizes.size(); k++) {
                    var end = k + 1 < sizes.size() ? units.firsts().get(k + 1) : unitsPerPicture;
                    assertTrue(end > units.firsts().get(k), "slices in raster order");
                    if (limit > 0 && end - units.firsts().get(k) > 1) {
                        limited++;
                        assertTrue(sizes.get(k) <= limit, "picture " + picture + ": slice of "
                                + (end - units.firsts().get(k)) + " units has " + sizes.get(k) + " bytes, "
                                + units.words().get(k) + " zero words");
                    }
                }
                var pictureBins = bins.get(picture);
                assertTrue(meetsBinConstraint(pictureBins, units.bytes(), rawBitsPerPicture), "picture " + picture
                        + ": " + pictureBins + " bins in " + units.bytes() + " bytes");
                var pictureWords = units.totalWords();
                if (pictureWords > 0) {
                    assertFalse(meetsBinConstraint(pictureBins, units.bytes() - 3, rawBitsPerPicture), "picture "
                            + picture + ": " + pictureWords + " words, more than the fewest that meet the constraint");
                }
                if (!meetsBinConstraint(pictureBins, units.bytes() - 3L * pictureWords, rawBitsPerPicture)) {
                    needed++;
                }
                words += pictureWords;
                if (units.slicesWithWords() > 1) {
                    spread++;
                }
                picture++;
            }
        }
        assertEquals(bins.size(), picture, "pictures in the stream");
        assertTrue(limit <= 0 || limited > 0, "some slices hold several macroblocks, so the limit applies to them");
        return new int[]{words, needed, spread};
    }

    // Each limit leaves room for several macroblocks per slice and was checked to fail when the encoder does not
    // close slices early for the words' room (every word then lands on slices past the limit)
    static Stream<Arguments> eightBit() {
        return Stream.of(
                Arguments.of(H264Profile.MAIN, 4, 8, 1500),
                Arguments.of(H264Profile.HIGH, 0, 8, 1500),
                Arguments.of(H264Profile.HIGH, 2, 16, 1400),
                Arguments.of(H264Profile.HIGH, 8, 16, 1400));
    }

    @ParameterizedTest(name = "{0}, quality {1}, noise {2}, at most {3} bytes")
    @MethodSource("eightBit")
    @DisplayName("8-bit pictures that need cabac_zero_words keep every slice in size, meet the bin constraint and decode")
    void eightBitLimited(H264Profile profile, int quality, int amplitude, int limit) {
        var width = 128;
        var height = 96;
        var options = StreamToolsTest.annexB(H264EncoderOptions.forRealtime()).profile(profile)
                .rateControl(H264RateControl.CONSTANT_QUALITY).quality(quality).quantizers(0, 51)
                .maxSliceBytes(limit).keyframeInterval(4).build();
        var pictures = noise(width, height, ChromaFormat.YUV420, 8, 6, amplitude, quality + 7);
        var bins = new ArrayList<Long>();
        var reconstructions = new ArrayList<byte[]>();
        var packets = encode8(width, height, options, pictures, bins, reconstructions);
        var result = check(packets, bins, 3072L * (width / 16) * (height / 16), (width / 16) * (height / 16), limit);
        assertTrue(result[1] > 0, "some pictures need cabac_zero_words (" + result[0] + " words)");
        assertTrue(result[2] > 0, "the words of some picture are spread over several slices");
        var smalt = StreamToolsTest.decodeSmalt(packets, H264DecoderOptions.defaults());
        StreamToolsTest.assertFrames(reconstructions, smalt, "Smalt");
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var stream = new ByteArrayOutputStream();
            packets.forEach(packet -> stream.writeBytes(packet.data()));
            StreamToolsTest.assertFrames(reconstructions, StreamToolsTest.decodeFfmpeg("zerowords-" + profile + "-" + quality,
                    stream.toByteArray(), smalt.getFirst().length), "ffmpeg");
        }
    }

    @Test
    @DisplayName("without a size limit the last slice of a picture carries every cabac_zero_word")
    void unlimited() {
        var width = 128;
        var height = 96;
        var options = StreamToolsTest.annexB(H264EncoderOptions.forRealtime()).profile(H264Profile.HIGH)
                .rateControl(H264RateControl.CONSTANT_QUALITY).quality(0).quantizers(0, 51).slices(3)
                .keyframeInterval(4).build();
        var pictures = noise(width, height, ChromaFormat.YUV420, 8, 4, 8, 3);
        var bins = new ArrayList<Long>();
        var packets = encode8(width, height, options, pictures, bins, null);
        var result = check(packets, bins, 3072L * 48, 48, 0);
        assertTrue(result[1] > 0, "some pictures need cabac_zero_words");
        for (var packet : packets) {
            for (var units : pictures(packet)) {
                var words = units.words();
                for (var k = 0; k + 1 < words.size(); k++) {
                    assertEquals(0, words.get(k), "only the last slice carries words");
                }
            }
        }
    }

    // As for eightBit: limits under about 600 bytes give slices of single macroblocks, which the limit exempts
    static Stream<Arguments> wide() {
        return Stream.of(
                Arguments.of(null, ChromaFormat.YUV420, 10, 1200),
                Arguments.of(H264FieldCoding.MBAFF, ChromaFormat.YUV420, 10, 1200),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, ChromaFormat.YUV422, 10, 1200),
                Arguments.of(null, ChromaFormat.YUV444, 8, 1200));
    }

    @ParameterizedTest(name = "{0}, {1}, {2}-bit, at most {3} bytes")
    @MethodSource("wide")
    @DisplayName("extended pictures that need cabac_zero_words keep every slice in size, meet the bin constraint and decode exactly")
    void wideLimited(H264FieldCoding coding, ChromaFormat format, int depth, int limit) {
        var width = 96;
        var height = 64;
        var profile = format == ChromaFormat.YUV444 ? H264Profile.HIGH_444_PREDICTIVE
                : format == ChromaFormat.YUV422 ? H264Profile.HIGH_422 : H264Profile.HIGH_10;
        var builder = ExtendedFormatsTest.options(profile, format, depth).quality(2).quantizers(0, 51)
                .maxSliceBytes(limit);
        var fields = coding == H264FieldCoding.FIELD_PICTURES;
        if (coding != null) {
            builder.fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(coding);
        }
        var pictures = noise(width, height, format, depth, 4, 10, depth);
        var bins = new ArrayList<Long>();
        var reconstructions = new ArrayList<short[][]>();
        var packets = encodeWide(width, height, builder.build(), pictures, fields, bins, reconstructions);
        var mbs = (width / 16) * (height / 16);
        var chroma = format == ChromaFormat.YUV420 ? 64 : format == ChromaFormat.YUV422 ? 128 : 256;
        var raw = (long) (256 + 2 * chroma) * depth * mbs / (fields ? 2 : 1);
        // first_mb_in_slice counts macroblock pairs in MBAFF frames and the macroblocks of one field in field pictures
        var units = coding == null ? mbs : mbs / 2;
        var result = check(packets, bins, raw, units, limit);
        assertTrue(result[1] > 0, "some pictures need cabac_zero_words (" + result[0] + " words)");
        assertTrue(result[2] > 0, "the words of some picture are spread over several slices");
        var smalt = ExtendedFormatsTest.decodeSmalt(packets);
        ExtendedFormatsTest.assertSame(reconstructions, smalt, "Smalt");
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var stream = new ByteArrayOutputStream();
            packets.forEach(packet -> stream.writeBytes(packet.data()));
            ExtendedFormatsTest.assertSame(smalt, ExtendedFormatsTest.decodeFfmpeg("zerowords-" + coding + "-" + format,
                    stream.toByteArray(), format, depth, width, height), "ffmpeg");
        }
    }
}
