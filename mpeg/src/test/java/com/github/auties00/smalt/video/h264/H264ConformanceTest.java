package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the ITU-T H.264.1 conformance bitstreams (JVT draft conformance suite, AVCv1 and
 * FRExt) and requires every frame to match the reference YUV byte for byte, progressive, field
 * and MBAFF streams alike, 4:2:0 and 4:2:2, 8 and 10 bits, the Extended profile's SP slices
 * included. The streams that come without a reference YUV are checked by
 * {@link H264HighBitDepthTest}, {@link H264Chroma422Test} and {@link H264Chroma444Test}; the
 * Extended profile tools the suite does not exercise (slice data partitioning, switching SP and
 * SI slices) by {@link H264ExtendedProfileTest}. Each archive is read from the hosted test data
 * by {@link Conformance#vector}.
 */
@DisplayName("H.264 conformance bitstreams (ITU-T H.264.1)")
class H264ConformanceTest {
    /**
     * Name | directory | archive | bitstream entry | reference entry | expectation
     * ("frames:N", or "frames16:N" for a reference of 16-bit little-endian samples).
     */
    private static final String MANIFEST = """
            AUD_MW_E|AVCv1|AUD_MW_E.zip|AUD_MW_E.264|AUD_MW_E_rec.qcif|frames:100
            BA1_FT_C|AVCv1|BA1_FT_C.zip|BA1_FT_C.264|BA1_FT_C.yuv|frames:299
            BA1_Sony_D|AVCv1|BA1_Sony_D.zip|BA1_Sony_D.jsv|BA1_Sony_D.yuv|frames:17
            BA2_Sony_F|AVCv1|BA2_Sony_F.zip|BA2_Sony_F.jsv|BA2_Sony_F.yuv|frames:300
            BA3_SVA_C|AVCv1|BA3_SVA_C.zip|BA3_SVA_C.264|BA3_SVA_C_rec.yuv|frames:33
            BAMQ1_JVC_C|AVCv1|BAMQ1_JVC_C.zip|BAMQ1_JVC_C.264|BAMQ1_JVC_C.yuv|frames:30
            BAMQ2_JVC_C|AVCv1|BAMQ2_JVC_C.zip|BAMQ2_JVC_C.264|BAMQ2_JVC_C.yuv|frames:30
            BANM_MW_D|AVCv1|BANM_MW_D.zip|BANM_MW_D.264|BANM_MW_D_rec.qcif|frames:100
            BASQP1_Sony_C|AVCv1|BASQP1_Sony_C.zip|BASQP1_Sony_C.jsv|BASQP1_Sony_C.yuv|frames:4
            BA_MW_D|AVCv1|BA_MW_D.zip|BA_MW_D.264|BA_MW_D_rec.qcif|frames:100
            CABA1_SVA_B|AVCv1|CABA1_SVA_B.zip|CABA1_SVA_B.264|CABA1_SVA_B_rec.yuv|frames:17
            CABA1_Sony_D|AVCv1|CABA1_Sony_D.zip|CABA1_Sony_D.jsv|CABA1_Sony_D.yuv|frames:50
            CABA2_SVA_B|AVCv1|CABA2_SVA_B.zip|CABA2_SVA_B.264|CABA2_SVA_B_rec.yuv|frames:17
            CABA2_Sony_E|AVCv1|CABA2_Sony_E.zip|CABA2_Sony_E.jsv|CABA2_Sony_E.yuv|frames:300
            CABA3_SVA_B|AVCv1|CABA3_SVA_B.zip|CABA3_SVA_B.264|CABA3_SVA_B_rec.yuv|frames:33
            CABA3_Sony_C|AVCv1|CABA3_Sony_C.zip|CABA3_Sony_C.jsv|CABA3_Sony_C.yuv|frames:300
            CABA3_TOSHIBA_E|AVCv1|CABA3_TOSHIBA_E.zip|CABA3_TOSHIBA_E.264|CABA3_TOSHIBA_E_dec.yuv|frames:300
            CABACI3_Sony_B|AVCv1|CABACI3_Sony_B.zip|CABACI3_Sony_B.jsv|CABACI3_Sony_B.yuv|frames:300
            CABAST3_Sony_E|AVCv1|CABAST3_Sony_E.zip|CABAST3_Sony_E.jsv|CABAST3_Sony_E.yuv|frames:25
            CABASTBR3_Sony_B|AVCv1|CABASTBR3_Sony_B.zip|CABASTBR3_Sony_B.jsv|CABASTBR3_Sony_B.yuv|frames:25
            CABREF3_Sand_D|AVCv1|CABREF3_Sand_D.zip|CABREF3_Sand_D.264|CABREF3_Sand_D.yuv|frames:50
            CACQP3_Sony_D|AVCv1|CACQP3_Sony_D.zip|CACQP3_Sony_D.jsv|CACQP3_Sony_D.yuv|frames:50
            CAFI1_SVA_C|AVCv1|CAFI1_SVA_C.zip|CAFI1_SVA_C.264|CAFI1_SVA_C_rec.yuv|frames:33
            CAMA1_Sony_C|AVCv1|CAMA1_Sony_C.zip|CAMA1_Sony_C.jsv|CAMA1_Sony_C.yuv|frames:5
            CAMA1_TOSHIBA_B|AVCv1|CAMA1_TOSHIBA_B.zip|CAMA1_TOSHIBA_B.264|CAMA1_TOSHIBA_B_dec.yuv|frames:90
            CAMA3_Sand_E|AVCv1|CAMA3_Sand_E.zip|CAMA3_Sand_E.264|CAMA3_Sand_E.yuv|frames:50
            CAMACI3_Sony_C|AVCv1|CAMACI3_Sony_C.zip|CAMACI3_Sony_C.jsv|CAMACI3_Sony_C.yuv|frames:17
            CAMANL1_TOSHIBA_B|AVCv1|CAMANL1_TOSHIBA_B.zip|CAMANL1_TOSHIBA_B.264|CAMANL1_TOSHIBA_B_dec.yuv|frames:90
            CAMANL2_TOSHIBA_B|AVCv1|CAMANL2_TOSHIBA_B.zip|CAMANL2_TOSHIBA_B.264|CAMANL2_TOSHIBA_B_dec.yuv|frames:90
            CAMANL3_Sand_E|AVCv1|CAMANL3_Sand_E.zip|CAMANL3_Sand_E.264|CAMANL3_Sand_E.yuv|frames:50
            CAMASL3_Sony_B|AVCv1|CAMASL3_Sony_B.zip|CAMASL3_Sony_B.jsv|CAMASL3_Sony_B.yuv|frames:17
            CAMP_MOT_MBAFF_L30|AVCv1|CAMP_MOT_MBAFF_L30.zip|CAMP_MOT_MBAFF_L30.26l|CAMP_MOT_MBAFF_L30_rec.yuv|frames:30
            CAMP_MOT_MBAFF_L31|AVCv1|CAMP_MOT_MBAFF_L31.zip|CAMP_MOT_MBAFF_L31.26l|CAMP_MOT_MBAFF_L31_rec.yuv|frames:30
            CANL1_SVA_B|AVCv1|CANL1_SVA_B.zip|CANL1_SVA_B.264|CANL1_SVA_B_rec.yuv|frames:17
            CANL1_Sony_E|AVCv1|CANL1_Sony_E.zip|CANL1_Sony_E.jsv|CANL1_Sony_E.yuv|frames:50
            CANL1_TOSHIBA_G|AVCv1|CANL1_TOSHIBA_G.zip|CANL1_TOSHIBA_G.264|CANL1_TOSHIBA_G_dec.yuv|frames:300
            CANL2_SVA_B|AVCv1|CANL2_SVA_B.zip|CANL2_SVA_B.264|CANL2_SVA_B_rec.yuv|frames:17
            CANL2_Sony_E|AVCv1|CANL2_Sony_E.zip|CANL2_Sony_E.jsv|CANL2_Sony_E.yuv|frames:300
            CANL3_SVA_B|AVCv1|CANL3_SVA_B.zip|CANL3_SVA_B.264|CANL3_SVA_B_rec.yuv|frames:17
            CANL3_Sony_C|AVCv1|CANL3_Sony_C.zip|CANL3_Sony_C.jsv|CANL3_Sony_C.yuv|frames:300
            CANL4_SVA_B|AVCv1|CANL4_SVA_B.zip|CANL4_SVA_B.264|CANL4_SVA_B_rec.yuv|frames:33
            CANLMA2_Sony_C|AVCv1|CANLMA2_Sony_C.zip|CANLMA2_Sony_C.jsv|CANLMA2_Sony_C.yuv|frames:17
            CANLMA3_Sony_C|AVCv1|CANLMA3_Sony_C.zip|CANLMA3_Sony_C.jsv|CANLMA3_Sony_C.yuv|frames:17
            CAPA1_TOSHIBA_B|AVCv1|CAPA1_TOSHIBA_B.zip|CAPA1_TOSHIBA_B.264|CAPA1_TOSHIBA_B_dec.yuv|frames:90
            CAPAMA3_Sand_F|AVCv1|CAPAMA3_Sand_F.zip|CAPAMA3_Sand_F.264|CAPAMA3_Sand_F.yuv|frames:50
            CAPCM1_Sand_E|AVCv1|CAPCM1_Sand_E.zip|CAPCM1_Sand_E.264|CAPCM1_Sand_E.yuv|frames:30
            CAPCMNL1_Sand_E|AVCv1|CAPCMNL1_Sand_E.zip|CAPCMNL1_Sand_E.264|CAPCMNL1_Sand_E.yuv|frames:30
            CAPM3_Sony_D|AVCv1|CAPM3_Sony_D.zip|CAPM3_Sony_D.jsv|CAPM3_Sony_D.yuv|frames:300
            CAQP1_Sony_B|AVCv1|CAQP1_Sony_B.zip|CAQP1_Sony_B.jsv|CAQP1_Sony_B.yuv|frames:50
            CAWP1_TOSHIBA_E|AVCv1|CAWP1_TOSHIBA_E.zip|CAWP1_TOSHIBA_E/CAWP1_TOSHIBA_E.264|CAWP1_TOSHIBA_E/CAWP1_TOSHIBA_E_dec.yuv|frames:90
            CAWP5_TOSHIBA_E|AVCv1|CAWP5_TOSHIBA_E.zip|CAWP5_TOSHIBA_E/CAWP5_TOSHIBA_E.264|CAWP5_TOSHIBA_E/CAWP5_TOSHIBA_E_dec.yuv|frames:90
            CI1_FT_B|AVCv1|CI1_FT_B.zip|CI1_FT_B.264|CI1_FT_B.yuv|frames:291
            CI_MW_D|AVCv1|CI_MW_D.zip|CI_MW_D.264|CI_MW_D_rec.qcif|frames:100
            CVBS3_Sony_C|AVCv1|CVBS3_Sony_C.zip|CVBS3_Sony_C.jsv|CVBS3_Sony_C.yuv|frames:300
            CVCANLMA2_Sony_C|AVCv1|CVCANLMA2_Sony_C.zip|CVCANLMA2_Sony_C.jsv|CVCANLMA2_Sony_C.yuv|frames:17
            CVFC1_Sony_C|AVCv1|CVFC1_Sony_C.zip|CVFC1_Sony_C.jsv|CVFC1_Sony_C_nogray.yuv|frames:50
            CVFI1_SVA_C|AVCv1|CVFI1_SVA_C.zip|CVFI1_SVA_C.264|CVFI1_SVA_C_rec.yuv|frames:7
            CVFI1_Sony_D|AVCv1|CVFI1_Sony_D.zip|CVFI1_Sony_D.jsv|CVFI1_Sony_D.yuv|frames:17
            CVFI2_SVA_C|AVCv1|CVFI2_SVA_C.zip|CVFI2_SVA_C.264|CVFI2_SVA_C_rec.yuv|frames:13
            CVFI2_Sony_H|AVCv1|CVFI2_Sony_H.zip|CVFI2_Sony_H.jsv|CVFI2_Sony_H.yuv|frames:17
            CVMA1_Sony_D|AVCv1|CVMA1_Sony_D.zip|CVMA1_Sony_D.jsv|CVMA1_Sony_D.yuv|frames:5
            CVMA1_TOSHIBA_B|AVCv1|CVMA1_TOSHIBA_B.zip|CVMA1_TOSHIBA_B.264|CVMA1_TOSHIBA_B_dec.yuv|frames:90
            CVMANL1_TOSHIBA_B|AVCv1|CVMANL1_TOSHIBA_B.zip|CVMANL1_TOSHIBA_B.264|CVMANL1_TOSHIBA_B_dec.yuv|frames:90
            CVMANL2_TOSHIBA_B|AVCv1|CVMANL2_TOSHIBA_B.zip|CVMANL2_TOSHIBA_B.264|CVMANL2_TOSHIBA_B_dec.yuv|frames:90
            CVMAPAQP3_Sony_E|AVCv1|CVMAPAQP3_Sony_E.zip|CVMAPAQP3_Sony_E.jsv|CVMAPAQP3_Sony_E.yuv|frames:8
            CVMAQP2_Sony_G|AVCv1|CVMAQP2_Sony_G.zip|CVMAQP2_Sony_G.jsv|CVMAQP2_Sony_G.yuv|frames:17
            CVMAQP3_Sony_D|AVCv1|CVMAQP3_Sony_D.zip|CVMAQP3_Sony_D.jsv|CVMAQP3_Sony_D.yuv|frames:17
            CVMP_MOT_FLD_L30_B|AVCv1|CVMP_MOT_FLD_L30_B.zip|CVMP_MOT_FLD_L30_B.26l|CVMP_MOT_FLD_L30_B_rec.yuv|frames:30
            CVMP_MOT_FRM_L31_B|AVCv1|CVMP_MOT_FRM_L31_B.zip|CVMP_MOT_FRM_L31_B.26l|CVMP_MOT_FRM_L31_B_rec.yuv|frames:30
            CVNLFI1_Sony_C|AVCv1|CVNLFI1_Sony_C.zip|CVNLFI1_Sony_C.jsv|CVNLFI1_Sony_C.yuv|frames:17
            CVNLFI2_Sony_H|AVCv1|CVNLFI2_Sony_H.zip|CVNLFI2_Sony_H.jsv|CVNLFI2_Sony_H.yuv|frames:17
            CVPA1_TOSHIBA_B|AVCv1|CVPA1_TOSHIBA_B.zip|CVPA1_TOSHIBA_B.264|CVPA1_TOSHIBA_B_dec.yuv|frames:90
            CVPCMNL1_SVA_C|AVCv1|CVPCMNL1_SVA_C.zip|CVPCMNL1_SVA_C.264|CVPCMNL1_SVA_C_rec.yuv|frames:30
            CVPCMNL2_SVA_C|AVCv1|CVPCMNL2_SVA_C.zip|CVPCMNL2_SVA_C.264|CVPCMNL2_SVA_C_rec.yuv|frames:2
            CVSE2_Sony_B|AVCv1|CVSE2_Sony_B.zip|CVSE2_Sony_B.jsv|CVSE2_Sony_B-norpt.yuv|frames:278
            CVSE3_Sony_H|AVCv1|CVSE3_Sony_H.zip|CVSE3_Sony_H.jsv|CVSE3_Sony_H-norpt.yuv|frames:278
            CVSEFDFT3_Sony_E|AVCv1|CVSEFDFT3_Sony_E.zip|CVSEFDFT3_Sony_E.jsv|CVSEFDFT3_Sony_E-norpt.yuv|frames:200
            CVWP1_TOSHIBA_E|AVCv1|CVWP1_TOSHIBA_E.zip|CVWP1_TOSHIBA_E/CVWP1_TOSHIBA_E.264|CVWP1_TOSHIBA_E/CVWP1_TOSHIBA_E_dec.yuv|frames:90
            CVWP2_TOSHIBA_E|AVCv1|CVWP2_TOSHIBA_E.zip|CVWP2_TOSHIBA_E/CVWP2_TOSHIBA_E.264|CVWP2_TOSHIBA_E/CVWP2_TOSHIBA_E_dec.yuv|frames:90
            CVWP3_TOSHIBA_E|AVCv1|CVWP3_TOSHIBA_E.zip|CVWP3_TOSHIBA_E/CVWP3_TOSHIBA_E.264|CVWP3_TOSHIBA_E/CVWP3_TOSHIBA_E_dec.yuv|frames:90
            CVWP5_TOSHIBA_E|AVCv1|CVWP5_TOSHIBA_E.zip|CVWP5_TOSHIBA_E/CVWP5_TOSHIBA_E.264|CVWP5_TOSHIBA_E/CVWP5_TOSHIBA_E_dec.yuv|frames:90
            FI1_Sony_E|AVCv1|FI1_Sony_E.zip|FI1_Sony_E.jsv|FI1_Sony_E.yuv|frames:17
            FM1_BT_B|AVCv1|FM1_BT_B.zip|FM1_BT_B.h264|FM1_BT_B.yuv|frames:400
            FM1_FT_E|AVCv1|FM1_FT_E.zip|FM1_FT_E.264|FM1_FT_E.yuv|frames:300
            FM2_SVA_C|AVCv1|FM2_SVA_C.zip|FM2_SVA_C.264|FM2_SVA_C_rec.yuv|frames:17
            HCBP1_HHI_A|AVCv1|HCBP1_HHI_A.zip|HCBP1_HHI_A.264|HCBP1_HHI_A.yuv|frames:250
            HCBP2_HHI_A|AVCv1|HCBP2_HHI_A.zip|HCBP2_HHI_A.264|HCBP2_HHI_A.yuv|frames:250
            HCMP1_HHI_A|AVCv1|HCMP1_HHI_A.zip|HCMP1_HHI_A.264|HCMP1_HHI_A.yuv|frames:250
            LS_SVA_D|AVCv1|LS_SVA_D.zip|LS_SVA_D.264|LS_SVA_D_rec.yuv|frames:1700
            MIDR_MW_D|AVCv1|MIDR_MW_D.zip|MIDR_MW_D.264|MIDR_MW_D_rec.qcif|frames:100
            MPS_MW_A|AVCv1|MPS_MW_A.zip|MPS_MW_A.264|MPS_MW_A_rec.qcif|frames:150
            MR1_BT_A|AVCv1|MR1_BT_A.zip|MR1_BT_A.h264|MR1_BT_A.yuv|frames:62
            MR1_MW_A|AVCv1|MR1_MW_A.zip|MR1_MW_A.264|MR1_MW_A_rec.qcif|frames:150
            MR2_MW_A|AVCv1|MR2_MW_A.zip|MR2_MW_A.264|MR2_MW_A_rec.qcif|frames:300
            MR2_TANDBERG_E|AVCv1|MR2_TANDBERG_E.zip|MR2_TANDBERG_E.264|MR2_TANDBERG_E.yuv|frames:300
            MR3_TANDBERG_B|AVCv1|MR3_TANDBERG_B.zip|MR3_TANDBERG_B.264|MR3_TANDBERG_B.yuv|frames:300
            MR4_TANDBERG_C|AVCv1|MR4_TANDBERG_C.zip|MR4_TANDBERG_C.264|MR4_TANDBERG_C.yuv|frames:300
            MR5_TANDBERG_C|AVCv1|MR5_TANDBERG_C.zip|MR5_TANDBERG_C.264|MR5_TANDBERG_C.yuv|frames:300
            MR6_BT_B|AVCv1|MR6_BT_B.zip|MR6_BT_B.h264|MR6_BT_B.yuv|frames:60
            MR7_BT_B|AVCv1|MR7_BT_B.zip|MR7_BT_B.h264|MR7_BT_B.yuv|frames:60
            MR8_BT_B|AVCv1|MR8_BT_B.zip|MR8_BT_B.h264|MR8_BT_B.yuv|frames:58
            MR9_BT_B|AVCv1|MR9_BT_B.zip|MR9_BT_B.h264|MR9_BT_B.yuv|frames:58
            MV1_BRCM_D|AVCv1|MV1_BRCM_D.zip|src19td.IBP.264|src19td_rec.yuv|frames:257
            NL1_Sony_D|AVCv1|NL1_Sony_D.zip|NL1_Sony_D.jsv|NL1_Sony_D.yuv|frames:17
            NL2_Sony_H|AVCv1|NL2_Sony_H.zip|NL2_Sony_H.jsv|NL2_Sony_H.yuv|frames:300
            NL3_SVA_E|AVCv1|NL3_SVA_E.zip|NL3_SVA_E.264|NL3_SVA_E_rec.yuv|frames:33
            NLMQ1_JVC_C|AVCv1|NLMQ1_JVC_C.zip|NLMQ1_JVC_C.264|NLMQ1_JVC_C.yuv|frames:30
            NLMQ2_JVC_C|AVCv1|NLMQ2_JVC_C.zip|NLMQ2_JVC_C.264|NLMQ2_JVC_C.yuv|frames:30
            NRF_MW_E|AVCv1|NRF_MW_E.zip|NRF_MW_E.264|NRF_MW_E_rec.qcif|frames:100
            SL1_SVA_B|AVCv1|SL1_SVA_B.zip|SL1_SVA_B.264|SL1_SVA_B_rec.yuv|frames:33
            SP1_BT_A|AVCv1|SP1_BT_A.zip|sp1_bt_a.h264|sp1_bt_a.yuv|frames:400
            SVA_BA1_B|AVCv1|SVA_BA1_B.zip|SVA_BA1_B.264|SVA_BA1_B_rec.yuv|frames:17
            SVA_BA2_D|AVCv1|SVA_BA2_D.zip|SVA_BA2_D.264|SVA_BA2_D_rec.yuv|frames:17
            SVA_Base_B|AVCv1|SVA_Base_B.zip|SVA_Base_B.264|SVA_Base_B_rec.yuv|frames:17
            SVA_CL1_E|AVCv1|SVA_CL1_E.zip|SVA_CL1_E.264|SVA_CL1_E_rec.yuv|frames:50
            SVA_FM1_E|AVCv1|SVA_FM1_E.zip|SVA_FM1_E.264|SVA_FM1_E_rec.yuv|frames:17
            SVA_NL1_B|AVCv1|SVA_NL1_B.zip|SVA_NL1_B.264|SVA_NL1_B_rec.yuv|frames:17
            SVA_NL2_E|AVCv1|SVA_NL2_E.zip|SVA_NL2_E.264|SVA_NL2_E_rec.yuv|frames:17
            Sharp_MP_Field_1_B|AVCv1|Sharp_MP_Field_1_B.zip|Sharp_MP_Field_1_B.jvt|Sharp_MP_Field_1_rec_B.yuv|frames:15
            Sharp_MP_Field_2_B|AVCv1|Sharp_MP_Field_2_B.zip|Sharp_MP_Field_2_B.jvt|Sharp_MP_Field_2_rec_B.yuv|frames:15
            Sharp_MP_Field_3_B|AVCv1|Sharp_MP_Field_3_B.zip|Sharp_MP_Field_3_B.jvt|Sharp_MP_Field_3_rec_B.yuv|frames:15
            Sharp_MP_PAFF_1r2|AVCv1|Sharp_MP_PAFF_1r2.zip|Sharp_MP_PAFF_1r2.jvt|Sharp_MP_PAFF_1r2_rec.yuv|frames:15
            Sharp_MP_PAFF_2r|AVCv1|Sharp_MP_PAFF_2r.zip|Sharp_MP_PAFF_2.jvt|Sharp_MP_PAFF_2_rec.yuv|frames:15
            cabac_mot_fld0_full|AVCv1|cabac_mot_fld0_full.zip|camp_mot_fld0_full.26l|camp_mot_fld0_full_rec.yuv|frames:30
            cabac_mot_frm0_full|AVCv1|cabac_mot_frm0_full.zip|camp_mot_frm0_full.26l|camp_mot_frm0_full_rec.yuv|frames:30
            cabac_mot_mbaff0_full|AVCv1|cabac_mot_mbaff0_full.zip|camp_mot_mbaff0_full.26l|camp_mot_mbaff0_full_rec.yuv|frames:30
            cabac_mot_picaff0_full|AVCv1|cabac_mot_picaff0_full.zip|camp_mot_picaff0_full.26l|camp_mot_picaff0_full_rec.yuv|frames:30
            cama1_vtc_c|AVCv1|cama1_vtc_c.zip|cama1_vtc_c.avc|cama1_vtc_c_recon.yuv|frames:4
            cama2_vtc_b|AVCv1|cama2_vtc_b.zip|cama2_vtc_b.avc|cama2_vtc_b_recon.yuv|frames:4
            cama3_vtc_b|AVCv1|cama3_vtc_b.zip|cama3_vtc_b.avc|cama3_vtc_b_recon.yuv|frames:4
            cavlc_mot_fld0_full_B|AVCv1|cavlc_mot_fld0_full_B.zip|cvmp_mot_fld0_full_B.26l|cvmp_mot_fld0_full_B_rec.yuv|frames:30
            cavlc_mot_frm0_full_B|AVCv1|cavlc_mot_frm0_full_B.zip|cvmp_mot_frm0_full_B.26l|cvmp_mot_frm0_full_rec_B.yuv|frames:30
            cavlc_mot_mbaff0_full_B|AVCv1|cavlc_mot_mbaff0_full_B.zip|cvmp_mot_mbaff0_full_B.26l|cvmp_mot_mbaff0_full_B_rec.yuv|frames:30
            cavlc_mot_picaff0_full_B|AVCv1|cavlc_mot_picaff0_full_B.zip|cvmp_mot_picaff0_full_B.26l|cvmp_mot_picaff0_full_B_rec.yuv|frames:30
            sp2_bt_b|AVCv1|sp2_bt_b.zip|H26L/BitstreamExchange/sp2_bt_b.h264|H26L/BitstreamExchange/sp2_bt_b.yuv|frames:400
            FREXT01_JVC_D|FRExt|FREXT01_JVC_D.zip|FREXT01_JVC_D.264|FREXT01_JVC_D.yuv|frames:16
            FREXT02_JVC_C|FRExt|FREXT02_JVC_C.zip|FREXT02_JVC_C.264|FREXT02_JVC_C.yuv|frames:16
            FREXT1_TANDBERG_A|FRExt|FREXT1_TANDBERG_A.zip|FREXT1_TANDBERG_A.264|FREXT1_TANDBERG_A.yuv|frames:5
            FREXT2_TANDBERG_A|FRExt|FREXT2_TANDBERG_A.zip|FREXT2_TANDBERG_A.264|FREXT2_TANDBERG_A.yuv|frames:5
            FREXT3_TANDBERG_A|FRExt|FREXT3_TANDBERG_A.zip|FREXT3_TANDBERG_A.264|FREXT3_TANDBERG_A.yuv|frames:5
            FRExt1_Panasonic_D|FRExt|FRExt1_Panasonic_D.zip|FRExt1_Panasonic.avc|FRExt1_Panasonic_rec.yuv|frames:8
            FRExt2_Panasonic_C|FRExt|FRExt2_Panasonic_C.zip|FRExt2_Panasonic.avc|FRExt2_Panasonic_rec.yuv|frames:15
            FRExt3_Panasonic_E|FRExt|FRExt3_Panasonic_E.zip|FRExt3_Panasonic.avc|FRExt3_Panasonic_rec.yuv|frames:11
            FRExt4_Panasonic_B|FRExt|FRExt4_Panasonic_B.zip|FRExt4_Panasonic.avc|FRExt4_Panasonic_rec.yuv|frames:10
            FRExt_MMCO4_Sony_B|FRExt|FRExt_MMCO4_Sony_B.zip|FRExt_MMCO4_Sony_B/FRExt_MMCO4_Sony_B.264|FRExt_MMCO4_Sony_B/FRExt_MMCO4_Sony_B.yuv|frames:60
            HCAFF1_HHI_B|FRExt|HCAFF1_HHI_B.zip|HCAFF1_HHI.264|HCAFF1_HHI.yuv|frames:10
            HCAFR1_HHI_C|FRExt|HCAFR1_HHI_C.zip|HCAFR1_HHI.264|HCAFR1_HHI.yuv|frames:10
            HCAFR2_HHI_A|FRExt|HCAFR2_HHI_A.zip|HCAFR2_HHI.264|HCAFR2_HHI.yuv|frames:10
            HCAFR3_HHI_A|FRExt|HCAFR3_HHI_A.zip|HCAFR3_HHI.264|HCAFR3_HHI.yuv|frames:10
            HCAFR4_HHI_A|FRExt|HCAFR4_HHI_A.zip|HCAFR4_HHI.264|HCAFR4_HHI.yuv|frames:10
            HCAMFF1_HHI_B|FRExt|HCAMFF1_HHI_B.zip|HCAMFF1_HHI.264|HCAMFF1_HHI.yuv|frames:10
            HCHP1_HHI_B|FRExt|HCHP1_HHI_B.zip|HCHP1_HHI_B.264|HCHP1_HHI_B.yuv|frames:250
            HCHP2_HHI_A|FRExt|HCHP2_HHI_A.zip|HCHP2_HHI_A.264|HCHP2_HHI_A.yuv|frames:250
            HCHP3_HHI_A|FRExt|HCHP3_HHI_A.zip|HCHP3_HHI_A.264|HCHP3_HHI_A.yuv|frames:250
            HPCADQ_BRCM_B|FRExt|HPCADQ_BRCM_B.zip|HPCADQ_BRCM_B.264|HPCADQ_BRCM_B.yuv|frames:300
            HPCAFLNL_BRCM_C|FRExt|HPCAFLNL_BRCM_C.zip|HPCAFLNL_BRCM_C.264|HPCAFLNL_BRCM_C.yuv|frames:300
            HPCAFL_BRCM_C|FRExt|HPCAFL_BRCM_C.zip|HPCAFL_BRCM_C.264|HPCAFL_BRCM_C.yuv|frames:300
            HPCALQ_BRCM_B|FRExt|HPCALQ_BRCM_B.zip|HPCALQ_BRCM_B.264|HPCALQ_BRCM_B.yuv|frames:300
            HPCAMAPALQ_BRCM_B|FRExt|HPCAMAPALQ_BRCM_B.zip|HPCAMAPALQ_BRCM_B.264|HPCAMAPALQ_BRCM_B.yuv|frames:300
            HPCAMOLQ_BRCM_B|FRExt|HPCAMOLQ_BRCM_B.zip|HPCAMOLQ_BRCM_B.264|HPCAMOLQ_BRCM_B.YUV|frames:100
            HPCANL_BRCM_C|FRExt|HPCANL_BRCM_C.zip|HPCANL_BRCM_C.264|HPCANL_BRCM_C.yuv|frames:300
            HPCAQ2LQ_BRCM_B|FRExt|HPCAQ2LQ_BRCM_B.zip|HPCAQ2LQ_BRCM_B.264|HPCAQ2LQ_BRCM_B.YUV|frames:100
            HPCA_BRCM_C|FRExt|HPCA_BRCM_C.zip|HPCA_BRCM_C.264|HPCA_BRCM_C.yuv|frames:300
            HPCVFLNL_BRCM_A|FRExt|HPCVFLNL_BRCM_A.zip|HPCVFLNL_BRCM_A.264|HPCVFLNL_BRCM_A.YUV|frames:300
            HPCVFL_BRCM_A|FRExt|HPCVFL_BRCM_A.zip|HPCVFL_BRCM_A.264|HPCVFL_BRCM_A.YUV|frames:300
            HPCVMOLQ_BRCM_B|FRExt|HPCVMOLQ_BRCM_B.zip|HPCVMOLQ_BRCM_B.264|HPCVMOLQ_BRCM_B.YUV|frames:100
            HPCVNL_BRCM_A|FRExt|HPCVNL_BRCM_A.zip|HPCVNL_BRCM_A.264|HPCVNL_BRCM_A.YUV|frames:300
            HPCV_BRCM_A|FRExt|HPCV_BRCM_A.zip|HPCV_BRCM_A.264|HPCV_BRCM_A.YUV|frames:300
            HVLCFI0_Sony_B|FRExt|HVLCFI0_Sony_B.zip|HVLCFI0_Sony_B/HVLCFI0_Sony_B.264|HVLCFI0_Sony_B/HVLCFI0_Sony_B.yuv|frames:19
            HVLCMFF0_Sony_B|FRExt|HVLCMFF0_Sony_B.zip|HVLCMFF0_Sony_B/HVLCMFF0_Sony_B.264|HVLCMFF0_Sony_B/HVLCMFF0_Sony_B.yuv|frames:19
            HVLCPFF0_Sony_B|FRExt|HVLCPFF0_Sony_B.zip|HVLCPFF0_Sony_B/HVLCPFF0_Sony_B.264|HVLCPFF0_Sony_B/HVLCPFF0_Sony_B.yuv|frames:19
            Hi422FR10_SONY_A|FRExt|Hi422FR10_SONY_A.zip|Hi422FR10_SONY_B.264|Hi422FR10_SONY_B.yuv|frames:5
            Hi422FR11_SONY_A|FRExt|Hi422FR11_SONY_A.zip|Hi422FR11_SONY_B.264|Hi422FR11_SONY_B.yuv|frames:5
            Hi422FR12_SONY_A|FRExt|Hi422FR12_SONY_A.zip|Hi422FR12_SONY_B.264|Hi422FR12_SONY_B.yuv|frames:5
            Hi422FR13_SONY_A|FRExt|Hi422FR13_SONY_A.zip|Hi422FR13_SONY_B.264|Hi422FR13_SONY_B.yuv|frames16:5
            Hi422FR14_SONY_A|FRExt|Hi422FR14_SONY_A.zip|Hi422FR14_SONY_B.264|Hi422FR14_SONY_B.yuv|frames16:5
            Hi422FR15_SONY_A|FRExt|Hi422FR15_SONY_A.zip|Hi422FR15_SONY_B.264|Hi422FR15_SONY_B.yuv|frames16:5
            alphaconformanceG|FRExt|alphaconformanceG.zip|alphaconformanceG/alphaconformanceG.264|alphaconformanceG/test8b43.yuv|frames:40
            alphaconformanceG-alpha|FRExt|alphaconformanceG.zip|alphaconformanceG/test7a43.264patched|alphaconformanceG/test7a43.yuv|frames:43
            alphaconformanceG-foreground|FRExt|alphaconformanceG.zip|alphaconformanceG/test8b43.264|alphaconformanceG/test8b43.yuv|frames:43
            brcm_freh10|FRExt|brcm_freh10.zip|freh10.264|Freh10_dec.yuv|frames:100
            brcm_freh11|FRExt|brcm_freh11.zip|freh11.264|Freh11_dec.yuv|frames:100
            brcm_freh3|FRExt|brcm_freh3.zip|freh3.264|Freh3_dec.yuv|frames:100
            brcm_freh4|FRExt|brcm_freh4.zip|freh4.264|Freh4_dec.yuv|frames:100
            brcm_freh5|FRExt|brcm_freh5.zip|freh5.264|Freh5_dec.yuv|frames:100
            brcm_freh6|FRExt|brcm_freh6.zip|freh6.264|Freh6_dec.yuv|frames:100
            brcm_freh8|FRExt|brcm_freh8.zip|freh8.264|Freh8_dec.yuv|frames:100
            brcm_freh9|FRExt|brcm_freh9.zip|freh9.264|Freh9_dec.yuv|frames:100
            freh12_b|FRExt|freh12_b.zip|Freh12_B.264|Freh12_B_dec.yuv|frames:100
            freh1_b|FRExt|freh1_b.zip|Freh1_B.264|Freh1_B_dec.yuv|frames:100
            freh2_b|FRExt|freh2_b.zip|Freh2_B.264|Freh2_B_dec.yuv|frames:100
            freh7_b|FRExt|freh7_b.zip|Freh7_B.264|Freh7_B_dec.yuv|frames:100
            """;

    record Vector(String name, String directory, String archive, String bitstream, String reference,
                  String expectation) {
        @Override
        public String toString() {
            return name;
        }

        boolean wide() {
            return expectation.startsWith("frames16:");
        }

        int frames() {
            return Integer.parseInt(expectation.substring(expectation.indexOf(':') + 1));
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|", -1);
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return result;
    }

    static Stream<Vector> decodable() {
        return vectors().stream();
    }

    static Stream<Vector> threaded() {
        return decodable().filter(vector -> vector.name().matches(
                "CABA3_Sony_C|CAPM3_Sony_D|MR4_TANDBERG_C|CVWP1_TOSHIBA_E|HCBP2_HHI_A|FRExt_MMCO4_Sony_B|HPCV_BRCM_A"
                        + "|freh12_b|MV1_BRCM_D|FM1_FT_E|CAPCM1_Sand_E|cabac_mot_frm0_full"
                        + "|CVPA1_TOSHIBA_B|CAPAMA3_Sand_F|CAMA3_Sand_E|CAMANL2_TOSHIBA_B|CVMP_MOT_FLD_L30_B"
                        + "|cabac_mot_mbaff0_full|cavlc_mot_picaff0_full_B|FRExt2_Panasonic_C|HCAMFF1_HHI_B|MR9_BT_B"
                        + "|SP1_BT_A|sp2_bt_b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodable")
    @DisplayName("decodes every frame bit-exactly")
    void bitExact(Vector vector) throws IOException {
        check(vector, 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes bit-exactly with four frame threads")
    void bitExactThreaded(Vector vector) throws IOException {
        check(vector, 4);
    }

    /** Returns the archive of a vector, {@code h264-<directory>-<archive>} of the hosted test data. */
    static Path archive(Vector vector) {
        return Conformance.vector("h264-" + vector.directory() + "-" + vector.archive());
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = archive(vector);
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
        }
    }

    static byte[] reference(Vector vector) throws IOException {
        var archive = archive(vector);
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.reference())).readAllBytes();
        }
    }

    static void check(Vector vector, int threads) throws IOException {
        var archive = archive(vector);
        byte[] stream;
        byte[] reference;
        try (var zip = new ZipFile(archive.toFile())) {
            stream = zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
            reference = zip.getInputStream(zip.getEntry(vector.reference())).readAllBytes();
        }
        var count = new int[1];
        var options = H264DecoderOptions.builder().threads(threads).highBitDepth(vector.wide()).build();
        try (var decoder = new H264Decoder(track(), options)) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink = frame -> {
                if (vector.wide()) {
                    compareWide(vector, (Yuv16Picture) frame.picture(), reference, count[0]);
                } else {
                    compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
                }
                count[0]++;
            };
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                decoder.decode(packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
    }

    static void compare(Vector vector, YuvPicture picture, byte[] reference, int index) {
        var width = picture.width();
        var height = picture.height();
        var monochrome = picture.chromaFormat() == ChromaFormat.MONOCHROME;
        var chromaWidth = monochrome ? (width + 1) / 2 : picture.chromaWidth();
        var chromaHeight = monochrome ? (height + 1) / 2 : picture.chromaHeight();
        var frameSize = width * height + 2 * chromaWidth * chromaHeight;
        var base = (long) index * frameSize;
        if (base + frameSize > reference.length) {
            fail(vector.name() + ": decoder produced more frames than the reference holds");
        }
        var at = (int) base;
        for (var y = 0; y < height; y++) {
            var row = y * picture.yStride();
            for (var x = 0; x < width; x++) {
                if (picture.y()[row + x] != reference[at + y * width + x]) {
                    fail(vector.name() + ": frame " + index + " luma mismatch at " + x + "," + y);
                }
            }
        }
        at += width * height;
        for (var plane = 0; plane < 2; plane++) {
            for (var y = 0; y < chromaHeight; y++) {
                for (var x = 0; x < chromaWidth; x++) {
                    var expected = reference[at + plane * chromaWidth * chromaHeight + y * chromaWidth + x];
                    byte actual;
                    if (picture.chromaFormat() == ChromaFormat.MONOCHROME) {
                        actual = (byte) 128;
                    } else {
                        var samples = plane == 0 ? picture.u() : picture.v();
                        actual = samples[y * picture.uvStride() + x];
                    }
                    if (actual != expected) {
                        fail(vector.name() + ": frame " + index + " chroma " + plane + " mismatch at " + x + "," + y);
                    }
                }
            }
        }
    }

    /**
     * Compares a frame of 16-bit samples with a reference of 16-bit little-endian samples.
     */
    static void compareWide(Vector vector, Yuv16Picture picture, byte[] reference, int index) {
        var width = picture.width();
        var height = picture.height();
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        var frameSize = 2 * (width * height + 2 * chromaWidth * chromaHeight);
        var base = (long) index * frameSize;
        if (base + frameSize > reference.length) {
            fail(vector.name() + ": decoder produced more frames than the reference holds");
        }
        var at = (int) base;
        short[][] planes = {picture.y(), picture.u(), picture.v()};
        for (var plane = 0; plane < 3; plane++) {
            var w = plane == 0 ? width : chromaWidth;
            var h = plane == 0 ? height : chromaHeight;
            var stride = plane == 0 ? picture.yStride() : picture.uvStride();
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var expected = (reference[at] & 0xFF) | (reference[at + 1] & 0xFF) << 8;
                    at += 2;
                    if (planes[plane][y * stride + x] != expected) {
                        fail(vector.name() + ": frame " + index + " plane " + plane + " mismatch at " + x + "," + y);
                    }
                }
            }
        }
    }

    static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
    }

    private static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }
}
