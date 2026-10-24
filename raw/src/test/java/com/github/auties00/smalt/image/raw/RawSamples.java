package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.testkit.Conformance;

import java.nio.file.Path;

/**
 * The camera raw sample files of the tests: CC0 files of the raw.pixls.us archive, fetched and
 * verified through {@link Conformance} (the test using one is skipped when it is unavailable).
 */
public enum RawSamples {
    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossless-jpeg-compression-rgb-3-2.dng (24103910 bytes).
     */
    ADC_5D3_LINEAR_LOSSLESS("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossless-jpeg-compression-rgb-3-2.dng", "https://raw.pixls.us/getfile.php/1032/nice/Adobe%20DNG%20Converter%20-%20Canon%20EOS%205D%20Mark%20III%20-%20Lossless%20JPEG%20compression%2C%20rgb%20%283%3A2%29.DNG",
            "0608c9f277307b745530e61b589c465540611df22f9a1fe2d01237c5b2b4c13b"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-rgb-3-2.dng (2484836 bytes).
     */
    ADC_5D3_LINEAR_LOSSY("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-rgb-3-2.dng", "https://raw.pixls.us/getfile.php/1033/nice/Adobe%20DNG%20Converter%20-%20Canon%20EOS%205D%20Mark%20III%20-%20Lossy%20JPEG%20compression%2C%20rgb%20%283%3A2%29.DNG",
            "b22f1e36331f679abb8b13e433b9bbde3988723adf10fee7aa0dda6381016a98"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-lossless-jpeg-compression-3-2.dng (23206642 bytes).
     */
    ADC_5D3_LOSSLESS("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-lossless-jpeg-compression-3-2.dng", "https://raw.pixls.us/getfile.php/1024/nice/Adobe%20DNG%20Converter%20-%20Canon%20EOS%205D%20Mark%20III%20-%2016bit%2016bit%20Lossless%20JPEG%20compression%20%283%3A2%29.DNG",
            "3118116735d4f6dd01fd9d6a80ee9aac52a2c2debdc6dcd112bd5e3059044a55"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-3-2.dng (6193902 bytes).
     */
    ADC_5D3_LOSSY("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-3-2.dng", "https://raw.pixls.us/getfile.php/1023/nice/Adobe%20DNG%20Converter%20-%20Canon%20EOS%205D%20Mark%20III%20-%20Lossy%20JPEG%20compression%20%283%3A2%29.DNG",
            "159326856c29073e845c3c5a9ecf98c6474f43ca15798a88ad5e2baecd0664b7"),

    /**
     * rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-uncompressed-3-2.dng (48382640 bytes).
     */
    ADC_5D3_UNCOMPRESSED("rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-16bit-16bit-uncompressed-3-2.dng", "https://raw.pixls.us/getfile.php/1022/nice/Adobe%20DNG%20Converter%20-%20Canon%20EOS%205D%20Mark%20III%20-%2016bit%2016bit%20uncompressed%20%283%3A2%29.DNG",
            "95dc76a2f255e623acaa5149b320389f7da7c995da890c3e0d511c0ff681b2e1"),

    /**
     * rawpixls-sony-ilce-7m2-14bit-14bit-uncompressed-3-2.arw (49512448 bytes).
     */
    ARW_A7M2_UNCOMPRESSED_14("rawpixls-sony-ilce-7m2-14bit-14bit-uncompressed-3-2.arw", "https://raw.pixls.us/getfile.php/851/nice/Sony%20-%20ILCE-7M2%20-%2014bit%2014bit%20uncompressed%20%283%3A2%29.ARW",
            "a37752f65c1fabb48178bfbe2a81f1e7dc705a3363652e984792527b43700ada"),

    /**
     * rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw (16646144 bytes).
     */
    ARW_NEX6_CRAW_12("rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw", "https://raw.pixls.us/getfile.php/782/nice/Sony%20-%20NEX-6%20-%2012bit%2012bit%20compressed%20%283%3A2%29.ARW",
            "bf4c6d21136aa4fd626212fe72b962b6404e3fca45cdc3b6afbed8e73fee2cf8"),

    /**
     * rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2 (5805950 bytes).
     */
    CR2_40D_SRAW("rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2", "https://raw.pixls.us/getfile.php/2102/nice/Canon%20-%20EOS%2040D%20-%20sRAW2%20%28sRAW%29%20%283%3A2%29.CR2",
            "ba644e7dd2abe74eca260e67f0206ff113bf0f62e710f8130611e964d6be5bf1"),

    /**
     * rawpixls-canon-eos-5d-mark-iii.cr2 (25965009 bytes).
     */
    CR2_5D3("rawpixls-canon-eos-5d-mark-iii.cr2", "https://raw.pixls.us/getfile.php/771/nice/Canon%20-%20EOS%205D%20Mark%20III.CR2",
            "ec069b178b9383d80c72f8cdc1b79bd54ca451e7c20793b109d8ad73bef6bdf6"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2 (12343414 bytes).
     */
    CR2_5D3_SRAW("rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2", "https://raw.pixls.us/getfile.php/773/nice/Canon%20-%20EOS%205D%20Mark%20III%20-%20sRAW2%20%28sRAW%29.CR2",
            "a79065e78a6c5bdbc8726e786ade02d54a7d496cc3cb4504d804c924a3d999da"),

    /**
     * rawpixls-canon-eos-7d-sraw1-mraw-3-2.cr2 (16253693 bytes).
     */
    CR2_7D_MRAW("rawpixls-canon-eos-7d-sraw1-mraw-3-2.cr2", "https://raw.pixls.us/getfile.php/130/nice/Canon%20-%20EOS%207D%20-%20sRAW1%20%28mRAW%29%20%283%3A2%29.CR2",
            "25c746a58363e910e6046a96cf2915fff45fe2995676eb2c97842d0c156d03a7"),

    /**
     * rawpixls-canon-eos-7d-sraw2-sraw-3-2.cr2 (11062629 bytes).
     */
    CR2_7D_SRAW("rawpixls-canon-eos-7d-sraw2-sraw-3-2.cr2", "https://raw.pixls.us/getfile.php/129/nice/Canon%20-%20EOS%207D%20-%20sRAW2%20%28sRAW%29%20%283%3A2%29.CR2",
            "3789b1ba5d880613104637c40a06619cd9f3b54b0cf62d2d95b9f9e885edcf6e"),

    /**
     * rawpixls-canon-eos-250d-raw-3-2.cr3 (26886282 bytes).
     */
    CR3_250D("rawpixls-canon-eos-250d-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3387/nice/Canon%20-%20EOS%20250D%20-%20RAW%20%283%3A2%29.CR3",
            "ed1bd1bb8dbcf31735fbe52eeabdbd3a32236eda9cb20b132e8847cb0d89517f"),

    /**
     * rawpixls-canon-powershot-g1-raw-4-3.crw (2300702 bytes).
     */
    CRW_G1("rawpixls-canon-powershot-g1-raw-4-3.crw", "https://raw.pixls.us/getfile.php/2073/nice/Canon%20-%20PowerShot%20G1%20-%20RAW%20%284%3A3%29.crw",
            "f39be5acf14057ce04527fd8d0574e57ec8ed5fed082d7bfaff763075a44ee8a"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng (1538165 bytes).
     */
    DNG_5D3_14BIT_SMALL("rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng", "https://raw.pixls.us/getfile.php/2204/nice/Canon%20-%20EOS%205D%20Mark%20III%20-%2014bit%2014bit%20%282.3471882640587%29.dng",
            "1d77dcc6cdb839b10c1948f0b4471984ab48cf15e973fb77520584cee72b603e"),

    /**
     * rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng (1831164 bytes).
     */
    DNG_5D3_16BIT_SMALL("rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng", "https://raw.pixls.us/getfile.php/2649/nice/Canon%20-%20EOS%205D%20Mark%20III%20-%2016bit%20%2816%3A9%29.dng",
            "bc29ac8a37c800346749b9a5a1adc2722357f7f0e49df05a7104454881604b92"),

    /**
     * rawpixls-canon-eos-7d-mark-ii-16bit-3-2.dng (38265824 bytes).
     */
    DNG_7D2_JPEG_XL("rawpixls-canon-eos-7d-mark-ii-16bit-3-2.dng", "https://raw.pixls.us/getfile.php/7023/nice/Canon%20-%20EOS%207D%20Mark%20II%20-%2016bit%20%283%3A2%29.dng",
            "090e01f9a7a860b2f2f0ead770b8a9ba3b17ea98dc1997835c6bda2fc0a2ae54"),

    /**
     * rawpixls-apple-iphone-12-pro-8bit-4-3.dng (29195592 bytes).
     */
    DNG_IPHONE_12_PRO("rawpixls-apple-iphone-12-pro-8bit-4-3.dng", "https://raw.pixls.us/getfile.php/4264/nice/Apple%20-%20iPhone%2012%20Pro%20-%208bit%20%284%3A3%29.DNG",
            "e91e77a4533ed7cce551d83330676ea5c47dd5e55fb38adda7819366afdbdfc2"),

    /**
     * rawpixls-apple-iphone-7-plus-16bit-4-3.dng (10550105 bytes).
     */
    DNG_IPHONE_7_PLUS("rawpixls-apple-iphone-7-plus-16bit-4-3.dng", "https://raw.pixls.us/getfile.php/939/nice/Apple%20-%20iPhone%207%20Plus%20-%2016bit%20%284%3A3%29.DNG",
            "e8c7ebd8f22f7281d165496b91c8fea8a8e79c8113172fb499e1d66d6045332e"),

    /**
     * rawpixls-leica-m9-8bit-8bit-compressed-3-2.dng (18324480 bytes).
     */
    DNG_LEICA_M9("rawpixls-leica-m9-8bit-8bit-compressed-3-2.dng", "https://raw.pixls.us/getfile.php/753/nice/Leica%20-%20M9%20-%208bit%208bit%20compressed%20%283%3A2%29.DNG",
            "d1d162fce62210b8951c862189a6098329b5163b7f7492dc397c6e53bba04239"),

    /**
     * rawpixls-leica-x2-12bit-3-2.dng (24453726 bytes).
     */
    DNG_LEICA_X2("rawpixls-leica-x2-12bit-3-2.dng", "https://raw.pixls.us/getfile.php/882/nice/Leica%20-%20X2%20-%2012bit%20%283%3A2%29.DNG",
            "a67eca6271afb10510ffc9cf998b641d0e8a0ebfa4c779a7bc0bfed5d28b62dd"),

    /**
     * rawpixls-pentax-k-3-14bit-3-2.dng (31633373 bytes).
     */
    DNG_PENTAX_K3("rawpixls-pentax-k-3-14bit-3-2.dng", "https://raw.pixls.us/getfile.php/1074/nice/Pentax%20-%20K-3%20-%2014bit%20%283%3A2%29.DNG",
            "23adc89df282c3b09d6721cb4a6330b0c6ac8b2cb93e66526be2b2f86e01039d"),

    /**
     * rawpixls-pentax-k-5-ii-14bit-3-2.dng (22293737 bytes).
     */
    DNG_PENTAX_K5II("rawpixls-pentax-k-5-ii-14bit-3-2.dng", "https://raw.pixls.us/getfile.php/873/nice/Pentax%20-%20K-5%20II%20-%2014bit%20%283%3A2%29.DNG",
            "aa41f0d9ab8d3e21e468b4c61d4d1ddae6a4fbe37f629b78159246cbb94dd56b"),

    /**
     * rawpixls-pentax-k-x-12bit-3-2.dng (14359791 bytes).
     */
    DNG_PENTAX_KX("rawpixls-pentax-k-x-12bit-3-2.dng", "https://raw.pixls.us/getfile.php/830/nice/Pentax%20-%20K-x%20-%2012bit%20%283%3A2%29.DNG",
            "7a594b66b54f99bb5aba4627c78abfc41213d6911ea54e4f5d2dd6c8505f6968"),

    /**
     * rawpixls-google-pixel-2-xl-16bit-4-3.dng (24479668 bytes).
     */
    DNG_PIXEL_2_XL("rawpixls-google-pixel-2-xl-16bit-4-3.dng", "https://raw.pixls.us/getfile.php/2206/nice/Google%20-%20Pixel%202%20XL%20-%2016bit%20%284%3A3%29.dng",
            "5541093a369f0487fef92bcd1f5fef1be6e4e3cec2aa4d52ad38d2211f3309d7"),

    /**
     * rawpixls-ricoh-gr-12bit-4-3.dng (12129815 bytes).
     */
    DNG_RICOH_GR("rawpixls-ricoh-gr-12bit-4-3.dng", "https://raw.pixls.us/getfile.php/996/nice/Ricoh%20-%20GR%20-%2012bit%20%284%3A3%29.DNG",
            "7ceec1ea31c56b6e0414202504581b915f17818ffe018b4c8bba1393332c405e"),

    /**
     * rawpixls-samsung-gx20-12bit-3-2.dng (24061568 bytes).
     */
    DNG_SAMSUNG_GX20("rawpixls-samsung-gx20-12bit-3-2.dng", "https://raw.pixls.us/getfile.php/1537/nice/Samsung%20-%20GX20%20-%2012bit%20%283%3A2%29.DNG",
            "5557016b2712b621befdb05731923562a802beafd13e0f366737390a8f7b3286"),

    /**
     * rawpixls-fujifilm-x100s-nan.dng (20729906 bytes).
     */
    DNG_X100S("rawpixls-fujifilm-x100s-nan.dng", "https://raw.pixls.us/getfile.php/2644/nice/Fujifilm%20-%20X100S%20-%20NAN.dng",
            "7adec564bbf74858c70b5a01a2b744000f8057303169fc70d91bde7cb435530f"),

    /**
     * rawpixls-nikon-d300s-12bit-12bit-compressed-lossless-3-2.nef (12064037 bytes).
     */
    NEF_D300S_LOSSLESS_12("rawpixls-nikon-d300s-12bit-12bit-compressed-lossless-3-2.nef", "https://raw.pixls.us/getfile.php/3054/nice/Nikon%20-%20D300S%20-%2012bit%2012bit%20compressed%20%28Lossless%29%20%283%3A2%29.NEF",
            "c6723c94d8f3054013b2e927de7b82ae60df16518e737a9f10f73596285b37af"),

    /**
     * rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef (15142984 bytes).
     */
    NEF_D300S_LOSSLESS_14("rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef", "https://raw.pixls.us/getfile.php/3049/nice/Nikon%20-%20D300S%20-%2014bit%2014bit%20compressed%20%28Lossless%29%20%283%3A2%29.NEF",
            "de950a2263c87f085ee611a21e69fc4e0ecb6e417fff61ff6a23ea3121e21a81"),

    /**
     * rawpixls-nikon-d5100-14bit-14bit-uncompressed-3-2.nef (34245856 bytes).
     */
    NEF_D5100_UNCOMPRESSED_14("rawpixls-nikon-d5100-14bit-14bit-uncompressed-3-2.nef", "https://raw.pixls.us/getfile.php/1598/nice/Nikon%20-%20D5100%20-%2014bit%2014bit%20uncompressed%20%283%3A2%29.nef",
            "2fb2f1b87a3b89de5ba9e56527f21a912ca624f2fa2a594cef108ee8d7180412"),

    /**
     * rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef (28692945 bytes).
     */
    NEF_D5600_LOSSY_12_SPLIT("rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef", "https://raw.pixls.us/getfile.php/2261/nice/Nikon%20-%20D5600%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "7ea7d00ec7c4de55398ad258c6dd9952237ab8110ea0bfc7b29b4e17c1090719"),

    /**
     * rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef (19040701 bytes).
     */
    NEF_D7000_LOSSLESS_14("rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef", "https://raw.pixls.us/getfile.php/958/nice/Nikon%20-%20D7000%20-%2014bit%2014bit%20compressed%20%28Lossless%29%20%283%3A2%29.NEF",
            "639945753240005d80488c0462c01e73a7ab82c96d3d1cdb6661f02e1b3c1319"),

    /**
     * rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef (13048000 bytes).
     */
    NEF_D7000_LOSSY_12("rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/961/nice/Nikon%20-%20D7000%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "986db2bf9ed08cf095bb94cfbaf02f0e89d123edad28b63c05133e7815749486"),

    /**
     * rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef (23129498 bytes).
     */
    NEF_D7000_LOSSY_14_SPLIT("rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef", "https://raw.pixls.us/getfile.php/2578/nice/Nikon%20-%20D7000%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.nef",
            "6d5aab3dbab2d77deab82ea5dfc72608e60a97315d869d6a55dac596e8691616"),

    /**
     * rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef (5193213 bytes).
     */
    NEF_D70S_LOSSY_12("rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef", "https://raw.pixls.us/getfile.php/1117/nice/Nikon%20-%20D70s%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%201%29%29%20%283%3A2%29.NEF",
            "6c46f9854033e7623ebbb5de8c951df6762ca44f05d45035ccc5cb7e9f4e5692"),

    /**
     * rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef (8023561 bytes).
     */
    NEF_E5700("rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef", "https://raw.pixls.us/getfile.php/4456/nice/Nikon%20-%20E5700%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.NEF",
            "6efeb5049cb4957f0586c47478caed2c5cfdce23eed6b65f5e65ab50ab7c1294"),

    /**
     * rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef (12671168 bytes).
     */
    NEF_E8400("rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef", "https://raw.pixls.us/getfile.php/2082/nice/Nikon%20-%20E8400%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.NEF",
            "84ca3fd7f6e684f1469bd02f1d715b4ccb0716ce0bd42200aeda32ef5b9c8562"),

    /**
     * rawpixls-nikon-coolpix-e8800-12bit-12bit-uncompressed-4-3.nef (12671168 bytes).
     */
    NEF_E8800("rawpixls-nikon-coolpix-e8800-12bit-12bit-uncompressed-4-3.nef", "https://raw.pixls.us/getfile.php/2769/nice/Nikon%20-%20Coolpix%20E8800%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.NEF",
            "4a9bc7214e5eee808f46bc64cfa7d50d830e094f92253b52bb30a6abee684a78"),

    /**
     * rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef (28905934 bytes).
     */
    NEF_Z6II_LOSSY_12_SPLIT("rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4164/nice/Nikon%20-%20Z%206_2%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "85e9ed4bce4feb439e9e05c3845d557b76d10080c239358821a00759afd9314b"),

    /**
     * rawpixls-olympus-e-1-16bit-4-3.orf (10661632 bytes).
     */
    ORF_E1("rawpixls-olympus-e-1-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1800/nice/Olympus%20-%20E-1%20-%2016bit%20%284%3A3%29.ORF",
            "042286653fbae5b085bef4a4e626145385ea6e82e817f166b4904abcef64457c"),

    /**
     * rawpixls-olympus-e-410-16bit-4-3.orf (10288341 bytes).
     */
    ORF_E410("rawpixls-olympus-e-410-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1173/nice/Olympus%20-%20E-410%20-%2016bit%20%284%3A3%29.ORF",
            "b545f3d831275d7febb1d02bfa22b03078eeff27fcb4c5be6806e89857bb1ed7"),

    /**
     * rawpixls-olympus-e-m10-16bit-4-3.orf (15258356 bytes).
     */
    ORF_EM10("rawpixls-olympus-e-m10-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1331/nice/Olympus%20-%20E-M10%20-%2016bit%20%284%3A3%29.ORF",
            "d7f6ac99ee1f824e81ec0e71599f0f87f9b2e2675f7ddfdb21202281b8bdc5e0"),

    /**
     * rawpixls-olympus-xz-2-12bit-4-3.orf (19320944 bytes).
     */
    ORF_XZ2("rawpixls-olympus-xz-2-12bit-4-3.orf", "https://raw.pixls.us/getfile.php/1432/nice/Olympus%20-%20XZ-2%20-%2012bit%20%284%3A3%29.orf",
            "675fefe7c9e99281783b987889d723b5f46692f5c20e19fed5e422c7c6d2636b"),

    /**
     * rawpixls-olympus-c5060wz-12bit-4-3.orf (7646208 bytes).
     */
    ORF_C5060WZ("rawpixls-olympus-c5060wz-12bit-4-3.orf", "https://raw.pixls.us/getfile.php/1799/nice/Olympus%20-%20C5060WZ%20-%2012bit%20%284%3A3%29.ORF",
            "3fc12bc3f6320cfab456111d7805b446e329fdd1907680be55e2e163e5e084bd"),

    /**
     * rawpixls-olympus-e-10-16bit-4-3.orf (7614592 bytes).
     */
    ORF_E10("rawpixls-olympus-e-10-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/5424/nice/Olympus%20-%20E-10%20-%2016bit%20%284%3A3%29.ORF",
            "2bfdade72439017a60a47aad1e5bbcb1aca36f2be7a21e94a4257a678fb6f4da"),

    /**
     * rawpixls-olympus-e-400-16bit-4-3.orf (22052992 bytes).
     */
    ORF_E400("rawpixls-olympus-e-400-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/2151/nice/Olympus%20-%20E-400%20-%2016bit%20%284%3A3%29.ORF",
            "1354e0ac98ea90820227650031d8215637a2aaabd15de995c5a1e00b1986261b"),

    /**
     * rawpixls-olympus-e-m1-16bit-4-3.orf (15410600 bytes).
     */
    ORF_EM1("rawpixls-olympus-e-m1-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1051/nice/Olympus%20-%20E-M1%20-%2016bit%20%284%3A3%29.orf",
            "19ba17e778ae802d4039f0e88bb1cd68825780788b8058c479067e32b9a63eda"),

    /**
     * rawpixls-olympus-e-p1-16bit-4-3.orf (12857149 bytes).
     */
    ORF_EP1("rawpixls-olympus-e-p1-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/5099/nice/Olympus%20-%20E-P1%20-%2016bit%20%284%3A3%29.ORF",
            "862b9d409279af0528810aa02eb5b4140d7854f9d637d4188b82ebaf4bf0d4cf"),

    /**
     * rawpixls-olympus-e-p3-16bit-4-3.orf (11863288 bytes).
     */
    ORF_EP3("rawpixls-olympus-e-p3-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/241/nice/Olympus%20-%20E-P3%20-%2016bit%20%284%3A3%29.ORF",
            "540edabdd9d4e6ef8602f56443730cc3133d714dc4d3d2c607e2f0aa6be5cbcd"),

    /**
     * rawpixls-olympus-e-p5-16bit-4-3.orf (13857475 bytes).
     */
    ORF_EP5("rawpixls-olympus-e-p5-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1090/nice/Olympus%20-%20E-P5%20-%2016bit%20%284%3A3%29.ORF",
            "a1345ebe8d8722e791cbd52778e73257c5ff457ee13988fd78eac61eec841c21"),

    /**
     * rawpixls-olympus-e-pl6-16bit-4-3.orf (14852842 bytes).
     */
    ORF_EPL6("rawpixls-olympus-e-pl6-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/1430/nice/Olympus%20-%20E-PL6%20-%2016bit%20%284%3A3%29.orf",
            "38d3750abbcf847bba58e254b2ab8c57d8c9f3fc48490c29ae2b55c4e84d5f64"),

    /**
     * rawpixls-om-system-om-1-16bit-4-3-5283.orf (21820729 bytes).
     */
    ORF_OM1("rawpixls-om-system-om-1-16bit-4-3-5283.orf", "https://raw.pixls.us/getfile.php/5283/nice/OM%20System%20-%20OM-1%20-%2016bit%20%284%3A3%29.ORF",
            "bf2eef6becc0851e48231617c3e2eac27a2cdb44c75b93035c561d837952c18b"),

    /**
     * rawpixls-om-system-tg-7-16bit-4-3.orf (13815196 bytes).
     */
    ORF_TG7("rawpixls-om-system-tg-7-16bit-4-3.orf", "https://raw.pixls.us/getfile.php/6946/nice/OM%20System%20-%20TG-7%20-%2016bit%20%284%3A3%29.ORF",
            "4f7344c67bfcfcfec45f754195ba612fc2ad6312f5fab87812bf04b9e9e7d46a"),

    /**
     * rawpixls-pentax-ist-d-12bit-3-2.pef (13682035 bytes).
     */
    PEF_IST_D("rawpixls-pentax-ist-d-12bit-3-2.pef", "https://raw.pixls.us/getfile.php/1843/nice/Pentax%20-%20%2Aist%20D%20-%2012bit%20%283%3A2%29.PEF",
            "61904b594d16983070f3e56d663ae8f7b246b8bb554c4cf7bd3eb4b9f9a9f3aa"),

    /**
     * rawpixls-pentax-ist-dl-12bit-3-2.pef (10359839 bytes).
     */
    PEF_IST_DL("rawpixls-pentax-ist-dl-12bit-3-2.pef", "https://raw.pixls.us/getfile.php/1083/nice/Pentax%20-%20%2Aist%20DL%20-%2012bit%20%283%3A2%29.PEF",
            "0e6cd3c999a6dbf121591b2c58aaa8216e625f549db46af627f7346ccf1cf662"),

    /**
     * rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef (37441124 bytes).
     */
    PEF_K3III_MONOCHROME("rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef", "https://raw.pixls.us/getfile.php/7082/nice/Pentax%20-%20K-3%20Mark%20III%20Monochrome%20-%2014bit%20%283%3A2%29.PEF",
            "2d47df9825d1f0fa14321b589f2934678b19dcb28d74210b86bf8a0efc7d514a"),

    /**
     * rawpixls-pentax-k-5-14bit-3-2.pef (18852928 bytes).
     */
    PEF_K5("rawpixls-pentax-k-5-14bit-3-2.pef", "https://raw.pixls.us/getfile.php/1480/nice/Pentax%20-%20K-5%20-%2014bit%20%283%3A2%29.PEF",
            "1fefefbccd73ba05c71f49b724e1f52bb343e2ac4f52f3bc93d828f5ee02cf6c"),

    /**
     * rawpixls-pentax-k-s1-12bit-3-2.pef (21463813 bytes).
     */
    PEF_KS1("rawpixls-pentax-k-s1-12bit-3-2.pef", "https://raw.pixls.us/getfile.php/4365/nice/Pentax%20-%20K-S1%20-%2012bit%20%283%3A2%29.PEF",
            "9e6a9c88bab021719c293655aa32d9eac33a1816b9b2e54d1f9adb225ee280a1"),

    /**
     * rawpixls-pentax-k-x-12bit-3-2.pef (14201244 bytes).
     */
    PEF_PENTAX_KX("rawpixls-pentax-k-x-12bit-3-2.pef", "https://raw.pixls.us/getfile.php/831/nice/Pentax%20-%20K-x%20-%2012bit%20%283%3A2%29.PEF",
            "51a3f22d702353b246684dca79f83c79cfdbdf9892702ae5ac55dd469e239bb6"),

    /**
     * rawpixls-fujifilm-finepix-s2pro-3-2.raf (13211656 bytes).
     */
    RAF_S2PRO("rawpixls-fujifilm-finepix-s2pro-3-2.raf", "https://raw.pixls.us/getfile.php/3562/nice/Fujifilm%20-%20FinePix%20S2Pro%20-%203%3A2.RAF",
            "9fe1bf1361f015d9db862228e4a26b23d69e81015ed4a032eaefb8242ae9ad42"),

    /**
     * rawpixls-fujifilm-finepix-s6500fd-4-3.raf (13698640 bytes).
     */
    RAF_S6500FD("rawpixls-fujifilm-finepix-s6500fd-4-3.raf", "https://raw.pixls.us/getfile.php/3122/nice/Fujifilm%20-%20FinePix%20S6500fd%20-%204%3A3.RAF",
            "ee2a7afc2afc9e3a85ca9e5e237c68b612dc360fb45c6fb4495ef857abbab48e"),

    /**
     * rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf (26160640 bytes).
     */
    RAF_XA1("rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", "https://raw.pixls.us/getfile.php/1214/nice/Fujifilm%20-%20X-A1%20-%2012bit%2012bit%20uncompressed%20%283%3A2%29.RAF",
            "b2ddb5fe31a3b976d12961c72a2689de8742739df3d8f757614eca65f8df2a61"),

    /**
     * rawpixls-fujifilm-x-e2-14bit-14bit-uncompressed-3-2.raf (33800704 bytes).
     */
    RAF_XE2("rawpixls-fujifilm-x-e2-14bit-14bit-uncompressed-3-2.raf", "https://raw.pixls.us/getfile.php/937/nice/Fujifilm%20-%20X-E2%20-%2014bit%2014bit%20uncompressed%20%283%3A2%29.RAF",
            "7588c70af7fd6be3abcec52546aabcd04e0aafc6541e7f7f50bdc60b60513c57"),

    /**
     * rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf (23346352 bytes).
     */
    RAF_XT2_COMPRESSED("rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/865/nice/Fujifilm%20-%20X-T2%20-%2014bit%2014bit%20compressed%20%283%3A2%29.RAF",
            "f756ad5be551a8b3e1b503a8a6f7e8e637fba519c0b0f8c79dd58e94b0287602"),

    /**
     * rawpixls-panasonic-dc-g9-4-3-2348.rw2 (24256000 bytes).
     */
    RW2_G9("rawpixls-panasonic-dc-g9-4-3-2348.rw2", "https://raw.pixls.us/getfile.php/2348/nice/Panasonic%20-%20DC-G9%20-%204%3A3.RW2",
            "16282357ce0143ff7d36737f82074662db7aa0790ecd309196f406baf0f586a7"),

    /**
     * rawpixls-panasonic-dmc-gh1-1-1.rw2 (11463680 bytes).
     */
    RW2_GH1("rawpixls-panasonic-dmc-gh1-1-1.rw2", "https://raw.pixls.us/getfile.php/1320/nice/Panasonic%20-%20DMC-GH1%20-%201%3A1.RW2",
            "85472d8af0421f0357f52bde6512fcc2c0ff00216f78ddcbd6b389dd442cda27"),

    /**
     * rawpixls-panasonic-dmc-lx3-1-1.rw2 (9693184 bytes).
     */
    RW2_LX3("rawpixls-panasonic-dmc-lx3-1-1.rw2", "https://raw.pixls.us/getfile.php/1395/nice/Panasonic%20-%20DMC-LX3%20-%201%3A1.RW2",
            "6663d5b6539de15640ded2b0f422c8affb8cf1bec93e27b3548e970226c063a5"),

    /**
     * rawpixls-panasonic-dc-g9m2-4-3-6999.rw2 (41250304 bytes).
     */
    RW2_G9M2("rawpixls-panasonic-dc-g9m2-4-3-6999.rw2", "https://raw.pixls.us/getfile.php/6999/nice/Panasonic%20-%20DC-G9M2%20-%204%3A3.RW2",
            "dd4bc19ed6cf6625608a1ff14ce1a9d16692e1bf1d50993a3fc076e18905b662"),

    /**
     * rawpixls-panasonic-dmc-gf6-4-3.rw2 (19607552 bytes).
     */
    RW2_GF6("rawpixls-panasonic-dmc-gf6-4-3.rw2", "https://raw.pixls.us/getfile.php/739/nice/Panasonic%20-%20DMC-GF6%20-%204%3A3.RW2",
            "dace6b3818f00a5d4f7cc1cfc29744d15dcf7dd8684c882bea4a81c6ef670ad0"),

    /**
     * rawpixls-panasonic-dc-gh5s-4-3-2603.rw2 (19191296 bytes).
     */
    RW2_GH5S("rawpixls-panasonic-dc-gh5s-4-3-2603.rw2", "https://raw.pixls.us/getfile.php/2603/nice/Panasonic%20-%20DC-GH5S%20-%204%3A3.RW2",
            "b617151876e62b492641cef14b8c98ee3797e5014d24c6af1c780715a501fa53"),

    /**
     * rawpixls-panasonic-dc-s1-3-2.rw2 (35988480 bytes).
     */
    RW2_S1("rawpixls-panasonic-dc-s1-3-2.rw2", "https://raw.pixls.us/getfile.php/3038/nice/Panasonic%20-%20DC-S1%20-%203%3A2.RW2",
            "45309a096d6010281e2fa310e6ec2fe1bcc02947fa37b1a2414cfc7377ce4ac6"),

    /**
     * rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2 (37309952 bytes).
     */
    RW2_S5M2("rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2", "https://raw.pixls.us/getfile.php/7790/nice/Panasonic%20-%20DC-S5M2%20-%2014bit%20%283%3A2%29.RW2",
            "53a171521a7f9af6910b4ab61c5a4a42de0cceb24166e15b07eee1e00675028f"),

    /**
     * rawpixls-sony-dsc-f828-4-3.srf (17393344 bytes).
     */
    SRF_F828("rawpixls-sony-dsc-f828-4-3.srf", "https://raw.pixls.us/getfile.php/1351/nice/Sony%20-%20DSC-F828%20-%204%3A3.SRF",
            "07739f94bcab0c7e198d418ac65172d0dce04bb84a7683ecffd0bfe4b32f6d79"),

    /**
     * rawpixls-minolta-alpha-7-digital-3-2-3919.mrw (9182496 bytes).
     */
    MRW_ALPHA_7D("rawpixls-minolta-alpha-7-digital-3-2-3919.mrw", "https://raw.pixls.us/getfile.php/3919/nice/Minolta%20-%20ALPHA-7%20DIGITAL%20-%203%3A2.MRW",
            "f655f60d24e492cd2b8c46ac8bfd24f83d7696b49cdf2301ffd4f34857216248"),

    /**
     * rawpixls-minolta-alpha-7-digital-3-2-7778.mrw (9215264 bytes).
     */
    MRW_ALPHA_7D_2025("rawpixls-minolta-alpha-7-digital-3-2-7778.mrw", "https://raw.pixls.us/getfile.php/7778/nice/Minolta%20-%20Alpha-7%20Digital%20-%203%3A2.MRW",
            "5c71920de7fa103da6bd3eb56bbe94edcf9802f4c1f56d0cdc75666966440d85"),

    /**
     * rawpixls-minolta-alpha-sweet-digital-3-2.mrw (9182496 bytes).
     */
    MRW_ALPHA_SWEET("rawpixls-minolta-alpha-sweet-digital-3-2.mrw", "https://raw.pixls.us/getfile.php/8070/nice/Minolta%20-%20Alpha%20Sweet%20Digital%20-%203%3A2.MRW",
            "cad52fa1585f374ad7710d353295a7b8e3d9e148ad441d67d492010ef32dc5a5"),

    /**
     * rawpixls-minolta-dimage-5-4-3.mrw (6422144 bytes).
     */
    MRW_DIMAGE_5("rawpixls-minolta-dimage-5-4-3.mrw", "https://raw.pixls.us/getfile.php/7795/nice/Minolta%20-%20DiMAGE%205%20-%204%3A3.MRW",
            "d60bfd80bcb1f7b9c88b14a5e46ea27885bbf92d990ab30b3939288678fea0d9"),

    /**
     * rawpixls-minolta-dimage-a2-4-3.mrw (12150304 bytes).
     */
    MRW_DIMAGE_A2("rawpixls-minolta-dimage-a2-4-3.mrw", "https://raw.pixls.us/getfile.php/4419/nice/Minolta%20-%20DiMAGE%20A2%20-%204%3A3.MRW",
            "8655e3428461377dd7c6b28ae98a0b23d659314997ed16333985278f99c9a4b4"),

    /**
     * rawpixls-minolta-dynax-5d-3-2.mrw (9190688 bytes).
     */
    MRW_DYNAX_5D("rawpixls-minolta-dynax-5d-3-2.mrw", "https://raw.pixls.us/getfile.php/4246/nice/MINOLTA%20-%20DYNAX%205D%20-%203%3A2.MRW",
            "45eee786da72e284054ae81892b7226e8a08cf405b320edaaf767bbf1b2a77c8"),

    /**
     * rawpixls-minolta-dynax-7d-3-2.mrw (9182496 bytes).
     */
    MRW_DYNAX_7D("rawpixls-minolta-dynax-7d-3-2.mrw", "https://raw.pixls.us/getfile.php/1826/nice/Minolta%20-%20Dynax%207D%20-%203%3A2.MRW",
            "713819e33e5302ec864d576d57223cfc8dcf46e8d45e0ae744d1fec40290a89f"),

    /**
     * rawpixls-minolta-maxxum-7d-3-2.mrw (9207072 bytes).
     */
    MRW_MAXXUM_7D("rawpixls-minolta-maxxum-7d-3-2.mrw", "https://raw.pixls.us/getfile.php/3365/nice/Minolta%20-%20Maxxum%207D%20-%203%3A2.MRW",
            "ff57d7c632507e908e2f556fb1632d10c008f9d237d4001e583a2aedbacb585f"),

    /**
     * rawpixls-canon-eos-d30-raw-3-2.crw (2993260 bytes).
     */
    CRW_D30("rawpixls-canon-eos-d30-raw-3-2.crw", "https://raw.pixls.us/getfile.php/1307/nice/Canon%20-%20EOS%20D30%20-%20RAW%20%283%3A2%29.CRW",
            "4ee482f2b6f9f1a8d370703ea04e9f2abaa49356973a7f56b229af93a78e8a2e"),

    /**
     * rawpixls-canon-eos-d60-raw-3-2.crw (8591234 bytes).
     */
    CRW_D60("rawpixls-canon-eos-d60-raw-3-2.crw", "https://raw.pixls.us/getfile.php/2010/nice/Canon%20-%20EOS%20D60%20-%20RAW%20%283%3A2%29.CRW",
            "2b98659ecff170af1dfe44f89b493139e75e995b2979686f6ff0847cfb56b97f"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2.crw (7787330 bytes).
     */
    CRW_10D("rawpixls-canon-eos-10d-raw-3-2.crw", "https://raw.pixls.us/getfile.php/2027/nice/Canon%20-%20EOS%2010D%20-%20RAW%20%283%3A2%29.CRW",
            "b0730eb3a67cc2281a32e0b2ee104199246928d954a68b7e5b72366514c73d02"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2-3164.crw (10822040 bytes).
     */
    CRW_10D_3164("rawpixls-canon-eos-10d-raw-3-2-3164.crw", "https://raw.pixls.us/getfile.php/3164/nice/Canon%20-%20EOS%2010D%20-%20RAW%20%283%3A2%29.crw",
            "e879c94afdc64dabc88b01ce2d02628aa225259303ec71dd61f7fb0322bfea2d"),

    /**
     * rawpixls-canon-eos-10d-raw-3-2-3165.crw (10583702 bytes).
     */
    CRW_10D_3165("rawpixls-canon-eos-10d-raw-3-2-3165.crw", "https://raw.pixls.us/getfile.php/3165/nice/Canon%20-%20EOS%2010D%20-%20RAW%20%283%3A2%29.crw",
            "58144118854657df5cd2185df40fecf6b665fc6754fef0416c2f222b68b025c6"),

    /**
     * rawpixls-canon-eos-300d-raw-3-2.crw (9056348 bytes).
     */
    CRW_300D("rawpixls-canon-eos-300d-raw-3-2.crw", "https://raw.pixls.us/getfile.php/1983/nice/Canon%20-%20EOS%20300D%20-%20RAW%20%283%3A2%29.crw",
            "47da16e1668af5500eea63cf42cf2992d9cb3d32180b8d786f17f1e2a57f39f4"),

    /**
     * rawpixls-canon-eos-300d-raw-3-2-3162.crw (8389842 bytes).
     */
    CRW_300D_3162("rawpixls-canon-eos-300d-raw-3-2-3162.crw", "https://raw.pixls.us/getfile.php/3162/nice/Canon%20-%20EOS%20300D%20-%20RAW%20%283%3A2%29.CRW",
            "9e7f62239fe728046af28d0d4a8a722d35d40146a79794a5bda1775306af4383"),

    /**
     * rawpixls-canon-eos-digital-rebel-raw-3-2.crw (10153786 bytes).
     */
    CRW_DIGITAL_REBEL("rawpixls-canon-eos-digital-rebel-raw-3-2.crw", "https://raw.pixls.us/getfile.php/1308/nice/Canon%20-%20EOS%20Digital%20Rebel%20-%20RAW%20%283%3A2%29.CRW",
            "d6a3131bfac390fde01b96882ec378211f04570c3252708b58f0d05b33a9fdfc"),

    /**
     * rawpixls-canon-powershot-g5-raw-4-3.crw (4894440 bytes).
     */
    CRW_G5("rawpixls-canon-powershot-g5-raw-4-3.crw", "https://raw.pixls.us/getfile.php/1746/nice/Canon%20-%20PowerShot%20G5%20-%20RAW%20%284%3A3%29.CRW",
            "bb084223d714871b99de4f549eaf00513e30a7080feeead700252c2008060196"),

    /**
     * rawpixls-canon-powershot-g6-raw-4-3.crw (8052032 bytes).
     */
    CRW_G6("rawpixls-canon-powershot-g6-raw-4-3.crw", "https://raw.pixls.us/getfile.php/4226/nice/Canon%20-%20PowerShot%20G6%20-%20RAW%20%284%3A3%29.CRW",
            "244378e1f5660b6cc6cefc93a75ed4928d0b28aa6304ff327b61c748538f4fef"),

    /**
     * rawpixls-sigma-dp1-3-2-1116.x3f (10317264 bytes).
     */
    X3F_DP1("rawpixls-sigma-dp1-3-2-1116.x3f", "https://raw.pixls.us/getfile.php/1116/nice/Sigma%20-%20DP1%20-%203%3A2.X3F",
            "44508f7df4191aacaecb6461e1841dae002e5b71a4f95a00aa3f52d1c6d6bc5f"),

    /**
     * rawpixls-sigma-sigma-sd14-3-2-4462.x3f (12180112 bytes).
     */
    X3F_SD14("rawpixls-sigma-sigma-sd14-3-2-4462.x3f", "https://raw.pixls.us/getfile.php/4462/nice/Sigma%20-%20SIGMA%20SD14%20-%203%3A2.X3F",
            "c32f247eb372c3e66ad3b433518ec11f898fdc317c8432ebe025b46d4917bcf3"),

    /**
     * rawpixls-sigma-sigma-sd15-3-2-1863.x3f (16373048 bytes).
     */
    X3F_SD15("rawpixls-sigma-sigma-sd15-3-2-1863.x3f", "https://raw.pixls.us/getfile.php/1863/nice/Sigma%20-%20SIGMA%20SD15%20-%203%3A2.X3F",
            "e9bf8ca29bb9c533b764a4af7830bcf029ee6792323bad3e4179d0e5c29389c0"),

    /**
     * rawpixls-sigma-sigma-sd1-merrill-3-2-4918.x3f (47774136 bytes).
     */
    X3F_SD1_MERRILL("rawpixls-sigma-sigma-sd1-merrill-3-2-4918.x3f", "https://raw.pixls.us/getfile.php/4918/nice/Sigma%20-%20SIGMA%20SD1%20Merrill%20-%203%3A2.X3F",
            "74c0421f53e82b6a37a9ed63c7963f7cf34e49b5a51e70ff0ea7b41f726b2141"),

    /**
     * rawpixls-sigma-sigma-dp2-merrill-3-2-855.x3f (49812540 bytes).
     */
    X3F_DP2_MERRILL("rawpixls-sigma-sigma-dp2-merrill-3-2-855.x3f", "https://raw.pixls.us/getfile.php/855/nice/Sigma%20-%20SIGMA%20DP2%20Merrill%20-%203%3A2.X3F",
            "6236912c716052b9fbf66bb95e217a9aa43ecdc47ec8b397ea19bf50b2a7c040"),

    /**
     * rawpixls-sigma-sigma-dp2-quattro-3-2-5540.x3f (54740948 bytes).
     */
    X3F_DP2_QUATTRO("rawpixls-sigma-sigma-dp2-quattro-3-2-5540.x3f", "https://raw.pixls.us/getfile.php/5540/nice/Sigma%20-%20SIGMA%20dp2%20Quattro%20-%203%3A2.X3F",
            "e89f40df6881bbf60a83e56eee92d04f71b89e9ff55d6ff5d498afd4590cfcb3");

    private final String file;
    private final String url;
    private final String sha256;

    RawSamples(String file, String url, String sha256) {
        this.file = file;
        this.url = url;
        this.sha256 = sha256;
    }

    public Path fetch() {
        return Conformance.fetch(file, url, sha256);
    }

    public String fileName() {
        return file;
    }
}
