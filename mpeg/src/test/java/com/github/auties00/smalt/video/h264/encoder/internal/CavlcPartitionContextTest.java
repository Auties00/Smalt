package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264BitWriter;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.internal.H264Tables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks the CAVLC {@code nC} rule of slice data partitioning (ITU-T H.264 9.2.1): with
 * {@code constrained_intra_pred_flag} set, the residual of an intra macroblock in partition B is coded
 * as if its inter neighbors were unavailable, so that partition B decodes without partition C. A
 * 32x16 sequence (constrained intra prediction, deblocking off) holds an IDR picture of two I_PCM
 * macroblocks and a P picture whose first macroblock is P_L0_16x16 with three coefficients in every
 * luma block and whose second is I_NxN with residual; the P picture is coded once as a single slice and
 * once as partitions A, B and C, its residual written with the encoder's CAVLC block writer under the
 * {@code nC} each coding requires, which selects another {@code coeff_token} table at the left column
 * of the intra macroblock. Both codings must decode to the same picture.
 */
@DisplayName("H.264 CAVLC nC of intra macroblocks in slice data partition B")
class CavlcPartitionContextTest {
    private static final int QP = 28;

    /** The levels of every luma block of the inter macroblock in raster order: three in scan order. */
    private static final int[] INTER_LEVELS = levels(new int[]{1, -1, 1});

    /** The levels of every luma block of the intra macroblock: two in scan order, the last a trailing one. */
    private static final int[] INTRA_LEVELS = levels(new int[]{3, 0, 0, -1});

    private static int[] levels(int[] inScanOrder) {
        var result = new int[16];
        for (var i = 0; i < inScanOrder.length; i++) {
            result[H264Tables.ZIGZAG_4X4[i]] = inScanOrder[i];
        }
        return result;
    }

    private static byte[] startCode(byte[] nal) {
        var out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /** Extended profile, 2x1 macroblocks, frame_num of 4 bits, picture order count type 2. */
    private static byte[] sps() {
        return H264BitWriter.nal(3, 7, new H264BitWriter().bits(88, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2).ue(1)
                .bit(0).ue(1).ue(0).bit(1).bit(1).bit(0).bit(0).trailing());
    }

    /** CAVLC, deblocking filter control present, constrained_intra_pred_flag 1. */
    private static byte[] pps() {
        return H264BitWriter.nal(3, 8, new H264BitWriter().ue(0).ue(0).bit(0).bit(0).ue(0).ue(0).ue(0).bit(0)
                .bits(0, 2).se(0).se(0).se(0).bit(1).bit(1).bit(0).trailing());
    }

    private static byte[] idr() {
        var writer = new H264BitWriter().ue(0).ue(7).ue(0).bits(0, 4).ue(0).bit(0).bit(0).se(QP - 26).ue(1);
        for (var mb = 0; mb < 2; mb++) {
            writer.ue(25);
            while (!writer.aligned()) {
                writer.bit(0);
            }
            for (var i = 0; i < 384; i++) {
                writer.bits(40 + (mb * 71 + (i % 16) * 9 + (i / 16) * 5 + i % 7) % 170, 8);
            }
        }
        return H264BitWriter.nal(3, 5, writer.trailing());
    }

    /** Writes the slice header of the P picture, for a slice (NAL unit type 1) or a partition A (type 2). */
    private static void header(MsbBitWriter out, boolean partitioned) {
        out.writeUe(0);
        out.writeUe(5);
        out.writeUe(0);
        out.write(1, 4);
        out.writeBit(0);
        out.writeBit(0);
        out.writeBit(0);
        out.writeSe(0);
        out.writeUe(1);
        if (partitioned) {
            out.writeUe(0);
        }
    }

    /** Writes the category 2 elements of the inter macroblock: P_L0_16x16 with a zero vector, every luma block coded. */
    private static void interLayer(MsbBitWriter out) {
        out.writeUe(0);
        out.writeUe(0);
        out.writeSe(0);
        out.writeSe(0);
        out.writeUe(Tables.CBP_INTER_TO_CODE[15]);
        out.writeSe(0);
    }

    /** Writes the category 2 elements of the intra macroblock: I_NxN with predicted DC modes, every luma block coded. */
    private static void intraLayer(MsbBitWriter out) {
        out.writeUe(0);
        out.writeUe(5);
        for (var blk = 0; blk < 16; blk++) {
            out.writeBit(1);
        }
        out.writeUe(0);
        out.writeUe(Tables.CBP_INTRA_TO_CODE[15]);
        out.writeSe(0);
    }

    /**
     * Writes the sixteen luma blocks of a macroblock in decoding order, deriving each {@code nC} from the
     * blocks of this macroblock and, when {@code left} is not {@code null}, from the coefficient counts of the
     * left macroblock (by raster block position), and records the counts of this macroblock.
     */
    private static void residual(MsbBitWriter out, int[] levels, int[] left, int[] counts) {
        var writer = new CavlcWriter();
        for (var blk = 0; blk < 16; blk++) {
            var x = (blk >> 2 & 1) * 2 + (blk & 1);
            var y = (blk >> 3) * 2 + (blk >> 1 & 1);
            var a = x > 0 ? counts[y * 4 + x - 1] : left != null ? left[y * 4 + 3] : -1;
            var b = y > 0 ? counts[(y - 1) * 4 + x] : -1;
            var nC = a >= 0 && b >= 0 ? (a + b + 1) >> 1 : a >= 0 ? a : Math.max(b, 0);
            counts[y * 4 + x] = writer.writeBlock(out, levels, 0, H264Tables.ZIGZAG_4X4, 0, 16, nC);
        }
    }

    private static byte[] finish(MsbBitWriter out) {
        out.writeTrailingBits();
        return out.toByteArray();
    }

    /** The P picture as one slice: the intra macroblock's nC counts the inter macroblock's coefficients. */
    private static List<byte[]> slice() {
        var out = new MsbBitWriter();
        header(out, false);
        var interCounts = new int[16];
        interLayer(out);
        residual(out, INTER_LEVELS, null, interCounts);
        intraLayer(out);
        residual(out, INTRA_LEVELS, interCounts, new int[16]);
        return List.of(H264BitWriter.nal(2, 1, finish(out)));
    }

    /** The P picture as partitions: partition B codes the intra residual with the inter neighbor unavailable. */
    private static List<byte[]> partitions() {
        var a = new MsbBitWriter();
        header(a, true);
        interLayer(a);
        intraLayer(a);
        var b = new MsbBitWriter();
        b.writeUe(0);
        residual(b, INTRA_LEVELS, null, new int[16]);
        var c = new MsbBitWriter();
        c.writeUe(0);
        residual(c, INTER_LEVELS, null, new int[16]);
        return List.of(H264BitWriter.nal(2, 2, finish(a)), H264BitWriter.nal(2, 3, finish(b)),
                H264BitWriter.nal(2, 4, finish(c)));
    }

    private static List<byte[]> decode(List<byte[]> picture, int threads) {
        var stream = new ByteArrayOutputStream();
        for (var nal : List.of(sps(), pps(), idr())) {
            stream.writeBytes(startCode(nal));
        }
        for (var nal : picture) {
            stream.writeBytes(startCode(nal));
        }
        var frames = new ArrayList<byte[]>();
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
        try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(threads).build())) {
            Consumer<VideoFrame> sink = frame -> {
                var p = (YuvPicture) frame.picture();
                var samples = new byte[32 * 16 + 2 * 16 * 8];
                for (var y = 0; y < 16; y++) {
                    System.arraycopy(p.y(), y * p.yStride(), samples, y * 32, 32);
                }
                for (var y = 0; y < 8; y++) {
                    System.arraycopy(p.u(), y * p.uvStride(), samples, 512 + y * 16, 16);
                    System.arraycopy(p.v(), y * p.uvStride(), samples, 640 + y * 16, 16);
                }
                frames.add(samples);
            };
            decoder.decode(new Packet(1, stream.toByteArray(), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), sink);
            decoder.flush(sink);
        }
        return frames;
    }

    @ParameterizedTest(name = "threads {0}")
    @ValueSource(ints = {1, 3})
    @DisplayName("an intra macroblock's residual in partition B ignores the coefficients of its inter neighbor")
    void constrainedIntra(int threads) {
        var whole = decode(slice(), threads);
        var partitioned = decode(partitions(), threads);
        assertEquals(2, whole.size());
        assertEquals(2, partitioned.size());
        assertArrayEquals(whole.get(1), partitioned.get(1));
        assertFalse(Arrays.equals(whole.get(0), whole.get(1)), "the P picture adds residual to its prediction");
    }
}
