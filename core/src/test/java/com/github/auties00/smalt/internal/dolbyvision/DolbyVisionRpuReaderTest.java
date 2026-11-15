package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.DolbyVisionRpus.Kind;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how the RPU of an access unit is found: the framings of HEVC, the ITU-T T.35 and EMDF wrapping of AV1, as the
 * dolby_vision crate of dovi_tool writes it, and that damage of any kind reads as no RPU.
 */
@DisplayName("Dolby Vision RPU reader")
class DolbyVisionRpuReaderTest {
    /**
     * A profile 5 RPU of dovi_tool's generator: {@code DoviRpu::profile5_config} of the dolby_vision crate (MIT, commit
     * 41dae64), default configuration, written by {@code write_hevc_unspec62_nalu}.
     */
    static final String GENERATED_NAL = "7c01190809004061b6506f003ff801ffc00fffd0000008000006800000400000340000030200000301c80000c7c1a448003f"
            + "16c110c8000042fa95c0000003000200000300020000030010ae7fa8ffa8ffa8d0ae7fa8ffa8ffa8d0ae7fffc00000030000"
            + "0300000300190803e03854c0100a0000030000030000030024180fa000040000030000a0301c004004000209007fc000450b"
            + "0100000300c80245a780";

    /** The same RPU written by {@code write_av1_rpu_metadata_obu_t35_complete}. */
    static final String GENERATED_T35 = "b5003b0000080037cd083200809004061b6506f003ff801ffc00fffd000000800000680000040000034000002000001c8000"
            + "0c7c1a448003f16c110c8000042fa95c0000000020000000200000010ae7fa8ffa8ffa8d0ae7fa8ffa8ffa8d0ae7fffc0000"
            + "00000000000190803e03854c0100a0000000000000024180fa0000400000000a0301c004004000209007fc000450b0100000"
            + "0c80245a78002007";

    private static DolbyVisionRpu.Mapping curves(int pivots) {
        var pieces = new DolbyVisionRpu.Piece[pivots - 1];
        Arrays.fill(pieces, new DolbyVisionRpu.Piece(false, 2, new long[]{-3, 1L << 23, -(1L << 21) - 5}));
        var values = new int[pivots];
        for (var index = 0; index < pivots; index++) {
            values[index] = 1 + index * 1000 / pivots;
        }
        var curve = new DolbyVisionRpu.Curve(values, pieces);
        return new DolbyVisionRpu.Mapping(4, 23, false, new DolbyVisionRpu.Curve[]{curve, curve, curve});
    }

    private static DolbyVisionRpus rpus() {
        return DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(curves(3))).iptPqC2();
    }

    private static void assertCurves(DolbyVisionRpu rpu, int pivots) {
        assertNotNull(rpu);
        var expected = curves(pivots);
        var mapping = rpu.mapping();
        assertEquals(4, mapping.rpuId());
        assertEquals(23, mapping.denominatorBits());
        for (var component = 0; component < 3; component++) {
            assertArrayEquals(expected.curves()[component].pivots(), mapping.curves()[component].pivots());
            for (var piece = 0; piece < pivots - 1; piece++) {
                assertArrayEquals(expected.curves()[component].pieces()[piece].coefficients(),
                        mapping.curves()[component].pieces()[piece].coefficients());
            }
        }
        var management = rpu.displayManagement();
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB, management.yccToRgb());
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_OFFSET, management.yccToRgbOffset());
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS, management.rgbToLms());
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] annexB(byte[] nal) {
        return join(new byte[]{0, 0, 0, 1}, nal);
    }

    @Test
    @DisplayName("finds an HEVC RPU in Annex B and in length-prefixed access units")
    void hevc() {
        var nal = rpus().hevcNal();
        assertCurves(new DolbyVisionRpuReader(Codec.HEVC, null).read(annexB(nal)), 3);
        assertCurves(new DolbyVisionRpuReader(Codec.HEVC, null).read(DolbyVisionRpus.appendNal(new byte[0], nal)), 3);
        var slice = annexB(new byte[]{0x02, 0x01, (byte) 0x80, 0x55});
        assertCurves(new DolbyVisionRpuReader(Codec.HEVC, null).read(join(slice, annexB(nal))), 3);
        assertNull(new DolbyVisionRpuReader(Codec.HEVC, null).read(slice));
    }

    /** Wraps ITU-T T.35 data, its country code first, in a metadata OBU with a size field. */
    private static byte[] t35Obu(byte[] t35) {
        var size = t35.length + 1;
        var out = new ByteArrayOutputStream();
        out.write(5 << 3 | 2);
        out.write(size & 0x7F | (size > 127 ? 0x80 : 0));
        if (size > 127) {
            out.write(size >> 7);
        }
        out.write(4);
        out.writeBytes(t35);
        return out.toByteArray();
    }

    private static void assertSameRpu(DolbyVisionRpu expected, DolbyVisionRpu actual) {
        assertNotNull(actual);
        assertEquals(expected.profile(), actual.profile());
        assertEquals(expected.vdrBitDepth(), actual.vdrBitDepth());
        assertEquals(expected.mapping().denominatorBits(), actual.mapping().denominatorBits());
        for (var component = 0; component < 3; component++) {
            var left = expected.mapping().curves()[component];
            var right = actual.mapping().curves()[component];
            assertArrayEquals(left.pivots(), right.pivots());
            assertEquals(left.pieces().length, right.pieces().length);
            for (var piece = 0; piece < left.pieces().length; piece++) {
                assertEquals(left.pieces()[piece].mmr(), right.pieces()[piece].mmr());
                assertArrayEquals(left.pieces()[piece].coefficients(), right.pieces()[piece].coefficients());
            }
        }
        assertArrayEquals(expected.displayManagement().yccToRgb(), actual.displayManagement().yccToRgb());
        assertArrayEquals(expected.displayManagement().yccToRgbOffset(), actual.displayManagement().yccToRgbOffset());
        assertArrayEquals(expected.displayManagement().rgbToLms(), actual.displayManagement().rgbToLms());
    }

    @Test
    @DisplayName("reads what dovi_tool's generator writes, in both framings: the identity curves and IPT-PQ-c2 matrices")
    void generated() {
        var hevc = new DolbyVisionRpuReader(Codec.HEVC, null).read(annexB(HexFormat.of().parseHex(GENERATED_NAL)));
        assertNotNull(hevc);
        assertEquals(5, hevc.profile());
        assertEquals(12, hevc.vdrBitDepth());
        assertEquals(23, hevc.mapping().denominatorBits());
        for (var curve : hevc.mapping().curves()) {
            assertArrayEquals(new int[]{0, 1023}, curve.pivots());
            assertArrayEquals(new long[]{0, 1L << 23}, curve.pieces()[0].coefficients());
        }
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB, hevc.displayManagement().yccToRgb());
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_OFFSET, hevc.displayManagement().yccToRgbOffset());
        assertArrayEquals(DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS, hevc.displayManagement().rgbToLms());
        var av1 = new DolbyVisionRpuReader(Codec.AV1, null).read(t35Obu(HexFormat.of().parseHex(GENERATED_T35)));
        assertSameRpu(hevc, av1);
    }

    @Test
    @DisplayName("reads dovi_tool's T.35 payloads of real AV1 streams as their regular RPUs")
    void realAv1() throws IOException {
        var payload = Files.readAllBytes(Conformance.fetchListed("dovi-tool-av1-p5-01.bin"));
        var reference = Files.readAllBytes(Conformance.fetchListed("dovi-tool-av1-p5-01-ref.bin"));
        var unescaped = NalUnits.unescape(reference, 4, reference.length - 4);
        var expected = DolbyVisionRpu.parse(unescaped, 0, unescaped.length);
        assertEquals(5, expected.profile());
        var t35 = new byte[payload.length + 1];
        t35[0] = (byte) 0xB5;
        System.arraycopy(payload, 0, t35, 1, payload.length);
        assertSameRpu(expected, new DolbyVisionRpuReader(Codec.AV1, null).read(t35Obu(t35)));
    }

    @Test
    @DisplayName("notes an enhancement layer NAL unit, and only that one")
    void enhancementLayer() {
        var reader = new DolbyVisionRpuReader(Codec.HEVC, null);
        reader.read(annexB(rpus().hevcNal()));
        assertFalse(reader.enhancementLayer());
        reader.read(annexB(new byte[]{0x7E, 0x01, 0x55, 0x55}));
        assertTrue(reader.enhancementLayer());
    }

    @Test
    @DisplayName("finds the last valid RPU of an access unit")
    void last() {
        var first = DolbyVisionRpus.of(Kind.PROFILE_8);
        var unit = join(annexB(first.hevcNal()), annexB(rpus().hevcNal()));
        assertEquals(5, new DolbyVisionRpuReader(Codec.HEVC, null).read(unit).profile());
    }

    @Test
    @DisplayName("reads a video parameter set's layer count")
    void layers() {
        var single = new DolbyVisionRpuReader(Codec.HEVC, null);
        single.read(annexB(new byte[]{0x40, 0x01, 0x0C, 0x01, (byte) 0xFF}));
        assertFalse(single.layered());
        var two = new DolbyVisionRpuReader(Codec.HEVC, null);
        two.read(annexB(new byte[]{0x40, 0x01, 0x0C, 0x11, (byte) 0xFF}));
        assertTrue(two.layered());
    }

    @Test
    @DisplayName("finds an AV1 RPU in a metadata OBU, with its T.35 and EMDF wrapping removed")
    void av1() {
        var obu = rpus().av1Obu();
        var delimiter = new byte[]{0x12, 0x00};
        var frame = new byte[]{0x32, 0x03, 1, 2, 3};
        assertCurves(new DolbyVisionRpuReader(Codec.AV1, null).read(join(delimiter, obu, frame)), 3);
        assertNull(new DolbyVisionRpuReader(Codec.AV1, null).read(join(delimiter, frame)));
    }

    @Test
    @DisplayName("reads an EMDF payload size of two groups")
    void largePayload() {
        var large = DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(curves(9))).iptPqC2();
        assertTrue(large.rpu().length > 257);
        assertCurves(new DolbyVisionRpuReader(Codec.AV1, null).read(large.av1Obu()), 9);
    }

    @Test
    @DisplayName("takes the OBU header extension and an OBU without a size field")
    void obuForms() {
        var payload = rpus().av1Metadata();
        var sized = rpus().av1Obu();
        var extended = sized.clone();
        extended[0] |= 0x04;
        var withExtension = new byte[extended.length + 1];
        withExtension[0] = extended[0];
        withExtension[1] = 0;
        System.arraycopy(extended, 1, withExtension, 2, extended.length - 1);
        assertCurves(new DolbyVisionRpuReader(Codec.AV1, null).read(withExtension), 3);
        var unsized = new byte[payload.length + 1];
        unsized[0] = 5 << 3;
        System.arraycopy(payload, 0, unsized, 1, payload.length);
        assertCurves(new DolbyVisionRpuReader(Codec.AV1, null).read(unsized), 3);
    }

    @Test
    @DisplayName("ignores other metadata: another type, country, provider or EMDF header")
    void otherMetadata() {
        var sizeBytes = rpus().av1Obu().length - rpus().av1Metadata().length;
        for (var position : new int[]{0, 1, 2, 3, 5, 8, 9, 10, 11}) {
            var obu = rpus().av1Obu();
            obu[sizeBytes + position] ^= 0x10;
            assertNull(new DolbyVisionRpuReader(Codec.AV1, null).read(obu), "byte " + position);
        }
    }

    @Test
    @DisplayName("reads every truncation and every damaged byte as no RPU or a parsed one, never failing")
    void damage() {
        var obu = rpus().av1Obu();
        var nal = annexB(rpus().hevcNal());
        for (var length = 0; length < obu.length; length++) {
            assertNull(new DolbyVisionRpuReader(Codec.AV1, null).read(Arrays.copyOf(obu, length)), "AV1 length " + length);
        }
        for (var length = 0; length < nal.length; length++) {
            new DolbyVisionRpuReader(Codec.HEVC, null).read(Arrays.copyOf(nal, length));
        }
        for (var index = 0; index < obu.length; index++) {
            var damaged = obu.clone();
            damaged[index] ^= (byte) 0x5A;
            new DolbyVisionRpuReader(Codec.AV1, null).read(damaged);
        }
        for (var index = 0; index < nal.length; index++) {
            var damaged = nal.clone();
            damaged[index] ^= (byte) 0x5A;
            new DolbyVisionRpuReader(Codec.HEVC, null).read(damaged);
        }
    }
}
