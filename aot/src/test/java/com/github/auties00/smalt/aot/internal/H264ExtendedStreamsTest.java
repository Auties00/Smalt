package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every stream of the extended H.264 workloads is coded in the profile, chroma format, bit depth,
 * transform bypass, interlacing and entropy coding mode it is meant to train the decoder in, read back from its
 * parameter sets and slice headers, that the interlaced streams code combed content, and that every stream decodes
 * to pictures of its format, the lossless streams to their input samples in every plane.
 */
@DisplayName("extended H.264 training streams")
class H264ExtendedStreamsTest {
    // the longest a round of an extended workload may take once its classes are loaded: a cycle of the training
    // runs every workload once, so a slow round delays the convergence of the whole run
    private static final long ROUND_LIMIT_NANOS = 30_000_000_000L;

    // a lossy stream's planes stay within this mean absolute error of the source, in 8-bit units: far above what
    // the encoder leaves at the workload bitrate and far below what a broken prediction or residual path produces
    private static final double MAX_MEAN_ERROR = 6;

    // the least share of the pairs of a frame of interlaced content that must code better as fields, and as
    // frames, so the macroblock pair streams train both kinds of pair and the neighbors between them
    private static final double MIN_PAIR_SHARE = 0.05;

    /**
     * What a stream's parameter sets declare.
     *
     * @param profileIdc the {@code profile_idc}
     * @param chroma the chroma format
     * @param bitDepth the luma and chroma bit depth
     * @param bypass whether {@code qpprime_y_zero_transform_bypass_flag} is set
     * @param frameMbsOnly whether {@code frame_mbs_only_flag} is set
     * @param mbaff whether {@code mb_adaptive_frame_field_flag} is set
     * @param cabac whether {@code entropy_coding_mode_flag} is set
     */
    record Expected(int profileIdc, ChromaFormat chroma, int bitDepth, boolean bypass, boolean frameMbsOnly,
                    boolean mbaff, boolean cabac) {
    }

    private static final List<Expected> EXPECTED = List.of(
            new Expected(110, ChromaFormat.YUV420, 10, false, true, false, true),
            new Expected(122, ChromaFormat.YUV422, 10, false, false, true, true),
            new Expected(122, ChromaFormat.YUV422, 8, false, false, false, true),
            new Expected(244, ChromaFormat.YUV444, 8, true, true, false, true),
            new Expected(244, ChromaFormat.YUV444, 8, false, false, true, true),
            new Expected(244, ChromaFormat.YUV444, 10, false, true, false, true),
            new Expected(244, ChromaFormat.YUV420, 8, true, true, false, false),
            new Expected(244, ChromaFormat.YUV422, 8, true, false, true, false),
            new Expected(100, ChromaFormat.MONOCHROME, 8, false, true, false, true),
            new Expected(100, ChromaFormat.YUV420, 8, false, false, true, true),
            new Expected(100, ChromaFormat.YUV420, 8, false, false, false, true),
            new Expected(100, ChromaFormat.YUV420, 8, false, false, false, false));

    static Stream<Arguments> streams() {
        var options = MpegWorkloads.extendedOptions();
        assertEquals(EXPECTED.size(), options.size());
        return IntStream.range(0, options.size()).mapToObj(i -> Arguments.of(i, options.get(i), EXPECTED.get(i)));
    }

    @ParameterizedTest(name = "stream {0}: {2}")
    @MethodSource("streams")
    @DisplayName("a stream declares its coding tools and decodes to its format, losslessly when it is lossless")
    void stream(int index, H264EncoderOptions options, Expected expected) {
        assertEquals(expected.frameMbsOnly(), options.fieldOrder() == FieldOrder.PROGRESSIVE, "interlaced input");
        var stream = MpegWorkloads.extendedStream(options);
        var config = stream.codecConfig();
        assertNotNull(config, "codec configuration");
        var info = H264StreamInfo.probe(config);
        assertEquals(expected.profileIdc(), info.profileIdc(), "profile_idc");
        assertEquals(expected.chroma(), info.chromaFormat(), "chroma format");
        assertEquals(expected.bitDepth(), info.bitDepthLuma(), "luma bit depth");
        if (expected.chroma() != ChromaFormat.MONOCHROME) {
            assertEquals(expected.bitDepth(), info.bitDepthChroma(), "chroma bit depth");
        }
        assertEquals(expected.bypass(), info.transformBypass(), "transform bypass");
        assertEquals(expected.frameMbsOnly(), info.frameMbsOnly(), "frame_mbs_only_flag");
        assertEquals(expected.mbaff(), info.mbAdaptiveFrameField(), "mb_adaptive_frame_field_flag");
        assertEquals(expected.cabac(), cabac(config), "entropy_coding_mode_flag");
        // CABAC streams reorder B pictures; CAVLC streams have none
        assertEquals(expected.cabac(), reordered(stream.packets()), "B pictures");
        checkSlices(config, stream.packets(), expected);

        var inputs = MpegWorkloads.trainingInput(options, MpegWorkloads.EXTENDED_DECODED_FRAMES);
        var presented = new ArrayList<Long>();
        // a decoded picture is valid only during the call, so each is checked as it arrives
        Consumer<VideoFrame> output = frame -> {
            presented.add(frame.pts());
            check(frame, inputs.get((int) frame.pts()), expected);
        };
        var decoder = MpegWorkloads.extendedDecoder(config);
        for (var packet : stream.packets()) {
            decoder.decode(packet.withTrackId(1), output);
        }
        decoder.flush(output);
        decoder.close();
        assertEquals(LongStream.range(0, MpegWorkloads.EXTENDED_DECODED_FRAMES).boxed().toList(), presented,
                "presentation order");
    }

    @ParameterizedTest(name = "top field first: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("interlaced content mixes pairs that code better as fields and as frames")
    void interlacedContent(boolean topFirst) {
        var frames = SyntheticVideo.interlaced(VideoStreams.WIDTH, VideoStreams.HEIGHT,
                MpegWorkloads.EXTENDED_DECODED_FRAMES, topFirst);
        var natural = SyntheticVideo.natural(VideoStreams.WIDTH, VideoStreams.HEIGHT,
                MpegWorkloads.EXTENDED_DECODED_FRAMES);
        var pairs = (VideoStreams.WIDTH / 16) * Math.ceilDiv(VideoStreams.HEIGHT, 32);
        for (var t = 0; t < frames.size(); t++) {
            var field = fieldPairs(frames.get(t));
            if (t % 6 == 5) {
                assertEquals(0, field, "field pairs of black frame " + t);
                continue;
            }
            assertTrue(field >= MIN_PAIR_SHARE * pairs, field + " field pairs of frame " + t);
            assertTrue(pairs - field >= MIN_PAIR_SHARE * pairs, pairs - field + " frame pairs of frame " + t);
            // without the stripes the fields agree, so the combing comes from the field motion alone
            assertEquals(0, fieldPairs(natural.get(t)), "field pairs of natural frame " + t);
        }
        var other = SyntheticVideo.interlaced(VideoStreams.WIDTH, VideoStreams.HEIGHT, 1, !topFirst).getFirst();
        assertFalse(Arrays.equals(frames.getFirst().y(), other.y()), "field order changes the content");
    }

    /**
     * Counts the macroblock pairs of a picture whose rows differ from their neighbors more than twice as much as
     * from the rows two below, which an adaptive encoder codes as field macroblocks.
     */
    private static int fieldPairs(YuvPicture picture) {
        var count = 0;
        var height = Math.ceilDiv(picture.height(), 32) * 32;
        for (var y = 0; y < height; y += 32) {
            for (var x = 0; x < picture.width(); x += 16) {
                long frameCost = 0;
                long fieldCost = 0;
                for (var row = 0; row < 30; row++) {
                    for (var col = 0; col < 16; col++) {
                        var v = luma(picture, x + col, y + row);
                        frameCost += Math.abs(v - luma(picture, x + col, y + row + 1));
                        fieldCost += Math.abs(v - luma(picture, x + col, y + row + 2));
                    }
                }
                if (2 * fieldCost < frameCost) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int luma(YuvPicture picture, int x, int y) {
        return picture.y()[Math.min(y, picture.height() - 1) * picture.yStride() + x] & 0xFF;
    }

    private static void check(VideoFrame frame, YuvPicture input, Expected expected) {
        var picture = frame.picture();
        var t = frame.pts();
        assertEquals(VideoStreams.WIDTH, picture.width());
        assertEquals(VideoStreams.HEIGHT, picture.height());
        Samples decoded;
        int chromaWidth;
        int chromaHeight;
        if (expected.bitDepth() > 8) {
            var wide = assertInstanceOf(Yuv16Picture.class, picture);
            assertEquals(expected.bitDepth(), wide.bitDepth());
            assertEquals(expected.chroma(), wide.chromaFormat());
            decoded = samples(wide);
            chromaWidth = wide.chromaWidth();
            chromaHeight = wide.chromaHeight();
        } else {
            var yuv = assertInstanceOf(YuvPicture.class, picture);
            assertEquals(8, yuv.bitDepth());
            assertEquals(expected.chroma(), yuv.chromaFormat());
            decoded = samples(yuv);
            chromaWidth = yuv.chromaWidth();
            chromaHeight = yuv.chromaHeight();
        }
        var source = reference(input, expected);
        var planes = expected.chroma() == ChromaFormat.MONOCHROME ? 1 : 3;
        var names = new String[]{"luma", "Cb", "Cr"};
        for (var c = 0; c < planes; c++) {
            var width = c == 0 ? picture.width() : chromaWidth;
            var height = c == 0 ? picture.height() : chromaHeight;
            var error = 0L;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var want = source.at(c, x, y);
                    var got = decoded.at(c, x, y);
                    if (expected.bypass() && want != got) {
                        throw new AssertionError(names[c] + " of frame " + t + " differs at (" + x + ", " + y + "): "
                                + want + " != " + got);
                    }
                    error += Math.abs(want - got);
                }
            }
            var mean = error / (double) (1 << (expected.bitDepth() - 8)) / ((long) width * height);
            assertTrue(mean < MAX_MEAN_ERROR, "mean " + names[c] + " error " + mean + " of frame " + t);
        }
    }

    /**
     * The samples of the planes of a picture.
     */
    @FunctionalInterface
    private interface Samples {
        /**
         * {@return the sample of a plane, 0 luma, 1 Cb and 2 Cr, at a position}
         */
        int at(int plane, int x, int y);
    }

    private static Samples samples(YuvPicture picture) {
        return (c, x, y) -> switch (c) {
            case 0 -> picture.y()[y * picture.yStride() + x] & 0xFF;
            case 1 -> picture.u()[y * picture.uvStride() + x] & 0xFF;
            default -> picture.v()[y * picture.uvStride() + x] & 0xFF;
        };
    }

    private static Samples samples(Yuv16Picture picture) {
        return (c, x, y) -> switch (c) {
            case 0 -> picture.y()[y * picture.yStride() + x] & 0xFFFF;
            case 1 -> picture.u()[y * picture.uvStride() + x] & 0xFFFF;
            default -> picture.v()[y * picture.uvStride() + x] & 0xFFFF;
        };
    }

    /**
     * {@return the samples the encoder codes for an input picture: those of the picture, shifted to the stream's
     * depth, when it has the stream's chroma layout or the stream is monochrome, otherwise those of the picture
     * resampled to the stream's layout and depth}
     */
    private static Samples reference(YuvPicture input, Expected expected) {
        if (input.chromaFormat() == expected.chroma() || expected.chroma() == ChromaFormat.MONOCHROME) {
            var shift = expected.bitDepth() - 8;
            var samples = samples(input);
            return (c, x, y) -> samples.at(c, x, y) << shift;
        }
        return samples(ImageOps.toYuv16(input, expected.chroma(), input.color(), expected.bitDepth()));
    }

    /**
     * Checks the {@code field_pic_flag} of every slice: set in every slice of a stream of field pictures, which has
     * slices of both parities, and clear in every slice of the other streams.
     */
    private static void checkSlices(byte[] config, List<Packet> packets, Expected expected) {
        var sps = rbsp(firstSps(config));
        // after the NAL header, profile_idc, the constraint flags and level_idc
        var bits = new int[]{4 * 8};
        readUe(sps, bits);
        if (expected.profileIdc() >= 100) {
            if (readUe(sps, bits) == 3) {
                assertEquals(0, readBit(sps, bits), "separate_colour_plane_flag");
            }
            readUe(sps, bits);
            readUe(sps, bits);
            readBit(sps, bits);
            assertEquals(0, readBit(sps, bits), "seq_scaling_matrix_present_flag");
        }
        var log2MaxFrameNum = readUe(sps, bits) + 4;
        var fieldPictures = !expected.frameMbsOnly() && !expected.mbaff();
        var parities = new HashSet<Integer>();
        var slices = 0;
        for (var packet : packets) {
            for (var nal : nalUnits(config, packet.data())) {
                var type = nal[0] & 0x1F;
                if (type != 1 && type != 5) {
                    continue;
                }
                slices++;
                var slice = rbsp(nal);
                var position = new int[]{8};
                readUe(slice, position);
                readUe(slice, position);
                readUe(slice, position);
                position[0] += log2MaxFrameNum;
                var field = !expected.frameMbsOnly() && readBit(slice, position) == 1;
                assertEquals(fieldPictures, field, "field_pic_flag of slice " + slices);
                if (field) {
                    parities.add(readBit(slice, position));
                }
            }
        }
        assertTrue(slices >= packets.size(), slices + " slices");
        if (fieldPictures) {
            assertEquals(2, parities.size(), "field parities");
        }
    }

    /**
     * {@return the NAL units of a packet, whose framing is that of the codec configuration: length-prefixed after
     * an {@code AVCDecoderConfigurationRecord}, Annex B otherwise}
     */
    private static List<byte[]> nalUnits(byte[] config, byte[] data) {
        var units = new ArrayList<byte[]>();
        if (config[0] == 1) {
            var lengthSize = (config[4] & 3) + 1;
            var position = 0;
            while (position + lengthSize <= data.length) {
                var length = 0;
                for (var i = 0; i < lengthSize; i++) {
                    length = length << 8 | data[position++] & 0xFF;
                }
                assertTrue(length > 0 && position + length <= data.length, "NAL unit length " + length);
                units.add(Arrays.copyOfRange(data, position, position + length));
                position += length;
            }
            assertEquals(data.length, position, "trailing bytes");
            return units;
        }
        var start = -1;
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (start >= 0) {
                    var end = i;
                    while (end > start && data[end - 1] == 0) {
                        end--;
                    }
                    units.add(Arrays.copyOfRange(data, start, end));
                }
                start = i + 3;
                i += 2;
            }
        }
        if (start >= 0) {
            units.add(Arrays.copyOfRange(data, start, data.length));
        }
        return units;
    }

    /**
     * {@return the first sequence parameter set NAL unit of an {@code AVCDecoderConfigurationRecord} or of Annex B
     * parameter sets}
     */
    private static byte[] firstSps(byte[] config) {
        if (config[0] == 1) {
            assertTrue((config[5] & 0x1F) > 0, "sequence parameter sets");
            var length = (config[6] & 0xFF) << 8 | config[7] & 0xFF;
            return Arrays.copyOfRange(config, 8, 8 + length);
        }
        return nalUnits(config, config).stream().filter(nal -> (nal[0] & 0x1F) == 7).findFirst().orElseThrow();
    }

    /**
     * {@return a NAL unit with its emulation prevention bytes removed}
     */
    private static byte[] rbsp(byte[] nal) {
        var out = new ByteArrayOutputStream(nal.length);
        var zeros = 0;
        for (var b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("the extended streams cover every chroma format, both bit depths, lossless and interlaced coding")
    void coverage() {
        for (var chroma : ChromaFormat.values()) {
            if (chroma != ChromaFormat.YUV440) {
                assertTrue(EXPECTED.stream().anyMatch(stream -> stream.chroma() == chroma), chroma.name());
            }
        }
        for (var chroma : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444)) {
            for (var depth : new int[]{8, 10}) {
                assertTrue(EXPECTED.stream().anyMatch(stream -> stream.chroma() == chroma && stream.bitDepth() == depth
                        && !stream.bypass()), chroma + " " + depth);
            }
            assertTrue(EXPECTED.stream().anyMatch(stream -> stream.chroma() == chroma && stream.bypass()),
                    chroma + " lossless");
            assertTrue(EXPECTED.stream().anyMatch(stream -> stream.chroma() == chroma && stream.mbaff()),
                    chroma + " macroblock pairs");
        }
        // 4:4:4 field pictures are trained by the separate-plane workloads
        for (var chroma : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422)) {
            assertTrue(EXPECTED.stream().anyMatch(stream -> stream.chroma() == chroma && !stream.frameMbsOnly()
                    && !stream.mbaff()), chroma + " field pictures");
        }
        for (var cabac : new boolean[]{false, true}) {
            assertTrue(EXPECTED.stream().anyMatch(stream -> stream.cabac() == cabac && stream.mbaff()),
                    "macroblock pairs, CABAC " + cabac);
            assertTrue(EXPECTED.stream().anyMatch(stream -> stream.cabac() == cabac && !stream.frameMbsOnly()
                    && !stream.mbaff()), "field pictures, CABAC " + cabac);
        }
        assertTrue(EXPECTED.stream().anyMatch(stream -> stream.bypass() && stream.mbaff()),
                "lossless macroblock pairs");
    }

    @Test
    @DisplayName("a round of the extended workloads stays within the time a training cycle allows")
    void roundTime() throws Exception {
        for (var name : List.of("h264-encode-extended", "h264-decode-extended")) {
            var workload = Workloads.of(TrainingFamily.MPEG).stream()
                    .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow().create();
            workload.round();
            var start = System.nanoTime();
            workload.round();
            var elapsed = System.nanoTime() - start;
            assertTrue(elapsed < ROUND_LIMIT_NANOS, name + " took " + elapsed / 1_000_000 + " ms");
        }
    }

    /**
     * {@return whether a packet in decoding order presents before one decoded earlier, which only reordered
     * B pictures do}
     */
    private static boolean reordered(List<Packet> packets) {
        var latest = Long.MIN_VALUE;
        for (var packet : packets) {
            if (packet.pts() < latest) {
                return true;
            }
            latest = Math.max(latest, packet.pts());
        }
        return false;
    }

    /**
     * Reads {@code entropy_coding_mode_flag} from the first picture parameter set of an
     * {@code AVCDecoderConfigurationRecord} or of Annex B parameter sets.
     */
    private static boolean cabac(byte[] config) {
        byte[] pps;
        if (config[0] == 1) {
            var position = 5;
            var sps = config[position++] & 0x1F;
            for (var i = 0; i < sps; i++) {
                position += 2 + ((config[position] & 0xFF) << 8 | config[position + 1] & 0xFF);
            }
            assertTrue((config[position] & 0xFF) > 0, "picture parameter sets");
            var length = (config[position + 1] & 0xFF) << 8 | config[position + 2] & 0xFF;
            pps = Arrays.copyOfRange(config, position + 3, position + 3 + length);
        } else {
            pps = nalUnits(config, config).stream().filter(nal -> (nal[0] & 0x1F) == 8).findFirst().orElseThrow();
        }
        assertEquals(8, pps[0] & 0x1F, "picture parameter set NAL unit");
        pps = rbsp(pps);
        var bits = new int[]{8};
        readUe(pps, bits);
        readUe(pps, bits);
        return readBit(pps, bits) == 1;
    }

    private static int readBit(byte[] data, int[] bits) {
        var bit = data[bits[0] >> 3] >> (7 - (bits[0] & 7)) & 1;
        bits[0]++;
        return bit;
    }

    private static int readUe(byte[] data, int[] bits) {
        var zeros = 0;
        while (readBit(data, bits) == 0) {
            zeros++;
        }
        var value = 0;
        for (var i = 0; i < zeros; i++) {
            value = value << 1 | readBit(data, bits);
        }
        return (1 << zeros) - 1 + value;
    }
}
