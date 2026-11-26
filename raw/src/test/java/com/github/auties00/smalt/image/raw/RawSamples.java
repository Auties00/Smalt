package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.testkit.Conformance;

import java.nio.file.Path;

/**
 * The camera raw sample files of the tests: files of the raw.pixls.us archive (CC0, and a few CC BY-NC-SA ones used
 * for testing only) and one of rawsamples.ch (CC BY-NC-SA), read from the hosted test data through
 * {@link Conformance#vector(String)}.
 */
public enum RawSamples {
    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossless-jpeg-compression-rgb-3-2.dng (24103910 bytes).
     */
    ADC_5D3_LINEAR_LOSSLESS("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossless-jpeg-compression-rgb-3-2.dng"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-rgb-3-2.dng (2484836 bytes).
     */
    ADC_5D3_LINEAR_LOSSY("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-rgb-3-2.dng"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-lossless-jpeg-compression-3-2.dng (23206642 bytes).
     */
    ADC_5D3_LOSSLESS("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-lossless-jpeg-compression-3-2.dng"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-3-2.dng (6193902 bytes).
     */
    ADC_5D3_LOSSY("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-3-2.dng"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-uncompressed-3-2.dng (48382640 bytes).
     */
    ADC_5D3_UNCOMPRESSED("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-uncompressed-3-2.dng"),

    /**
     * rawpixls-sony-ilce-7m2-14bit-14bit-uncompressed-3-2.arw (49512448 bytes).
     */
    ARW_A7M2_UNCOMPRESSED_14("rawpixls-sony-ilce-7m2-14bit-14bit-uncompressed-3-2.arw"),

    /**
     * rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw (16646144 bytes).
     */
    ARW_NEX6_CRAW_12("rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a900-12bit-12bit-compressed-3-2.arw (37933568 bytes).
     */
    ARW_A900_UNCOMPRESSED_12("rawpixls-sony-dslr-a900-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a200-12bit-12bit-compressed-3-2.arw (11418600 bytes).
     */
    ARW_A200_COMPRESSED_12("rawpixls-sony-dslr-a200-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a230-12bit-12bit-compressed-3-2.arw (13893632 bytes).
     */
    ARW_A230_DELTA_12("rawpixls-sony-dslr-a230-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a290-12bit-12bit-compressed-3-2.arw (14286848 bytes).
     */
    ARW_A290_DELTA_12("rawpixls-sony-dslr-a290-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a350-12bit-12bit-compressed-3-2.arw (16173680 bytes).
     */
    ARW_A350_DELTA_12("rawpixls-sony-dslr-a350-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a380-12bit-12bit-compressed-3-2.arw (19431424 bytes).
     */
    ARW_A380_DELTA_12("rawpixls-sony-dslr-a380-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a390-12bit-12bit-compressed-3-2.arw (13860864 bytes).
     */
    ARW_A390_DELTA_12("rawpixls-sony-dslr-a390-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a100-compressed-3-2.arw (10137572 bytes).
     */
    ARW_A100_DELTA_12("rawpixls-sony-dslr-a100-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a300-12bit-12bit-compressed-3-2.arw (10519152 bytes).
     */
    ARW_A300_DELTA_12("rawpixls-sony-dslr-a300-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a330-12bit-12bit-compressed-3-2.arw (9601024 bytes).
     */
    ARW_A330_DELTA_12("rawpixls-sony-dslr-a330-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2 (5805950 bytes).
     */
    CR2_40D_SRAW("rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2"),

    /**
     * rawpixls-canon-eos-5d-mark-iii.cr2 (25965009 bytes).
     */
    CR2_5D3("rawpixls-canon-eos-5d-mark-iii.cr2"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2 (12343414 bytes).
     */
    CR2_5D3_SRAW("rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2"),

    /**
     * rawpixls-canon-eos-250d-raw-3-2.cr3 (26886282 bytes).
     */
    CR3_250D("rawpixls-canon-eos-250d-raw-3-2.cr3"),

    /**
     * rawpixls-canon-eos-250d-c-raw-3-2.cr3 (14783176 bytes).
     */
    CR3_EOS_250D_C_RAW("rawpixls-canon-eos-250d-c-raw-3-2.cr3"),

    /**
     * rawpixls-canon-eos-90d-3-2.cr3 (19512658 bytes).
     */
    CR3_EOS_90D("rawpixls-canon-eos-90d-3-2.cr3"),

    /**
     * rawpixls-canon-eos-m50-craw-3-2.cr3 (20948692 bytes).
     */
    CR3_EOS_M50_CRAW("rawpixls-canon-eos-m50-craw-3-2.cr3"),

    /**
     * rawpixls-canon-eos-r-3-2.cr3 (7997663 bytes).
     */
    CR3_EOS_R("rawpixls-canon-eos-r-3-2.cr3"),

    /**
     * rawpixls-canon-eos-r5-3-2.cr3 (6975574 bytes).
     */
    CR3_EOS_R5("rawpixls-canon-eos-r5-3-2.cr3"),

    /**
     * rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3 (30435672 bytes).
     */
    CR3_EOS_R5_RAW_1_6_CROP_DUAL_PIXEL("rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3"),

    /**
     * rawpixls-canon-eos-r6-3-2.cr3 (5273174 bytes).
     */
    CR3_EOS_R6("rawpixls-canon-eos-r6-3-2.cr3"),

    /**
     * rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3 (14758612 bytes).
     */
    CR3_EOS_R6_MARK_III_RAW("rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3"),

    /**
     * rawpixls-canon-eos-rp-3-2.cr3 (7395184 bytes).
     */
    CR3_EOS_RP("rawpixls-canon-eos-rp-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3 (16665658 bytes).
     */
    CR3_POWERSHOT_G5_X_MARK_II_C_RAW("rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3 (28097932 bytes).
     */
    CR3_POWERSHOT_G5_X_MARK_II_RAW("rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3 (21365790 bytes).
     */
    CR3_POWERSHOT_G7_X_MARK_III("rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3 (20852807 bytes).
     */
    CR3_POWERSHOT_SX70_HS_C_RAW("rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3 (29330229 bytes).
     */
    CR3_POWERSHOT_SX70_HS_RAW("rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3"),

    /**
     * rawpixls-canon-powershot-g1-raw-4-3.crw (2300702 bytes).
     */
    CRW_G1("rawpixls-canon-powershot-g1-raw-4-3.crw"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng (1538165 bytes).
     */
    DNG_5D3_14BIT_SMALL("rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng (1831164 bytes).
     */
    DNG_5D3_16BIT_SMALL("rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng"),

    /**
     * rawpixls-canon-eos-7d-mark-ii-16bit-3-2.dng (38265824 bytes).
     */
    DNG_7D2_JPEG_XL("rawpixls-canon-eos-7d-mark-ii-16bit-3-2.dng"),

    /**
     * rawpixls-apple-iphone-12-pro-8bit-4-3.dng (29195592 bytes).
     */
    DNG_IPHONE_12_PRO("rawpixls-apple-iphone-12-pro-8bit-4-3.dng"),

    /**
     * rawpixls-leica-m9-8bit-8bit-compressed-3-2.dng (18324480 bytes).
     */
    DNG_LEICA_M9("rawpixls-leica-m9-8bit-8bit-compressed-3-2.dng"),

    /**
     * rawpixls-leica-x2-12bit-3-2.dng (24453726 bytes).
     */
    DNG_LEICA_X2("rawpixls-leica-x2-12bit-3-2.dng"),

    /**
     * rawpixls-pentax-k-3-14bit-3-2.dng (31633373 bytes).
     */
    DNG_PENTAX_K3("rawpixls-pentax-k-3-14bit-3-2.dng"),

    /**
     * rawpixls-pentax-k-5-ii-14bit-3-2.dng (22293737 bytes).
     */
    DNG_PENTAX_K5II("rawpixls-pentax-k-5-ii-14bit-3-2.dng"),

    /**
     * rawpixls-pentax-k-x-12bit-3-2.dng (14359791 bytes).
     */
    DNG_PENTAX_KX("rawpixls-pentax-k-x-12bit-3-2.dng"),

    /**
     * rawpixls-google-pixel-2-xl-16bit-4-3.dng (24479668 bytes).
     */
    DNG_PIXEL_2_XL("rawpixls-google-pixel-2-xl-16bit-4-3.dng"),

    /**
     * rawpixls-ricoh-gr-12bit-4-3.dng (12129815 bytes).
     */
    DNG_RICOH_GR("rawpixls-ricoh-gr-12bit-4-3.dng"),

    /**
     * rawpixls-samsung-gx20-12bit-3-2.dng (24061568 bytes).
     */
    DNG_SAMSUNG_GX20("rawpixls-samsung-gx20-12bit-3-2.dng"),

    /**
     * rawpixls-fujifilm-x100s-nan.dng (20729906 bytes).
     */
    DNG_X100S("rawpixls-fujifilm-x100s-nan.dng"),

    /**
     * rawpixls-nikon-d300s-12bit-12bit-compressed-lossless-3-2.nef (12064037 bytes).
     */
    NEF_D300S_LOSSLESS_12("rawpixls-nikon-d300s-12bit-12bit-compressed-lossless-3-2.nef"),

    /**
     * rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef (15142984 bytes).
     */
    NEF_D300S_LOSSLESS_14("rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef"),

    /**
     * rawpixls-nikon-d5100-14bit-14bit-uncompressed-3-2.nef (34245856 bytes).
     */
    NEF_D5100_UNCOMPRESSED_14("rawpixls-nikon-d5100-14bit-14bit-uncompressed-3-2.nef"),

    /**
     * rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef (28692945 bytes).
     */
    NEF_D5600_LOSSY_12_SPLIT("rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef"),

    /**
     * rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef (19040701 bytes).
     */
    NEF_D7000_LOSSLESS_14("rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef"),

    /**
     * rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef (13048000 bytes).
     */
    NEF_D7000_LOSSY_12("rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef"),

    /**
     * rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef (23129498 bytes).
     */
    NEF_D7000_LOSSY_14_SPLIT("rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef"),

    /**
     * rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef (5193213 bytes).
     */
    NEF_D70S_LOSSY_12("rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef"),

    /**
     * rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef (8023561 bytes).
     */
    NEF_E5700("rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef"),

    /**
     * rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef (12671168 bytes).
     */
    NEF_E8400("rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef"),

    /**
     * rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef (28905934 bytes).
     */
    NEF_Z6II_LOSSY_12_SPLIT("rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef"),

    /**
     * rawpixls-olympus-e-410-16bit-4-3.orf (10288341 bytes).
     */
    ORF_E410("rawpixls-olympus-e-410-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-m10-16bit-4-3.orf (15258356 bytes).
     */
    ORF_EM10("rawpixls-olympus-e-m10-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-xz-2-12bit-4-3.orf (19320944 bytes).
     */
    ORF_XZ2("rawpixls-olympus-xz-2-12bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-10-16bit-4-3.orf (7614592 bytes).
     */
    ORF_E10("rawpixls-olympus-e-10-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-m1-16bit-4-3.orf (15410600 bytes).
     */
    ORF_EM1("rawpixls-olympus-e-m1-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-p1-16bit-4-3.orf (12857149 bytes).
     */
    ORF_EP1("rawpixls-olympus-e-p1-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-p3-16bit-4-3.orf (11863288 bytes).
     */
    ORF_EP3("rawpixls-olympus-e-p3-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-p5-16bit-4-3.orf (13857475 bytes).
     */
    ORF_EP5("rawpixls-olympus-e-p5-16bit-4-3.orf"),

    /**
     * rawpixls-olympus-e-pl6-16bit-4-3.orf (14852842 bytes).
     */
    ORF_EPL6("rawpixls-olympus-e-pl6-16bit-4-3.orf"),

    /**
     * rawpixls-om-system-om-1-16bit-4-3-5283.orf (21820729 bytes).
     */
    ORF_OM1("rawpixls-om-system-om-1-16bit-4-3-5283.orf"),

    /**
     * rawpixls-om-system-tg-7-16bit-4-3.orf (13815196 bytes).
     */
    ORF_TG7("rawpixls-om-system-tg-7-16bit-4-3.orf"),

    /**
     * rawpixls-pentax-ist-d-12bit-3-2.pef (13682035 bytes).
     */
    PEF_IST_D("rawpixls-pentax-ist-d-12bit-3-2.pef"),

    /**
     * rawpixls-pentax-ist-dl-12bit-3-2.pef (10359839 bytes).
     */
    PEF_IST_DL("rawpixls-pentax-ist-dl-12bit-3-2.pef"),

    /**
     * rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef (37441124 bytes).
     */
    PEF_K3III_MONOCHROME("rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef"),

    /**
     * rawpixls-pentax-k-5-14bit-3-2.pef (18852928 bytes).
     */
    PEF_K5("rawpixls-pentax-k-5-14bit-3-2.pef"),

    /**
     * rawpixls-pentax-k-x-12bit-3-2.pef (14201244 bytes).
     */
    PEF_PENTAX_KX("rawpixls-pentax-k-x-12bit-3-2.pef"),

    /**
     * rawpixls-fujifilm-finepix-s6500fd-4-3.raf (13698640 bytes).
     */
    RAF_S6500FD("rawpixls-fujifilm-finepix-s6500fd-4-3.raf"),

    /**
     * rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf (26160640 bytes).
     */
    RAF_XA1("rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf"),

    /**
     * rawpixls-fujifilm-x-e2-14bit-14bit-uncompressed-3-2.raf (33800704 bytes).
     */
    RAF_XE2("rawpixls-fujifilm-x-e2-14bit-14bit-uncompressed-3-2.raf"),

    /**
     * rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf (23346352 bytes).
     */
    RAF_XT2_COMPRESSED("rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf"),

    /**
     * rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf (27949104 bytes).
     */
    RAF_XT4_LOSSLESS("rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf"),

    /**
     * rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf (56710368 bytes).
     */
    RAF_GFX50SII_LOSSLESS("rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf"),

    /**
     * rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf (34522544 bytes).
     */
    RAF_GFX50SII_LOSSY("rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf"),

    /**
     * rawpixls-fujifilm-finepix-s5pro-3-2.raf (25616736 bytes).
     */
    RAF_S5PRO("rawpixls-fujifilm-finepix-s5pro-3-2.raf"),

    /**
     * rawpixls-fujifilm-finepix-s5000-4-3.raf (6851240 bytes).
     */
    RAF_S5000("rawpixls-fujifilm-finepix-s5000-4-3.raf"),

    /**
     * rawpixls-fujifilm-finepix-s100fs-4-3.raf (23714912 bytes).
     */
    RAF_S100FS("rawpixls-fujifilm-finepix-s100fs-4-3.raf"),

    /**
     * rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf (24789424 bytes).
     */
    RAF_S1("rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf"),

    /**
     * rawsamples-fujifilm-x10-sn.raf (19830388 bytes; CC BY-NC-SA 4.0, used for testing only): an X10 file in EXR
     * SN mode, from rawsamples.ch.
     */
    RAF_X10_SN("rawsamples-fujifilm-x10-sn.raf"),

    /**
     * rawpixls-fujifilm-dbp-for-gx680-4-3.raf (42714152 bytes).
     */
    RAF_DBP_GX680("rawpixls-fujifilm-dbp-for-gx680-4-3.raf"),

    /**
     * rawpixls-panasonic-dc-g9-4-3-2348.rw2 (24256000 bytes).
     */
    RW2_G9("rawpixls-panasonic-dc-g9-4-3-2348.rw2"),

    /**
     * rawpixls-panasonic-dmc-gh1-1-1.rw2 (11463680 bytes).
     */
    RW2_GH1("rawpixls-panasonic-dmc-gh1-1-1.rw2"),

    /**
     * rawpixls-panasonic-dmc-lx3-1-1.rw2 (9693184 bytes).
     */
    RW2_LX3("rawpixls-panasonic-dmc-lx3-1-1.rw2"),

    /**
     * rawpixls-panasonic-dmc-gf6-4-3.rw2 (19607552 bytes).
     */
    RW2_GF6("rawpixls-panasonic-dmc-gf6-4-3.rw2"),

    /**
     * rawpixls-panasonic-dc-gh5s-4-3-2603.rw2 (19191296 bytes).
     */
    RW2_GH5S("rawpixls-panasonic-dc-gh5s-4-3-2603.rw2"),

    /**
     * rawpixls-panasonic-dc-s1-3-2.rw2 (35988480 bytes).
     */
    RW2_S1("rawpixls-panasonic-dc-s1-3-2.rw2"),

    /**
     * rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2 (37309952 bytes).
     */
    RW2_S5M2("rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2"),

    /**
     * rawpixls-sony-dsc-f828-4-3.srf (17393344 bytes).
     */
    SRF_F828("rawpixls-sony-dsc-f828-4-3.srf"),

    /**
     * rawpixls-minolta-dimage-5-4-3.mrw (6422144 bytes).
     */
    MRW_DIMAGE_5("rawpixls-minolta-dimage-5-4-3.mrw"),

    /**
     * rawpixls-minolta-dynax-7d-3-2.mrw (9182496 bytes).
     */
    MRW_DYNAX_7D("rawpixls-minolta-dynax-7d-3-2.mrw"),

    /**
     * rawpixls-canon-eos-d30-raw-3-2.crw (2993260 bytes).
     */
    CRW_D30("rawpixls-canon-eos-d30-raw-3-2.crw"),

    /**
     * rawpixls-canon-eos-d60-raw-3-2.crw (8591234 bytes).
     */
    CRW_D60("rawpixls-canon-eos-d60-raw-3-2.crw"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2.crw (7787330 bytes).
     */
    CRW_10D("rawpixls-canon-eos-10d-raw-3-2.crw"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2-3164.crw (10822040 bytes).
     */
    CRW_10D_3164("rawpixls-canon-eos-10d-raw-3-2-3164.crw"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2-3165.crw (10583702 bytes).
     */
    CRW_10D_3165("rawpixls-canon-eos-10d-raw-3-2-3165.crw"),

    /**
     * rawpixls-canon-eos-300d-raw-3-2.crw (9056348 bytes).
     */
    CRW_300D("rawpixls-canon-eos-300d-raw-3-2.crw"),

    /**
     * rawpixls-canon-eos-300d-raw-3-2-3162.crw (8389842 bytes).
     */
    CRW_300D_3162("rawpixls-canon-eos-300d-raw-3-2-3162.crw"),

    /**
     * rawpixls-canon-eos-digital-rebel-raw-3-2.crw (10153786 bytes).
     */
    CRW_DIGITAL_REBEL("rawpixls-canon-eos-digital-rebel-raw-3-2.crw"),

    /**
     * rawpixls-canon-powershot-g5-raw-4-3.crw (4894440 bytes).
     */
    CRW_G5("rawpixls-canon-powershot-g5-raw-4-3.crw"),

    /**
     * rawpixls-canon-powershot-g6-raw-4-3.crw (8052032 bytes).
     */
    CRW_G6("rawpixls-canon-powershot-g6-raw-4-3.crw"),

    /**
     * rawpixls-sigma-dp1-3-2-1116.x3f (10317264 bytes).
     */
    X3F_DP1("rawpixls-sigma-dp1-3-2-1116.x3f"),

    /**
     * rawpixls-sigma-sigma-sd14-3-2-4462.x3f (12180112 bytes).
     */
    X3F_SD14("rawpixls-sigma-sigma-sd14-3-2-4462.x3f"),

    /**
     * rawpixls-sigma-sigma-sd15-3-2-1863.x3f (16373048 bytes).
     */
    X3F_SD15("rawpixls-sigma-sigma-sd15-3-2-1863.x3f"),

    /**
     * rawpixls-sigma-sigma-sd1-merrill-3-2-4918.x3f (47774136 bytes).
     */
    X3F_SD1_MERRILL("rawpixls-sigma-sigma-sd1-merrill-3-2-4918.x3f"),

    /**
     * rawpixls-sigma-sigma-dp2-merrill-3-2-855.x3f (49812540 bytes).
     */
    X3F_DP2_MERRILL("rawpixls-sigma-sigma-dp2-merrill-3-2-855.x3f"),

    /**
     * rawpixls-sigma-sigma-dp2-quattro-3-2-5540.x3f (54740948 bytes).
     */
    X3F_DP2_QUATTRO("rawpixls-sigma-sigma-dp2-quattro-3-2-5540.x3f"),

    /**
     * rawpixls-samsung-nx1-14bit-3-2.srw (36896768 bytes).
     */
    SRW_NX1("rawpixls-samsung-nx1-14bit-3-2.srw"),

    /**
     * rawpixls-samsung-nx300-12bit-3-2.srw (22835672 bytes).
     */
    SRW_NX300("rawpixls-samsung-nx300-12bit-3-2.srw"),

    /**
     * rawpixls-samsung-nx1000-12bit-3-2.srw (37588192 bytes).
     */
    SRW_NX1000("rawpixls-samsung-nx1000-12bit-3-2.srw"),

    /**
     * rawpixls-samsung-nx500-12bit-12bit-normal-compression-3-2.srw (20750652 bytes).
     */
    SRW_NX500_12_NORMAL("rawpixls-samsung-nx500-12bit-12bit-normal-compression-3-2.srw"),

    /**
     * rawpixls-samsung-nx500-14bit-14bit-lossless-compression-3-2.srw (32831534 bytes).
     */
    SRW_NX500_14_LOSSLESS("rawpixls-samsung-nx500-14bit-14bit-lossless-compression-3-2.srw"),

    /**
     * rawpixls-samsung-nx-mini-12bit-3-2.srw (21052805 bytes).
     */
    SRW_NX_MINI("rawpixls-samsung-nx-mini-12bit-3-2.srw"),

    /**
     * rawpixls-epson-r-d1-12bit-3-2.erf (9958994 bytes).
     */
    ERF_R_D1("rawpixls-epson-r-d1-12bit-3-2.erf"),

    /**
     * rawpixls-hasselblad-h3d-16bit-4-3.3fr (53594112 bytes).
     */
    FFF_H3D_16BIT_4_3("rawpixls-hasselblad-h3d-16bit-4-3.3fr"),

    /**
     * rawpixls-hasselblad-h4d-40-16bit-4-3.fff (73144108 bytes).
     */
    FFF_H4D_40_16BIT_4_3("rawpixls-hasselblad-h4d-40-16bit-4-3.fff"),

    /**
     * rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr (108399104 bytes).
     */
    FFF_X1D_II_50C_16BIT_4_3("rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr"),

    /**
     * rawpixls-hasselblad-cfv-50c-16bit-4-3.3fr (72417280 bytes).
     */
    FFF_CFV_50C_16BIT_4_3("rawpixls-hasselblad-cfv-50c-16bit-4-3.3fr"),

    /**
     * rawpixls-leaf-aptus-22-16bit-4-3-3196.mos (23423633 bytes).
     */
    MOS_APTUS_22_16BIT_4_3_3196("rawpixls-leaf-aptus-22-16bit-4-3-3196.mos"),

    /**
     * rawpixls-leaf-aptus-22-16bit-4-3-3197.mos (43365202 bytes).
     */
    MOS_APTUS_22_16BIT_4_3_3197("rawpixls-leaf-aptus-22-16bit-4-3-3197.mos"),

    /**
     * rawpixls-kodak-dcs760c-12bit-3-2.dcr (7547392 bytes).
     */
    DCR_DCS760C_12BIT_3_2("rawpixls-kodak-dcs760c-12bit-3-2.dcr"),

    /**
     * rawpixls-kodak-dc120-8bit-4-3.kdc (843328 bytes).
     */
    DCR_DC120_8BIT_4_3("rawpixls-kodak-dc120-8bit-4-3.kdc"),

    /**
     * rawpixls-iiq-iq140-sensorplus-l.tif (10897729 bytes).
     */
    IIQ_IQ140_IIQ_L_SENSOR_4_3("rawpixls-iiq-iq140-sensorplus-l.tif"),

    /**
     * rawpixls-mamiya-zd.mef (36575308 bytes).
     */
    MEF_ZD("rawpixls-mamiya-zd.mef"),

    /**
     * rawpixls-iiq-p40plus-cf100585.iiq (11172571 bytes).
     */
    IIQ_P40_IIQ_S_4_3("rawpixls-iiq-p40plus-cf100585.iiq"),

    /**
     * rawpixls-kodak-p880-nan.kdc (13828802 bytes).
     */
    DCR_P880_NAN("rawpixls-kodak-p880-nan.kdc"),

    /**
     * rawpixls-kodak-eos-dcs-3-8bit-4-3.tif (1374208 bytes).
     */
    DCR_EOS_DCS_3_8BIT_4_3("rawpixls-kodak-eos-dcs-3-8bit-4-3.tif"),

    /**
     * rawpixls-kodak-dcs520c-12bit-3-2.tif (1970973 bytes).
     */
    DCR_DCS520C_12BIT_3_2("rawpixls-kodak-dcs520c-12bit-3-2.tif"),

    /**
     * rawpixls-gopro-hero7-black-16bit-4-3-2908.gpr (3045278 bytes).
     */
    GPR_HERO7_BLACK_16BIT_4_3_2908("rawpixls-gopro-hero7-black-16bit-4-3-2908.gpr"),

    /**
     * rawpixls-panasonic-dmc-fz8-4-3.raw (11615232 bytes).
     */
    RW2_DMC_FZ8_4_3("rawpixls-panasonic-dmc-fz8-4-3.raw"),

    /**
     * rawpixls-panasonic-dmc-fz50-16-9.raw (15255996 bytes).
     */
    RW2_DMC_FZ50_16_9("rawpixls-panasonic-dmc-fz50-16-9.raw"),

    /**
     * rawpixls-panasonic-dmc-lx1-16-9.raw (16841216 bytes).
     */
    RW2_DMC_LX1_16_9("rawpixls-panasonic-dmc-lx1-16-9.raw"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-3-2-7762.nef (27501049 bytes).
     */
    NEF_Z50II_LOSSLESS_DX("rawpixls-nikon-z50-2-8bit-compressed-3-2-7762.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-3-2-7763.nef (17646080 bytes).
     */
    NEF_Z50II_HE_STAR_DX("rawpixls-nikon-z50-2-8bit-compressed-3-2-7763.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-3-2-7764.nef (12438016 bytes).
     */
    NEF_Z50II_HE_DX("rawpixls-nikon-z50-2-8bit-compressed-3-2-7764.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-1-1-7765.nef (18641028 bytes).
     */
    NEF_Z50II_LOSSLESS_1_1("rawpixls-nikon-z50-2-8bit-compressed-1-1-7765.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-1-1-7766.nef (11932672 bytes).
     */
    NEF_Z50II_HE_STAR_1_1("rawpixls-nikon-z50-2-8bit-compressed-1-1-7766.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-1-1-7767.nef (8443392 bytes).
     */
    NEF_Z50II_HE_1_1("rawpixls-nikon-z50-2-8bit-compressed-1-1-7767.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-16-9-7768.nef (23267980 bytes).
     */
    NEF_Z50II_LOSSLESS_16_9("rawpixls-nikon-z50-2-8bit-compressed-16-9-7768.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-16-9-7769.nef (14885888 bytes).
     */
    NEF_Z50II_HE_STAR_16_9("rawpixls-nikon-z50-2-8bit-compressed-16-9-7769.nef"),

    /**
     * rawpixls-nikon-z50-2-8bit-compressed-16-9-7770.nef (10363904 bytes).
     */
    NEF_Z50II_HE_16_9("rawpixls-nikon-z50-2-8bit-compressed-16-9-7770.nef"),

    /**
     * rawpixls-nikon-z6-3-8bit-compressed-3-2-7810.nef (6520320 bytes).
     */
    NEF_Z6III_HE_DX("rawpixls-nikon-z6-3-8bit-compressed-3-2-7810.nef"),

    /**
     * rawpixls-canon-eos-50d-sraw1-mraw-3-2.cr2 (13124350 bytes).
     */
    CR2_50D_MRAW("rawpixls-canon-eos-50d-sraw1-mraw-3-2.cr2"),

    /**
     * rawpixls-panasonic-dc-gh5m2-4-3-5082.rw2 (24210944 bytes).
     */
    RW2_GH5M2("rawpixls-panasonic-dc-gh5m2-4-3-5082.rw2"),

    /**
     * rawpixls-panasonic-dc-s5-3-2-6339.rw2 (43520512 bytes).
     */
    RW2_S5_V7("rawpixls-panasonic-dc-s5-3-2-6339.rw2"),

    /**
     * rawpixls-panasonic-dc-s9-3-2-7702.rw2 (27680768 bytes).
     */
    RW2_S9("rawpixls-panasonic-dc-s9-3-2-7702.rw2"),

    /**
     * rawpixls-sony-dslr-a450-12bit-12bit-compressed-3-2.arw (14712832 bytes).
     */
    ARW_A450_CRAW_12("rawpixls-sony-dslr-a450-12bit-12bit-compressed-3-2.arw"),

    /**
     * rawpixls-sony-dslr-a700-8bit-8bit-compressed-3-2.arw (12967424 bytes).
     */
    ARW_A700_CRAW_8("rawpixls-sony-dslr-a700-8bit-8bit-compressed-3-2.arw"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-16bit-16bit-raw.dng (20460092 bytes).
     */
    DNG_5D3_FLOAT_16("rawpixls-canon-eos-5d-mark-iii-16bit-16bit-raw.dng"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-24bit-24bit-raw.dng (35431526 bytes).
     */
    DNG_5D3_FLOAT_24("rawpixls-canon-eos-5d-mark-iii-24bit-24bit-raw.dng"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-32bit-32bit-raw.dng (56793214 bytes).
     */
    DNG_5D3_FLOAT_32("rawpixls-canon-eos-5d-mark-iii-32bit-32bit-raw.dng"),

    /**
     * rawpixls-samsung-sm-s901e-4-3.dng (42970855 bytes).
     */
    DNG_GALAXY_S22_JPEG_XL("rawpixls-samsung-sm-s901e-4-3.dng"),

    /**
     * rawpixls-samsung-galaxy-s23-16-9.dng (25412616 bytes).
     */
    DNG_GALAXY_S23("rawpixls-samsung-galaxy-s23-16-9.dng"),

    /**
     * rawpixls-leica-m-monochrom-typ-246-12bit-3-2.dng (21700096 bytes).
     */
    DNG_LEICA_M_MONOCHROM("rawpixls-leica-m-monochrom-typ-246-12bit-3-2.dng"),

    /**
     * rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.dng (37669430 bytes).
     */
    DNG_PENTAX_K3III_MONOCHROME("rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.dng"),

    /**
     * rawpixls-leica-m8-8bit-8bit-uncompressed-3-2.dng (10575296 bytes).
     */
    DNG_LEICA_M8("rawpixls-leica-m8-8bit-8bit-uncompressed-3-2.dng"),

    /**
     * rawpixls-sigma-fp-8bit-16-9.dng (2190336 bytes).
     */
    DNG_SIGMA_FP_8("rawpixls-sigma-fp-8bit-16-9.dng"),

    /**
     * rawpixls-sigma-fp-10bit-16-9.dng (2716672 bytes).
     */
    DNG_SIGMA_FP_10("rawpixls-sigma-fp-10bit-16-9.dng"),

    /**
     * rawpixls-blackmagic-micro-cinema-camera-12bit-16-9.dng (1228096 bytes).
     */
    DNG_BLACKMAGIC_MICRO_CINEMA("rawpixls-blackmagic-micro-cinema-camera-12bit-16-9.dng"),

    /**
     * rawpixls-canon-canon-powershot-a410-10bit-10bit-chdk-ver-1-5-0-4-3.dng (4219200 bytes).
     */
    DNG_CHDK_A410("rawpixls-canon-canon-powershot-a410-10bit-10bit-chdk-ver-1-5-0-4-3.dng"),

    /**
     * rawpixls-google-pixel-6-pro-16bit-4-3.dng (11932956 bytes).
     */
    DNG_PIXEL_6_PRO("rawpixls-google-pixel-6-pro-16bit-4-3.dng"),

    /**
     * rawpixls-leica-q-typ-116-14bit-3-2.dng (45100032 bytes).
     */
    DNG_LEICA_Q("rawpixls-leica-q-typ-116-14bit-3-2.dng"),

    /**
     * rawpixls-plustek-opticfilm-8100-16bit-4-3.dng (9601902 bytes).
     */
    DNG_PLUSTEK_8100("rawpixls-plustek-opticfilm-8100-16bit-4-3.dng"),

    /**
     * rawpixls-arashi-vision-insta360-one-x-16bit-16bit-2.dng (36967459 bytes).
     */
    DNG_INSTA360_ONE_X("rawpixls-arashi-vision-insta360-one-x-16bit-16bit-2.dng"),

    /**
     * rawpixls-nikon-coolpix-p7800-12bit-12bit-compressed-7-4-3.nrw (26992101 bytes).
     */
    NRW_P7800("rawpixls-nikon-coolpix-p7800-12bit-12bit-compressed-7-4-3.nrw"),

    /**
     * rawpixls-nikon-coolpix-b700-12bit-12bit-uncompressed-4-3.nrw (33056896 bytes).
     */
    NRW_B700("rawpixls-nikon-coolpix-b700-12bit-12bit-uncompressed-4-3.nrw"),

    /**
     * rawpixls-nikon-z-6-14bit-14bit-uncompressed-3-2.nef (47494144 bytes).
     */
    NEF_Z6_UNCOMPRESSED_14("rawpixls-nikon-z-6-14bit-14bit-uncompressed-3-2.nef"),

    /**
     * rawpixls-nikon-z-7-2-12bit-12bit-uncompressed-3-2.nef (73983744 bytes).
     */
    NEF_Z7II_UNCOMPRESSED_12("rawpixls-nikon-z-7-2-12bit-12bit-uncompressed-3-2.nef"),

    /**
     * rawpixls-nikon-d810-12bit-12bit-compressed-8-small-raw-3-2.nef (29011200 bytes).
     */
    NEF_D810_SMALL_RAW("rawpixls-nikon-d810-12bit-12bit-compressed-8-small-raw-3-2.nef"),

    /**
     * rawpixls-sony-ilce-1m2-14bit-lossless-3-2.arw (26370048 bytes).
     */
    ARW_A1M2_LOSSLESS("rawpixls-sony-ilce-1m2-14bit-lossless-3-2.arw"),

    /**
     * rawpixls-sony-ilce-9m3-3-2.arw (15413248 bytes).
     */
    ARW_A9M3("rawpixls-sony-ilce-9m3-3-2.arw"),

    /**
     * rawpixls-sony-ilce-7m5-14bit-3-2.arw (11481088 bytes).
     */
    ARW_A7M5("rawpixls-sony-ilce-7m5-14bit-3-2.arw"),

    /**
     * rawpixls-phase-one-iq140-iiq-sv2-sensor-4-3.tif (6835489 bytes).
     */
    IIQ_IQ140_SV2("rawpixls-phase-one-iq140-iiq-sv2-sensor-4-3.tif"),

    /**
     * rawpixls-om-system-om-5-16bit-4-3.ori (19841155 bytes).
     */
    ORI_OM5("rawpixls-om-system-om-5-16bit-4-3.ori"),

    /**
     * gpr-fusion-GPBK7066.GPR (3056384 bytes).
     */
    GPR_FUSION_BACK("gpr-fusion-GPBK7066.GPR"),

    /**
     * gpr-fusion-GPFR7066.GPR (3195446 bytes).
     */
    GPR_FUSION_FRONT("gpr-fusion-GPFR7066.GPR"),

    /**
     * gpr-hero5-GOPR2657.GPR (4966922 bytes).
     */
    GPR_HERO5("gpr-hero5-GOPR2657.GPR"),

    /**
     * gpr-hero6-GOPR0024.GPR (3071480 bytes).
     */
    GPR_HERO6("gpr-hero6-GOPR0024.GPR"),

    /**
     * gpr-hero7-GOPR9231.GPR (4153712 bytes).
     */
    GPR_HERO7("gpr-hero7-GOPR9231.GPR"),

    /**
     * gpr-hero9-GOPR0002.GPR (6763778 bytes).
     */
    GPR_HERO9("gpr-hero9-GOPR0002.GPR");

    private final String file;

    RawSamples(String file) {
        this.file = file;
    }

    /**
     * Returns the sample, read from the hosted test data.
     *
     * @return the file
     */
    public Path path() {
        return Conformance.vector(file);
    }

    public String fileName() {
        return file;
    }
}
