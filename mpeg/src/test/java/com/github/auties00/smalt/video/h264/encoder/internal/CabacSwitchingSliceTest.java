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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Decodes SP and SI slices coded with CABAC, which no profile allows and no encoder writes, and requires the same
 * pictures as the CAVLC coding of the same syntax elements. The CABAC slices are written with the encoder's
 * arithmetic coding engine: a 32x32 sequence whose IDR picture holds four I_PCM macroblocks (CAVLC, picture
 * parameter set 0), followed either by an SI picture of three SI macroblocks and one I_NxN macroblock, every block
 * predicted in DC mode without residual, or by an SP picture of four P_Skip macroblocks, each coded with picture
 * parameter set 0 (CAVLC) or 1 (CABAC). The SI picture exercises the {@code mb_type} prefix of SI slices and the
 * contexts that treat an SI neighbor as not I_NxN.
 */
@DisplayName("H.264 SP and SI slices coded with CABAC")
class CabacSwitchingSliceTest {
    private static final int QP = 30;

    private static final int QS = 28;

    private static byte[] startCode(byte[] nal) {
        var out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static byte[] sps() {
        return H264BitWriter.nal(3, 7, new H264BitWriter().bits(88, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2).ue(1)
                .bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing());
    }

    private static byte[] pps(int id, boolean cabac) {
        return H264BitWriter.nal(3, 8, new H264BitWriter().ue(id).ue(0).flag(cabac).bit(0).ue(0).ue(0).ue(0).bit(0)
                .bits(0, 2).se(0).se(0).se(1).bit(1).bit(0).bit(0).trailing());
    }

    private static byte[] idr() {
        var writer = new H264BitWriter().ue(0).ue(7).ue(0).bits(0, 4).ue(0).bit(0).bit(0).se(QP - 26).ue(1);
        for (var mb = 0; mb < 4; mb++) {
            writer.ue(25);
            while (!writer.aligned()) {
                writer.bit(0);
            }
            for (var i = 0; i < 384; i++) {
                writer.bits(16 + (mb * 59 + (i % 16) * 7 + (i / 16) * 11 + i % 5) % 200, 8);
            }
        }
        return H264BitWriter.nal(3, 5, writer.trailing());
    }

    /** The payload of a slice: the header {@code header} writes, then CABAC data {@code body} writes. */
    private static byte[] cabacSlice(Consumer<MsbBitWriter> header, int table, Consumer<CabacEncoder> body) {
        var out = new MsbBitWriter();
        header.accept(out);
        out.alignWithOnes();
        var cabac = new CabacEncoder();
        cabac.initContexts(table, QP);
        cabac.start(out);
        body.accept(cabac);
        cabac.flush();
        out.alignWithZeros();
        return out.toByteArray();
    }

    /** The SI picture with CAVLC: SI, SI, I_NxN, SI, every block predicted, no residual. */
    private static byte[] cavlcSi() {
        var writer = new H264BitWriter().ue(0).ue(4).ue(0).bits(1, 4).bit(0).se(QP - 26).se(QS - 26).ue(1);
        for (var mb = 0; mb < 4; mb++) {
            writer.ue(mb == 2 ? 1 : 0);
            for (var blk = 0; blk < 16; blk++) {
                writer.bit(1);
            }
            writer.ue(0).ue(3);
        }
        return H264BitWriter.nal(2, 1, writer.trailing());
    }

    /** The same SI picture with CABAC. */
    private static byte[] cabacSi() {
        Consumer<MsbBitWriter> header = out -> {
            out.writeUe(0);
            out.writeUe(4);
            out.writeUe(1);
            out.write(1, 4);
            out.writeBit(0);
            out.writeSe(QP - 26);
            out.writeSe(QS - 26);
            out.writeUe(1);
        };
        return H264BitWriter.nal(2, 1, cabacSlice(header, 0, cabac -> {
            boolean[] si = {true, true, false, true};
            for (var mb = 0; mb < 4; mb++) {
                var left = mb % 2 == 1 ? mb - 1 : -1;
                var top = mb >= 2 ? mb - 2 : -1;
                if (si[mb]) {
                    var inc = (left >= 0 && !si[left] ? 1 : 0) + (top >= 0 && !si[top] ? 1 : 0);
                    cabac.decision(inc, 0);
                } else {
                    // the prefix bin 1, then the I slice mb_type I_NxN, whose context counts the neighbors
                    // that are not I_NxN, SI macroblocks included (9.3.3.1.1.3)
                    var prefix = (left >= 0 && !si[left] ? 1 : 0) + (top >= 0 && !si[top] ? 1 : 0);
                    cabac.decision(prefix, 1);
                    var inc = (left >= 0 ? 1 : 0) + (top >= 0 ? 1 : 0);
                    cabac.decision(3 + inc, 0);
                }
                for (var blk = 0; blk < 16; blk++) {
                    cabac.decision(68, 1);
                }
                cabac.decision(64, 0);
                for (var b8 = 0; b8 < 4; b8++) {
                    var condA = (b8 & 1) == 1 || left >= 0 ? 1 : 0;
                    var condB = b8 >= 2 || top >= 0 ? 1 : 0;
                    cabac.decision(73 + condA + 2 * condB, 0);
                }
                cabac.decision(77, 0);
                cabac.terminate(mb == 3 ? 1 : 0);
            }
        }));
    }

    /** The SP picture of four P_Skip macroblocks with CAVLC. */
    private static byte[] cavlcSp(boolean switching) {
        var writer = new H264BitWriter().ue(0).ue(3).ue(0).bits(1, 4).bit(0).bit(0).bit(0).se(QP - 26)
                .flag(switching).se(QS - 26).ue(1).ue(4);
        return H264BitWriter.nal(2, 1, writer.trailing());
    }

    /** The same SP picture with CABAC. */
    private static byte[] cabacSp(boolean switching) {
        Consumer<MsbBitWriter> header = out -> {
            out.writeUe(0);
            out.writeUe(3);
            out.writeUe(1);
            out.write(1, 4);
            out.writeBit(0);
            out.writeBit(0);
            out.writeBit(0);
            out.writeUe(0);
            out.writeSe(QP - 26);
            out.writeBit(switching ? 1 : 0);
            out.writeSe(QS - 26);
            out.writeUe(1);
        };
        return H264BitWriter.nal(2, 1, cabacSlice(header, 1, cabac -> {
            for (var mb = 0; mb < 4; mb++) {
                cabac.decision(11, 1);
                cabac.terminate(mb == 3 ? 1 : 0);
            }
        }));
    }

    private static List<byte[]> decode(byte[] picture) {
        var stream = new ByteArrayOutputStream();
        for (var nal : List.of(sps(), pps(0, false), pps(1, true), idr(), picture)) {
            stream.writeBytes(startCode(nal));
        }
        var frames = new ArrayList<byte[]>();
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
        try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(1).build())) {
            Consumer<VideoFrame> sink = frame -> {
                var p = (YuvPicture) frame.picture();
                var samples = new byte[32 * 32 + 2 * 16 * 16];
                for (var y = 0; y < 32; y++) {
                    System.arraycopy(p.y(), y * p.yStride(), samples, y * 32, 32);
                }
                for (var y = 0; y < 16; y++) {
                    System.arraycopy(p.u(), y * p.uvStride(), samples, 1024 + y * 16, 16);
                    System.arraycopy(p.v(), y * p.uvStride(), samples, 1280 + y * 16, 16);
                }
                frames.add(samples);
            };
            decoder.decode(new Packet(1, stream.toByteArray(), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), sink);
            decoder.flush(sink);
        }
        return frames;
    }

    @Test
    @DisplayName("an SI picture with SI and I_NxN macroblocks decodes as its CAVLC coding")
    void si() {
        var cavlc = decode(cavlcSi());
        var cabac = decode(cabacSi());
        assertEquals(2, cavlc.size());
        assertEquals(2, cabac.size());
        assertArrayEquals(cavlc.get(1), cabac.get(1));
        assertFalse(Arrays.equals(cavlc.get(0), cavlc.get(1)), "the SI picture is not a copy of the IDR picture");
    }

    @ParameterizedTest(name = "sp_for_switch_flag {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an SP picture of P_Skip macroblocks decodes as its CAVLC coding")
    void sp(boolean switching) {
        var cavlc = decode(cavlcSp(switching));
        var cabac = decode(cabacSp(switching));
        assertEquals(2, cavlc.size());
        assertEquals(2, cabac.size());
        assertArrayEquals(cavlc.get(1), cabac.get(1));
        assertFalse(Arrays.equals(cavlc.get(0), cavlc.get(1)), "the SP picture requantizes its prediction");
    }
}
