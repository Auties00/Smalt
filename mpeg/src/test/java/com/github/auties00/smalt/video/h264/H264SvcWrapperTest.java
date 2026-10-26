package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the field pictures and macroblock-adaptive frames of the AVC conformance streams through the scalable
 * decoding process (ITU-T H.264 Annex F): {@link H264SvcWrapper} turns each stream into a scalable stream whose
 * base layer is the AVC stream and whose highest layer is a skipped quality refinement of it, and the decoder
 * outputs that refinement. With transform coefficient level prediction the refinement reconstructs the base
 * layer's decoding, so every frame must match the stream's reference decoding; in the streams whose macroblocks
 * change the quantization parameter the refinement rescales the levels to the quantization parameter of the slice,
 * and the expected MD5 is the JSVM 9.19.15 decoder's decoding of the wrapped stream. Without level prediction the
 * refinement predicts intra macroblocks as Intra_Base macroblocks; the expected MD5 of the macroblock-adaptive
 * streams is again the JSVM decoder's ({@code tools/testdata/README.md}). The streams are the {@code full} tier
 * archives of {@link H264ConformanceTest}.
 */
@DisplayName("H.264 interlaced AVC conformance streams decoded as scalable streams")
class H264SvcWrapperTest {
    /**
     * The conformance streams with field pictures or macroblock-adaptive frames whose levels keep their
     * quantization parameter, so that the refinement with level prediction reproduces their reference decoding.
     */
    private static final Set<String> REFERENCE = Set.of(
            "CABREF3_Sand_D", "CAFI1_SVA_C", "CAMA1_Sony_C", "CAMA1_TOSHIBA_B", "CAMA3_Sand_E", "CAMACI3_Sony_C",
            "CAMANL1_TOSHIBA_B", "CAMANL2_TOSHIBA_B", "CAMANL3_Sand_E", "CAMASL3_Sony_B", "CAMP_MOT_MBAFF_L30",
            "CAMP_MOT_MBAFF_L31", "CANLMA2_Sony_C", "CANLMA3_Sony_C", "CAPA1_TOSHIBA_B", "CAPAMA3_Sand_F",
            "CVCANLMA2_Sony_C", "CVFI1_SVA_C", "CVFI1_Sony_D", "CVFI2_SVA_C", "CVFI2_Sony_H", "CVMA1_Sony_D",
            "CVMA1_TOSHIBA_B", "CVMANL1_TOSHIBA_B", "CVMANL2_TOSHIBA_B", "CVMP_MOT_FLD_L30_B", "CVMP_MOT_FRM_L31_B",
            "CVNLFI1_Sony_C", "CVNLFI2_Sony_H", "CVPA1_TOSHIBA_B", "FI1_Sony_E", "MR6_BT_B", "MR7_BT_B", "MR8_BT_B",
            "MR9_BT_B", "Sharp_MP_Field_1_B", "Sharp_MP_Field_2_B", "Sharp_MP_Field_3_B", "Sharp_MP_PAFF_1r2",
            "Sharp_MP_PAFF_2r", "cabac_mot_fld0_full", "cabac_mot_mbaff0_full", "cabac_mot_picaff0_full",
            "cama1_vtc_c", "cama2_vtc_b", "cama3_vtc_b", "cavlc_mot_fld0_full_B", "cavlc_mot_mbaff0_full_B",
            "cavlc_mot_picaff0_full_B", "FREXT01_JVC_D", "FREXT02_JVC_C", "FRExt2_Panasonic_C", "FRExt4_Panasonic_B",
            "HCAFF1_HHI_B", "HCAMFF1_HHI_B", "HCHP3_HHI_A", "HPCAFLNL_BRCM_C", "HPCAFL_BRCM_C", "HPCAMAPALQ_BRCM_B",
            "HPCVFLNL_BRCM_A", "HPCVFL_BRCM_A", "brcm_freh10", "brcm_freh11", "brcm_freh4", "brcm_freh5", "brcm_freh6",
            "freh7_b");

    /**
     * The MD5 of the JSVM decoder's decoding of the wrapped streams with level prediction whose macroblocks
     * change the quantization parameter. CVMAPAQP3_Sony_E, which does too, is left out: the JSVM decoder stops on
     * its field pictures.
     */
    private static final Map<String, String> RESCALED = Map.of(
            "CVMAQP2_Sony_G", "3efc38831d789422e8d84ef12ca0fb9c",
            "CVMAQP3_Sony_D", "c7ae5a5354e8600f14f407dd155fddec");

    /**
     * The MD5 of the JSVM decoder's decoding of the wrapped macroblock-adaptive streams without level prediction.
     */
    private static final Map<String, String> INTRA_BASE = Map.ofEntries(
            Map.entry("CAMA1_Sony_C", "c234cc1b862b5b2ef958622fce77b7f5"),
            Map.entry("CAMA1_TOSHIBA_B", "21a52dd6f527031c3a07be99a0508d33"),
            Map.entry("CAMA3_Sand_E", "d8347f913504b1b0cfffafcee3a75863"),
            Map.entry("CAMACI3_Sony_C", "0f3edb4f003b8fb4838f6a35c572fe70"),
            Map.entry("CAMANL1_TOSHIBA_B", "6653cdad7eca45d243be14f9964b480c"),
            Map.entry("CAMANL2_TOSHIBA_B", "3f50053d2b63ffb563f529830609789e"),
            Map.entry("CAMANL3_Sand_E", "394f46590b0f2803e288a86672718c13"),
            Map.entry("CAMASL3_Sony_B", "199b15788d49b98064a386188fd04598"),
            Map.entry("CAMP_MOT_MBAFF_L30", "b360d7c05677d545fea9f35862aca50c"),
            Map.entry("CAMP_MOT_MBAFF_L31", "249f8b52b757de13d53d13cbbe017a41"),
            Map.entry("CANLMA2_Sony_C", "4769b8c29cd656707980734ab2908db1"),
            Map.entry("CANLMA3_Sony_C", "9d4a4ea410082abe9c5979ca4c0b209a"),
            Map.entry("CVCANLMA2_Sony_C", "4769b8c29cd656707980734ab2908db1"),
            Map.entry("CVMA1_Sony_D", "c234cc1b862b5b2ef958622fce77b7f5"),
            Map.entry("CVMA1_TOSHIBA_B", "21a52dd6f527031c3a07be99a0508d33"),
            Map.entry("CVMANL1_TOSHIBA_B", "6653cdad7eca45d243be14f9964b480c"),
            Map.entry("CVMANL2_TOSHIBA_B", "3f50053d2b63ffb563f529830609789e"),
            Map.entry("CVMAQP2_Sony_G", "655f8bdb1b9f75a7c373ec08d44f8a52"),
            Map.entry("CVMAQP3_Sony_D", "1bf54528c75b8736c59cebfb08fca87b"),
            Map.entry("cabac_mot_mbaff0_full", "284c559e362707932e20b985dac08356"),
            Map.entry("cavlc_mot_mbaff0_full_B", "059ee151f0785a1e0434adcbfd72ab9e"),
            Map.entry("FREXT01_JVC_D", "0ad68d9239859df14582f37f9d4ba40f"),
            Map.entry("FREXT02_JVC_C", "0ad68d9239859df14582f37f9d4ba40f"),
            Map.entry("HCAMFF1_HHI_B", "ab2fc4381f77ab858c2971597b913620"),
            Map.entry("brcm_freh5", "d908785c570431b7d4f4903f51ed5719"));

    /** The layer the frames of the refinement name: {@code dependency_id} 0, {@code quality_id} 1. */
    private static final Optional<VideoLayer> REFINEMENT = Optional.of(new VideoLayer(1, 0, VideoLayer.Type.PRIMARY));

    static Stream<H264ConformanceTest.Vector> reference() {
        return H264ConformanceTest.vectors().stream().filter(vector -> REFERENCE.contains(vector.name()));
    }

    static Stream<Arguments> rescaled() {
        return pinned(RESCALED);
    }

    static Stream<Arguments> intraBase() {
        return pinned(INTRA_BASE);
    }

    private static Stream<Arguments> pinned(Map<String, String> md5s) {
        return H264ConformanceTest.vectors().stream().filter(vector -> md5s.containsKey(vector.name()))
                .map(vector -> Arguments.of(vector, md5s.get(vector.name())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reference")
    @DisplayName("reproduces the reference decoding through a refinement that predicts the levels")
    void coefficientPrediction(H264ConformanceTest.Vector vector) throws IOException {
        var stream = H264ConformanceTest.bitstream(vector);
        var reference = H264ConformanceTest.reference(vector);
        var count = new int[1];
        Consumer<VideoFrame> sink = frame -> {
            assertEquals(REFINEMENT, frame.sideData().layer(), vector + " frame " + count[0]);
            H264ConformanceTest.compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
            count[0]++;
        };
        var options = H264DecoderOptions.builder().highestLayer().build();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : H264TestStreams.splitAnnexB(H264SvcWrapper.wrap(stream, true))) {
                decoder.decode(H264SvcStreamsTest.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), count[0], "frame count of " + vector);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rescaled")
    @DisplayName("matches the JSVM decoder where the refinement rescales levels to the quantization parameter of its slice")
    void rescaledLevels(H264ConformanceTest.Vector vector, String md5) throws IOException {
        var output = H264SvcStreamsTest.decode(H264SvcWrapper.wrap(H264ConformanceTest.bitstream(vector), true),
                H264DecoderOptions.builder().highestLayer().build());
        assertEquals(md5, output.md5(), vector + " (" + output.count() + " frames)");
        assertEquals(vector.frames(), output.count(), "frame count of " + vector);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("intraBase")
    @DisplayName("matches the JSVM decoder through a refinement that predicts intra macroblocks as Intra_Base")
    void intraBasePrediction(H264ConformanceTest.Vector vector, String md5) throws IOException {
        var output = H264SvcStreamsTest.decode(H264SvcWrapper.wrap(H264ConformanceTest.bitstream(vector)),
                H264DecoderOptions.builder().highestLayer().build());
        assertEquals(md5, output.md5(), vector + " (" + output.count() + " frames)");
        assertEquals(vector.frames(), output.count(), "frame count of " + vector);
        output.layers.forEach(layer -> assertEquals(REFINEMENT, layer, vector.name()));
    }
}
