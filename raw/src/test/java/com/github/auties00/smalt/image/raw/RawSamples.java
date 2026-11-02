package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.testkit.Conformance;

import java.nio.file.Path;

/**
 * The camera raw sample files of the tests: files of the raw.pixls.us archive (CC0, and a few CC BY-NC-SA ones used
 * for testing only) and one of rawsamples.ch (CC BY-NC-SA), fetched and verified through {@link Conformance} (the
 * test using one is skipped when it is unavailable).
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
     * rawpixls-sony-dslr-a900-12bit-12bit-compressed-3-2.arw (37933568 bytes).
     */
    ARW_A900_UNCOMPRESSED_12("rawpixls-sony-dslr-a900-12bit-12bit-compressed-3-2.arw", "https://raw.pixls.us/getfile.php/5858/nice/Sony%20-%20DSLR-A900%20-%2012bit%2012bit%20compressed%20%283%3A2%29.ARW",
            "ac7c1532df77c321e8010aa1be60c1b9f245b1ac0db6f38fe617a73aae81af50"),

    /**
     * rawpixls-sony-dslr-a200-12bit-12bit-compressed-3-2.arw (11418600 bytes).
     */
    ARW_A200_COMPRESSED_12("rawpixls-sony-dslr-a200-12bit-12bit-compressed-3-2.arw", "https://raw.pixls.us/getfile.php/2271/nice/Sony%20-%20DSLR-A200%20-%2012bit%2012bit%20compressed%20%283%3A2%29.ARW",
            "04eacd403d92e0c90da9d91b87615af8eda1afc963350d3ab774557c36763eef"),

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
     * rawpixls-canon-eos-1d-x-mark-iii-3-2.cr3 (10173528 bytes).
     */
    CR3_EOS_1D_X_MARK_III("rawpixls-canon-eos-1d-x-mark-iii-3-2.cr3", "https://raw.pixls.us/getfile.php/4654/nice/Canon%20-%20EOS-1D%20X%20Mark%20III%20-%203%3A2.CR3",
            "e5755d1414a2dd51fb0120b272058f00b0199861646070c00304d927eb119560"),

    /**
     * rawpixls-canon-eos-200d-mark-ii-3-2.cr3 (39134372 bytes).
     */
    CR3_EOS_200D_MARK_II("rawpixls-canon-eos-200d-mark-ii-3-2.cr3", "https://raw.pixls.us/getfile.php/5869/nice/Canon%20-%20EOS%20200D%20Mark%20II%20-%203%3A2.CR3",
            "4ea0cbd52b760a5654d3f431c7c9ecf8acdc62a6aa74a2cfbfe651693c740c77"),

    /**
     * rawpixls-canon-eos-250d-c-raw-3-2.cr3 (14783176 bytes).
     */
    CR3_EOS_250D_C_RAW("rawpixls-canon-eos-250d-c-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3383/nice/Canon%20-%20EOS%20250D%20-%20C-RAW%20%283%3A2%29.CR3",
            "69bdd267defb3d676f41f62a5e5ea65651669a04191cf4e89740917c59cedbcc"),

    /**
     * rawpixls-canon-eos-850d-3-2.cr3 (27601954 bytes).
     */
    CR3_EOS_850D("rawpixls-canon-eos-850d-3-2.cr3", "https://raw.pixls.us/getfile.php/4867/nice/Canon%20-%20EOS%20850D%20-%203%3A2.CR3",
            "47f7747b74073515bdbd65015738bc31947504d68255c5377baa21f179df2486"),

    /**
     * rawpixls-canon-eos-90d-3-2.cr3 (19512658 bytes).
     */
    CR3_EOS_90D("rawpixls-canon-eos-90d-3-2.cr3", "https://raw.pixls.us/getfile.php/4650/nice/Canon%20-%20EOS%2090D%20-%203%3A2.CR3",
            "c8c918b2d1a53e64d7d5009ebac89a920dbba5f766c2f396bb4ec95312b21034"),

    /**
     * rawpixls-canon-eos-kiss-m-3-2.cr3 (27575430 bytes).
     */
    CR3_EOS_KISS_M("rawpixls-canon-eos-kiss-m-3-2.cr3", "https://raw.pixls.us/getfile.php/3332/nice/Canon%20-%20EOS%20Kiss%20M%20-%203%3A2.CR3",
            "49a4bcd16e0236451b73ae052af8a4d0c9872063f79fc62fdf982d462792741c"),

    /**
     * rawpixls-canon-eos-m200-craw-3-2.cr3 (17606963 bytes).
     */
    CR3_EOS_M200_CRAW("rawpixls-canon-eos-m200-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/8470/nice/Canon%20-%20EOS%20M200%20-%20CRAW%20%283%3A2%29.CR3",
            "0585a468143c6606936d0ffbff1788c6c02e3610d8ecb50614e21492fbacd052"),

    /**
     * rawpixls-canon-eos-m200-raw-3-2.cr3 (28778121 bytes).
     */
    CR3_EOS_M200_RAW("rawpixls-canon-eos-m200-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/8469/nice/Canon%20-%20EOS%20M200%20-%20RAW%20%283%3A2%29.CR3",
            "9b900c04ae37ba827b7894e66907657a0b174a74855c40039a8ca0c95583f141"),

    /**
     * rawpixls-canon-eos-m50-3-2.cr3 (14851567 bytes).
     */
    CR3_EOS_M50("rawpixls-canon-eos-m50-3-2.cr3", "https://raw.pixls.us/getfile.php/4656/nice/Canon%20-%20EOS%20M50%20-%203%3A2.CR3",
            "27a88fdae82516ba136e12c2cc9b934f25dbaaac24d64ab4e5517f8524463594"),

    /**
     * rawpixls-canon-eos-m50-craw-3-2.cr3 (20948692 bytes).
     */
    CR3_EOS_M50_CRAW("rawpixls-canon-eos-m50-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/2663/nice/Canon%20-%20EOS%20M50%20-%20CRAW%20%283%3A2%29.CR3",
            "15384b775867ec4c42b11882837f1e368cedc0561832ffab271221e6bb80be4c"),

    /**
     * rawpixls-canon-eos-m50-mark-ii-3-2.cr3 (34348711 bytes).
     */
    CR3_EOS_M50_MARK_II("rawpixls-canon-eos-m50-mark-ii-3-2.cr3", "https://raw.pixls.us/getfile.php/4430/nice/Canon%20-%20EOS%20M50%20Mark%20II%20-%203%3A2.CR3",
            "1a3c11d85f48b8c644664b505f69fbdd75eb51270bb8ab556dbc660f0487e957"),

    /**
     * rawpixls-canon-eos-m6-mark-ii-c-raw-3-2.cr3 (31581690 bytes).
     */
    CR3_EOS_M6_MARK_II_C_RAW("rawpixls-canon-eos-m6-mark-ii-c-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3714/nice/Canon%20-%20EOS%20M6%20Mark%20II%20-%20C-RAW%20%283%3A2%29.CR3",
            "94635c6f91e7d743cee6881cc2519992ae9f9031fd86b49e5d0f16edf5961b55"),

    /**
     * rawpixls-canon-eos-m6-mark-ii-raw-3-2.cr3 (48407090 bytes).
     */
    CR3_EOS_M6_MARK_II_RAW("rawpixls-canon-eos-m6-mark-ii-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3713/nice/Canon%20-%20EOS%20M6%20Mark%20II%20-%20RAW%20%283%3A2%29.CR3",
            "48be4fbcc36e4f9bf31dd63ba7f97aa906a39b29f6b2caa679506c4cdb20f5a7"),

    /**
     * rawpixls-canon-eos-r-3-2.cr3 (7997663 bytes).
     */
    CR3_EOS_R("rawpixls-canon-eos-r-3-2.cr3", "https://raw.pixls.us/getfile.php/4664/nice/Canon%20-%20EOS%20R%20-%203%3A2.CR3",
            "90bbf397b592f9ce9799477df4affbdf0025578c07eac26f928696b0d40d5121"),

    /**
     * rawpixls-canon-eos-r10-3-2.cr3 (31010650 bytes).
     */
    CR3_EOS_R10("rawpixls-canon-eos-r10-3-2.cr3", "https://raw.pixls.us/getfile.php/5871/nice/Canon%20-%20EOS%20R10%20-%203%3A2.CR3",
            "5d2e076d38e7835d4c4f13dc2030a913cc573a31d7199b60777d23a25ccc3ef9"),

    /**
     * rawpixls-canon-eos-r100-craw-3-2.cr3 (15714631 bytes).
     */
    CR3_EOS_R100_CRAW("rawpixls-canon-eos-r100-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/7897/nice/Canon%20-%20EOS%20R100%20-%20CRAW%20%283%3A2%29.CR3",
            "0b66842b2fe00329ebc05fe8ab9357ddbbe1a0a2d2e69a2893102de7fdf5c942"),

    /**
     * rawpixls-canon-eos-r100-raw-3-2.cr3 (28173253 bytes).
     */
    CR3_EOS_R100_RAW("rawpixls-canon-eos-r100-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/7896/nice/Canon%20-%20EOS%20R100%20-%20RAW%20%283%3A2%29.CR3",
            "6d83217d58a5e6d2dabcf470430e91a16676b60531c025b81fa7453fc74b0521"),

    /**
     * rawpixls-canon-eos-r3-3-2.cr3 (32291078 bytes).
     */
    CR3_EOS_R3("rawpixls-canon-eos-r3-3-2.cr3", "https://raw.pixls.us/getfile.php/5051/nice/Canon%20-%20EOS%20R3%20-%203%3A2.CR3",
            "295d46a4d5383895e63126f67000d4352a5bb425ee4d8b16eb6b6c284bdf7994"),

    /**
     * rawpixls-canon-eos-r5-3-2.cr3 (6975574 bytes).
     */
    CR3_EOS_R5("rawpixls-canon-eos-r5-3-2.cr3", "https://raw.pixls.us/getfile.php/4696/nice/Canon%20-%20EOS%20R5%20-%203%3A2.CR3",
            "a5f4935e0331cc16003c0b1ab6c657272739e82175557aaae576e78a8262511b"),

    /**
     * rawpixls-canon-eos-r5-mark-ii-craw-3-2.cr3 (7564960 bytes).
     */
    CR3_EOS_R5_MARK_II_CRAW("rawpixls-canon-eos-r5-mark-ii-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/7884/nice/Canon%20-%20EOS%20R5%20Mark%20II%20-%20CRAW%20%283%3A2%29.CR3",
            "6a597497776a6abbfab8fcf12d0086ac7a3f8afea2879d61accf6bbf48d357d7"),

    /**
     * rawpixls-canon-eos-r5-mark-ii-raw-3-2.cr3 (20533408 bytes).
     */
    CR3_EOS_R5_MARK_II_RAW("rawpixls-canon-eos-r5-mark-ii-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/7883/nice/Canon%20-%20EOS%20R5%20Mark%20II%20-%20RAW%20%283%3A2%29.CR3",
            "9f88c0ff012924193b7f4497559d133dfe626766d600b5d67689156721739ead"),

    /**
     * rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3 (30435672 bytes).
     */
    CR3_EOS_R5_RAW_1_6_CROP_DUAL_PIXEL("rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3", "https://raw.pixls.us/getfile.php/4691/nice/Canon%20-%20EOS%20R5%20-%20RAW%2C%201.6%20crop%2C%20dual-pixel%20%283%3A2%29.CR3",
            "10a1116bd00e92314b09382b403f76706b1eeab3ce33234a6c75fe25ed289756"),

    /**
     * rawpixls-canon-eos-r50-craw-3-2.cr3 (19117932 bytes).
     */
    CR3_EOS_R50_CRAW("rawpixls-canon-eos-r50-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/6646/nice/Canon%20-%20EOS%20R50%20-%20CRAW%20%283%3A2%29.CR3",
            "b79af9ffa184dd740e8af7a9f451f7150053d7e456b3435ca2e43bbbf5f65026"),

    /**
     * rawpixls-canon-eos-r50-raw-3-2.cr3 (31849836 bytes).
     */
    CR3_EOS_R50_RAW("rawpixls-canon-eos-r50-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/6648/nice/Canon%20-%20EOS%20R50%20-%20RAW%20%283%3A2%29.CR3",
            "cbc0666f27d37007203df53f3668c30d3980aab93c3e33e7bff41c72b77414e9"),

    /**
     * rawpixls-canon-eos-r50-v-raw-3-2.cr3 (29026726 bytes).
     */
    CR3_EOS_R50_V_RAW("rawpixls-canon-eos-r50-v-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/8403/nice/Canon%20-%20EOS%20R50%20V%20-%20RAW%20%283%3A2%29.CR3",
            "955ff98710505498ce7f98cbcc5875793ef2d6941b3b5692ed3baf2817ee7c9f"),

    /**
     * rawpixls-canon-eos-r6-3-2.cr3 (5273174 bytes).
     */
    CR3_EOS_R6("rawpixls-canon-eos-r6-3-2.cr3", "https://raw.pixls.us/getfile.php/4659/nice/Canon%20-%20EOS%20R6%20-%203%3A2.CR3",
            "74abb0a113d075ad9887a058082f40dd2a938c4813a08474d82356f11a027778"),

    /**
     * rawpixls-canon-eos-r6-mark-ii-3-2.cr3 (7919762 bytes).
     */
    CR3_EOS_R6_MARK_II("rawpixls-canon-eos-r6-mark-ii-3-2.cr3", "https://raw.pixls.us/getfile.php/6408/nice/Canon%20-%20EOS%20R6%20Mark%20II%20-%203%3A2.cr3",
            "51ae1e673d2801d8a54f33dd8669c43034c506e2d355e75b26c0648e2f14a431"),

    /**
     * rawpixls-canon-eos-r6-mark-iii-craw-3-2.cr3 (7065300 bytes).
     */
    CR3_EOS_R6_MARK_III_CRAW("rawpixls-canon-eos-r6-mark-iii-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/8964/nice/Canon%20-%20EOS%20R6%20Mark%20III%20-%20CRAW%20%283%3A2%29.CR3",
            "7e1a5d84297a52853b0bbe5a5c03a5d4f5b93e00b7020271523240f9cabfe50e"),

    /**
     * rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3 (14758612 bytes).
     */
    CR3_EOS_R6_MARK_III_RAW("rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/8963/nice/Canon%20-%20EOS%20R6%20Mark%20III%20-%20RAW%20%283%3A2%29.CR3",
            "1f533686b2230817dac98266a6cc8ffd960413c109965768b7efc05cc1ca96eb"),

    /**
     * rawpixls-canon-eos-r7-3-2.cr3 (13509466 bytes).
     */
    CR3_EOS_R7("rawpixls-canon-eos-r7-3-2.cr3", "https://raw.pixls.us/getfile.php/5634/nice/Canon%20-%20EOS%20R7%20-%203%3A2.CR3",
            "10a18e3f01ca9cd93408496d3853388ef140830548bfeed4237e40f967eb9d5c"),

    /**
     * rawpixls-canon-eos-r8-3-2.cr3 (14606994 bytes).
     */
    CR3_EOS_R8("rawpixls-canon-eos-r8-3-2.cr3", "https://raw.pixls.us/getfile.php/6587/nice/Canon%20-%20EOS%20R8%20-%203%3A2.CR3",
            "df33cf394573645ce03dea1b2e9f0b5cc2b7734e9e3391ee7114151414cf2812"),

    /**
     * rawpixls-canon-eos-rebel-sl3-3-2.cr3 (15640012 bytes).
     */
    CR3_EOS_REBEL_SL3("rawpixls-canon-eos-rebel-sl3-3-2.cr3", "https://raw.pixls.us/getfile.php/3738/nice/Canon%20-%20EOS%20Rebel%20SL3%20-%203%3A2.CR3",
            "6f26a2e4ea5a3e3b859662c9b01da6b24731413835aa601065bff1d7f30c6092"),

    /**
     * rawpixls-canon-eos-rp-3-2.cr3 (7395184 bytes).
     */
    CR3_EOS_RP("rawpixls-canon-eos-rp-3-2.cr3", "https://raw.pixls.us/getfile.php/4671/nice/Canon%20-%20EOS%20RP%20-%203%3A2.CR3",
            "4a859ab137f792aedaf3416b0aad1497ebaab02052a49453902e226699154aa7"),

    /**
     * rawpixls-canon-powershot-g5-x-mark-ii-c-raw-1-1.cr3 (16898925 bytes).
     */
    CR3_POWERSHOT_G5_X_MARK_II_C_RAW_1_1("rawpixls-canon-powershot-g5-x-mark-ii-c-raw-1-1.cr3", "https://raw.pixls.us/getfile.php/3505/nice/Canon%20-%20PowerShot%20G5%20X%20Mark%20II%20-%20C-RAW%20%281%3A1%29.CR3",
            "7d1160f4c7159dd30daae7a99a575d0c37dc11493aa6c796e1ff9e504c2bdd8f"),

    /**
     * rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3 (16665658 bytes).
     */
    CR3_POWERSHOT_G5_X_MARK_II_C_RAW("rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3506/nice/Canon%20-%20PowerShot%20G5%20X%20Mark%20II%20-%20C-RAW%20%283%3A2%29.CR3",
            "16d7b944344004c866bf2f84c5673969e76adfe16e5fb4388da48c72bdd58058"),

    /**
     * rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3 (28097932 bytes).
     */
    CR3_POWERSHOT_G5_X_MARK_II_RAW("rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3", "https://raw.pixls.us/getfile.php/3510/nice/Canon%20-%20PowerShot%20G5%20X%20Mark%20II%20-%20RAW%20%283%3A2%29.CR3",
            "16f69a73e25e0555259a942d65be816042dcb3af03505b25335e5d02ad6f2af6"),

    /**
     * rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3 (21365790 bytes).
     */
    CR3_POWERSHOT_G7_X_MARK_III("rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3", "https://raw.pixls.us/getfile.php/6054/nice/Canon%20-%20PowerShot%20G7%20X%20Mark%20III%20-%203%3A2.CR3",
            "29fa26718f2791f8dc3a69d8517d0575f8cf165492253671e7b1a5151a512f02"),

    /**
     * rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3 (20852807 bytes).
     */
    CR3_POWERSHOT_SX70_HS_C_RAW("rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3", "https://raw.pixls.us/getfile.php/3281/nice/Canon%20-%20PowerShot%20SX70%20HS%20-%20C-RAW%2C%203%3A2%20%283%3A2%29.CR3",
            "5c37f0d865c79e22156f684281773dc4bf550f8ae1bfce2acc23cacb07a7893c"),

    /**
     * rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3 (29330229 bytes).
     */
    CR3_POWERSHOT_SX70_HS_RAW("rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3", "https://raw.pixls.us/getfile.php/3277/nice/Canon%20-%20PowerShot%20SX70%20HS%20-%20RAW%2C%203%3A2%20%283%3A2%29.CR3",
            "26d5f6deebb65e5a544d7d1b7706dca2bb8b06642065aa725c1394054e9b717d"),

    /**
     * rawpixls-canon-powershot-v1-craw-3-2.cr3 (11423648 bytes).
     */
    CR3_POWERSHOT_V1_CRAW("rawpixls-canon-powershot-v1-craw-3-2.cr3", "https://raw.pixls.us/getfile.php/8500/nice/Canon%20-%20PowerShot%20V1%20-%20CRAW%20%283%3A2%29.CR3",
            "0a0abfd81b3a34fa513ac8ed24c809275160d65b07e892c2eb4353b77c535a31"),

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
     * rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf (27949104 bytes).
     */
    RAF_XT4_LOSSLESS("rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/3914/nice/Fujifilm%20-%20X-T4%20-%2014bit%2014bit%20lossless%20compressed%20%283%3A2%29.RAF",
            "07ea642656074834ffe5c661870a612ffa2e80fc1218a05166ee966bbe5f6fae"),

    /**
     * rawpixls-fujifilm-x-e5-14bit-lossless-compressed-3-2.raf (40819024 bytes).
     */
    RAF_XE5_LOSSLESS("rawpixls-fujifilm-x-e5-14bit-lossless-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/8508/nice/Fujifilm%20-%20X-E5%20-%2014bit%20lossless%20compressed%20%283%3A2%29.RAF",
            "ff94e2e0d90ba29cacfacceab150a666e329945bd9e281cfced9564d06fc4070"),

    /**
     * rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf (56710368 bytes).
     */
    RAF_GFX50SII_LOSSLESS("rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf", "https://raw.pixls.us/getfile.php/5003/nice/Fujifilm%20-%20GFX50S%20II%20-%2014bit%2014bit%20compressed%20lossless%20%284%3A3%29.RAF",
            "4aada4653d7f876ee5e7b1cff36671d7820600adb98af39e8fb7c41ae0ace96d"),

    /**
     * rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf (50106976 bytes).
     */
    RAF_GFX50S_COMPRESSED("rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/1435/nice/Fujifilm%20-%20GFX%2050S%20-%2014bit%2014bit%20compressed%20%283%3A2%29.RAF",
            "a7124d13910e0241a6b035154bc1fcdb7be4439371d043b1e1950a0251feb51e"),

    /**
     * rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf (135619152 bytes).
     */
    RAF_GFX100S_16BIT_LOSSLESS("rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf", "https://raw.pixls.us/getfile.php/4495/nice/Fujifilm%20-%20GFX100S%20-%2016bit%2016bit%20lossless%20compressed%20%284%3A3%29.RAF",
            "a7bf8f061b603f7ef5fd29276eb6b2ac57355ca2f589a340117bfff0d03c065c"),

    /**
     * rawpixls-fujifilm-x-e5-14bit-lossy-compressed-3-2.raf (29752800 bytes).
     */
    RAF_XE5_LOSSY("rawpixls-fujifilm-x-e5-14bit-lossy-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/8509/nice/Fujifilm%20-%20X-E5%20-%2014bit%20lossy%20compressed%20%283%3A2%29.RAF",
            "28f56cc9784164209e92fae209bdcc78267bd692aa2fb964595485f7e93692ab"),

    /**
     * rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf (34522544 bytes).
     */
    RAF_GFX50SII_LOSSY("rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf", "https://raw.pixls.us/getfile.php/5004/nice/Fujifilm%20-%20GFX50S%20II%20-%2014bit%2014bit%20compressed%20%284%3A3%29.RAF",
            "59699d3063a9900eb5f226da664473c6081ed2cfe4d4529b7cd45afa2f1cac62"),

    /**
     * rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf (26971008 bytes).
     */
    RAF_XT4_LOSSY("rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf", "https://raw.pixls.us/getfile.php/3918/nice/Fujifilm%20-%20X-T4%20-%2014bit%2014bit%20compressed%20%283%3A2%29.RAF",
            "871836938ffa695f4c41765ddb107c2703ce755f7f3ced7e10f9e04746f1734e"),

    /**
     * rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf (70874736 bytes).
     */
    RAF_GFX100II_16BIT_LOSSY("rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf", "https://raw.pixls.us/getfile.php/7175/nice/Fujifilm%20-%20GFX100%20II%20-%2016bit%2016bit%20compressed%20%284%3A3%29.RAF",
            "fed86662d870e53b547db4f29af5e2d149acdcb500f8b4213854a0f85ef0b45b"),

    /**
     * rawpixls-fujifilm-finepix-s9500-4-3.raf (19163072 bytes).
     */
    RAF_S9500("rawpixls-fujifilm-finepix-s9500-4-3.raf", "https://raw.pixls.us/getfile.php/1373/nice/Fujifilm%20-%20FinePix%20S9500%20-%204%3A3.RAF",
            "b7a7bb3b8a69232866c75774ca6ba9296f3d4435d1e1bac327b4cdc6bea39144"),

    /**
     * rawpixls-fujifilm-finepix-s9600-4-3.raf (19118528 bytes).
     */
    RAF_S9600("rawpixls-fujifilm-finepix-s9600-4-3.raf", "https://raw.pixls.us/getfile.php/1642/nice/Fujifilm%20-%20FinePix%20S9600%20-%204%3A3.raf",
            "d4888ec28d89ce7f9cc6d1b852d2ea03da965bf5e8ce5874d1e3fa439f92dbc0"),

    /**
     * rawpixls-fujifilm-finepix-s5500-4-3.raf (8498640 bytes).
     */
    RAF_S5500("rawpixls-fujifilm-finepix-s5500-4-3.raf", "https://raw.pixls.us/getfile.php/1769/nice/Fujifilm%20-%20FinePix%20S5500%20-%204%3A3.RAF",
            "92a947758829686f0a0c662d160a7b8319099cc405a0e4bf90e1fd61c47b2b69"),

    /**
     * rawpixls-fujifilm-finepix-s3pro-3-2.raf (25760952 bytes).
     */
    RAF_S3PRO("rawpixls-fujifilm-finepix-s3pro-3-2.raf", "https://raw.pixls.us/getfile.php/1964/nice/Fujifilm%20-%20FinePix%20S3Pro%20-%203%3A2.RAF",
            "038e5a13a22513f53d143e328a66e2de8071809565db696ecac84b4364fcb75b"),

    /**
     * rawpixls-fujifilm-finepix-s5200-4-3.raf (11230736 bytes).
     */
    RAF_S5200("rawpixls-fujifilm-finepix-s5200-4-3.raf", "https://raw.pixls.us/getfile.php/1970/nice/Fujifilm%20-%20FinePix%20S5200%20-%204%3A3.RAF",
            "93eada72efdf8261d9bfde5022a28a656e4c1c6d0ddac7b0c82686fdcb8ded99"),

    /**
     * rawpixls-fujifilm-finepix-s5pro-3-2.raf (25616736 bytes).
     */
    RAF_S5PRO("rawpixls-fujifilm-finepix-s5pro-3-2.raf", "https://raw.pixls.us/getfile.php/2634/nice/Fujifilm%20-%20FinePix%20S5Pro%20-%203%3A2.RAF",
            "aca2b2ebcf90d248b4f822ed78bfeb73e79f1b64164a87dc2051a96726cdfa0c"),

    /**
     * rawpixls-fujifilm-finepix-s5000-4-3.raf (6851240 bytes).
     */
    RAF_S5000("rawpixls-fujifilm-finepix-s5000-4-3.raf", "https://raw.pixls.us/getfile.php/2726/nice/Fujifilm%20-%20FinePix%20S5000%20-%204%3A3.RAF",
            "dabd5e74521a6980156be9fd4b88d0c37b0fe4d0e0e6f5c12db8cffff1b76297"),

    /**
     * rawpixls-fujifilm-finepix-s200exr-4-3.raf (25326928 bytes).
     */
    RAF_S200EXR("rawpixls-fujifilm-finepix-s200exr-4-3.raf", "https://raw.pixls.us/getfile.php/3425/nice/Fujifilm%20-%20FinePix%20S200EXR%20-%204%3A3.RAF",
            "2d4549067f92b33a222d7ded9460adb5ff6ce739ed92af9890c168e37170d4ff"),

    /**
     * rawpixls-fujifilm-finepix-s7000-4-3.raf (13096536 bytes).
     */
    RAF_S7000("rawpixls-fujifilm-finepix-s7000-4-3.raf", "https://raw.pixls.us/getfile.php/3495/nice/Fujifilm%20-%20FinePix%20S7000%20-%204%3A3.raf",
            "098aa2c64dc6a026f3f48f1494476a55751e08af2d37789b7f557fd11a14cf49"),

    /**
     * rawpixls-fujifilm-finepix-s100fs-4-3.raf (23714912 bytes).
     */
    RAF_S100FS("rawpixls-fujifilm-finepix-s100fs-4-3.raf", "https://raw.pixls.us/getfile.php/4068/nice/Fujifilm%20-%20FinePix%20S100FS%20-%204%3A3.RAF",
            "efa518d70b8122dcfe65f98c7c080f15a512d841a2a86bfe226032a327e1d25e"),

    /**
     * rawpixls-fujifilm-finepix-s6000fd-4-3.raf (13660864 bytes).
     */
    RAF_S6000FD("rawpixls-fujifilm-finepix-s6000fd-4-3.raf", "https://raw.pixls.us/getfile.php/5549/nice/Fujifilm%20-%20FinePix%20S6000fd%20-%204%3A3.RAF",
            "2c602e5a31b81683d8fb97f36c5cb3580836420e12b9ed5ae16241c68ff6201d"),

    /**
     * rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf (25320656 bytes).
     */
    RAF_F550EXR("rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/3869/nice/Fujifilm%20-%20FinePix%20F550EXR%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "95b6c8a9d5d885f60bbc965ddfd899d325f2225c99ab087d02739543a3ff7dd6"),

    /**
     * rawpixls-fujifilm-finepix-f770exr-12bit-12bit-uncompressed-4-3.raf (25447984 bytes).
     */
    RAF_F770EXR("rawpixls-fujifilm-finepix-f770exr-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/3039/nice/Fujifilm%20-%20FinePix%20F770EXR%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "ffc905e325a00c57a5335681654fdf226b724269cd5d26cd976983939f88043d"),

    /**
     * rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf (19768592 bytes).
     */
    RAF_X10("rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/1344/nice/Fujifilm%20-%20FinePix%20X10%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "206448688c49b0a9da74dd94f12aba143c2d8842cb5bbf64d4ecdc501874451b"),

    /**
     * rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf (24789424 bytes).
     */
    RAF_S1("rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/1651/nice/Fujifilm%20-%20FinePix%20S1%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "d77f104a9fa4ce11c78b5078ac40acf8b150d67ea837d187e0903d208bf2dbae"),

    /**
     * rawpixls-fujifilm-finepix-sl1000-12bit-12bit-uncompressed-4-3.raf (24816408 bytes).
     */
    RAF_SL1000("rawpixls-fujifilm-finepix-sl1000-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/5760/nice/Fujifilm%20-%20FinePix%20SL1000%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "8bc9903cb1bff93376eb73b64f35861dcf3c66e2ca936cfac2d7c62435689dec"),

    /**
     * rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf (13252480 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_F700("rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf", "https://raw.pixls.us/getfile.php/607/nice/Fujifilm%20-%20FinePix%20F700%20-%20double%20width%2016-bit%20unpacked%20little-endian%20%284%3A3%29.RAF",
            "c3686a66f9159043bd27afb0d749d566c2912a0374319e38f68fa6255ea565b4"),

    /**
     * rawpixls-fujifilm-finepix-e550-4-3.raf (13285512 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_E550("rawpixls-fujifilm-finepix-e550-4-3.raf", "https://raw.pixls.us/getfile.php/665/nice/Fujifilm%20-%20FinePix%20E550%20-%204%3A3.RAF",
            "82d1010a1eb8cdf4b2de7ca30a3a4db4e96f290536f06ed2798a67c1fe81e6ae"),

    /**
     * rawpixls-fujifilm-finepix-e900-4-3.raf (19161008 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_E900("rawpixls-fujifilm-finepix-e900-4-3.raf", "https://raw.pixls.us/getfile.php/699/nice/Fujifilm%20-%20FinePix%20E900%20-%204%3A3.RAF",
            "07adee4a49ae922c89786c96c2f86ce3c130fa128e18b25d0e498fa881d51b94"),

    /**
     * rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf (25439728 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_F600EXR("rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/687/nice/Fujifilm%20-%20FinePix%20F600EXR%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "229295351ad9777b2c6a25c7ab46368626b4f7a63b8ec8a3657a624446f2d08a"),

    /**
     * rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf (25753088 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_F900EXR("rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/618/nice/Fujifilm%20-%20FinePix%20F900EXR%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "48e13e0d4b0ced917c0655d430efd8191d7f8bd6856ca55ee7b30bc766ed601d"),

    /**
     * rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf (25397168 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_HS20EXR("rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/592/nice/Fujifilm%20-%20FinePix%20HS20EXR%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "860ca30714a7dd71cc936fefa034d036de33f4a9b69f53a782ffbad06a5fe442"),

    /**
     * rawpixls-fujifilm-finepix-hs10-hs11-12bit-12bit-uncompressed-4-3.raf (15915200 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_HS10("rawpixls-fujifilm-finepix-hs10-hs11-12bit-12bit-uncompressed-4-3.raf", "https://raw.pixls.us/getfile.php/1652/nice/Fujifilm%20-%20FinePix%20HS10%20HS11%20-%2012bit%2012bit%20uncompressed%20%284%3A3%29.RAF",
            "dbdd459e33beadd68d26d298424056402c907bf11d37a904572d3df76c35fbcb"),

    /**
     * rawpixls-fujifilm-finepix-s5600-4-3.raf (11214560 bytes; CC BY-NC-SA 4.0, used for testing only).
     */
    RAF_S5600("rawpixls-fujifilm-finepix-s5600-4-3.raf", "https://raw.pixls.us/getfile.php/629/nice/Fujifilm%20-%20FinePix%20S5600%20-%204%3A3.RAF",
            "8d24ac05af61c8af0a2e7e97166e0db142b5cfb126655462704bf3de03d30d23"),

    /**
     * rawsamples-fujifilm-finepix-s200exr-hr.raf (25344384 bytes; CC BY-NC-SA 4.0, used for testing only): an
     * S200EXR file in EXR HR mode, from rawsamples.ch.
     */
    RAF_S200EXR_HR("rawsamples-fujifilm-finepix-s200exr-hr.raf", "https://www.rawsamples.ch/raws/fuji/RAW_FUJI_FINEPIX_S200EXR.RAF",
            "3730062a3658cbce112a22da2b4595cba818c89e1cfbcbe203a45cccda15e119"),

    /**
     * rawsamples-fujifilm-x10-sn.raf (19830388 bytes; CC BY-NC-SA 4.0, used for testing only): an X10 file in EXR
     * SN mode, from rawsamples.ch.
     */
    RAF_X10_SN("rawsamples-fujifilm-x10-sn.raf", "https://www.rawsamples.ch/raws/fuji/RAW_FUJI_X10.RAF",
            "3296bdcaeb3fb20a2409e7bd0ece3e0493704a2fc22d1f12db8555b69974a1b9"),

    /**
     * rawpixls-fujifilm-dbp-for-gx680-4-3.raf (42714152 bytes).
     */
    RAF_DBP_GX680("rawpixls-fujifilm-dbp-for-gx680-4-3.raf", "https://raw.pixls.us/getfile.php/2574/nice/Fujifilm%20-%20DBP%20for%20GX680%20-%204%3A3.RAF",
            "36e5e144e8d69a98455851578408f6682c881475e39bded4ec72b772b4cff696"),

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
            "e89f40df6881bbf60a83e56eee92d04f71b89e9ff55d6ff5d498afd4590cfcb3"),

    /**
     * rawpixls-samsung-nx1-14bit-3-2.srw (36896768 bytes).
     */
    SRW_NX1("rawpixls-samsung-nx1-14bit-3-2.srw", "https://raw.pixls.us/getfile.php/837/nice/Samsung%20-%20NX1%20-%2014bit%20%283%3A2%29.srw",
            "ec57f83c23094766b723ba9446cdf79388c527934d6689c1ac5b1b6ab3ea8378"),

    /**
     * rawpixls-samsung-nx200-12bit-3-2.srw (34541344 bytes).
     */
    SRW_NX200("rawpixls-samsung-nx200-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/839/nice/Samsung%20-%20NX200%20-%2012bit%20%283%3A2%29.srw",
            "03563eadfe4ef32b5bf2a2850abb40b29fcc1f8d56b3b2ed92ef307b90d7d9dc"),

    /**
     * rawpixls-samsung-nx30-12bit-3-2.srw (31184954 bytes).
     */
    SRW_NX30("rawpixls-samsung-nx30-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/881/nice/Samsung%20-%20NX30%20-%2012bit%20%283%3A2%29.srw",
            "691438acdecd54ab96d326b96f83daa02fa68f7d66a38ff2fee4e8dd00c79d24"),

    /**
     * rawpixls-samsung-nx3000-12bit-3-2.srw (21530327 bytes).
     */
    SRW_NX3000("rawpixls-samsung-nx3000-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/1190/nice/Samsung%20-%20NX3000%20-%2012bit%20%283%3A2%29.SRW",
            "b517dbe12d6d25a9b89116663892d0461e11f3160633e707ddc49c94e5469385"),

    /**
     * rawpixls-samsung-ex1-14bit-4-3.srw (21699313 bytes).
     */
    SRW_EX1("rawpixls-samsung-ex1-14bit-4-3.srw", "https://raw.pixls.us/getfile.php/1204/nice/Samsung%20-%20EX1%20-%2014bit%20%284%3A3%29.SRW",
            "a084890fd9d7995ceb746c18ecf5be48c95baf14286d5c0c480cc3daa25f3e37"),

    /**
     * rawpixls-samsung-nx210-12bit-3-2.srw (34221984 bytes).
     */
    SRW_NX210("rawpixls-samsung-nx210-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/1831/nice/Samsung%20-%20NX210%20-%2012bit%20%283%3A2%29.SRW",
            "d613503ddf9bedd2d2e84708749b1a94f0cccd4fa15492e01242368c5c07cfad"),

    /**
     * rawpixls-samsung-nx300-12bit-3-2.srw (22835672 bytes).
     */
    SRW_NX300("rawpixls-samsung-nx300-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/1832/nice/Samsung%20-%20NX300%20-%2012bit%20%283%3A2%29.SRW",
            "2087d4b351134e307c1c5e7a4fbf5691998a4c7b20451ba46f8f577d87b38052"),

    /**
     * rawpixls-samsung-nx1000-12bit-3-2.srw (37588192 bytes).
     */
    SRW_NX1000("rawpixls-samsung-nx1000-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/2588/nice/Samsung%20-%20NX1000%20-%2012bit%20%283%3A2%29.SRW",
            "beef4d1e6f25ff28fb20bb3ad00ffadb62ab57d7041237e295b0b62f7de5309c"),

    /**
     * rawpixls-samsung-nx500-12bit-12bit-normal-compression-3-2.srw (20750652 bytes).
     */
    SRW_NX500_12_NORMAL("rawpixls-samsung-nx500-12bit-12bit-normal-compression-3-2.srw", "https://raw.pixls.us/getfile.php/3668/nice/Samsung%20-%20NX500%20-%2012bit%2012bit%20normal%20compression%20%283%3A2%29.SRW",
            "156e43118811bcecb6fcc600b5f41ee1773c4f00b6a5b2dd72e177e35f6596ab"),

    /**
     * rawpixls-samsung-nx500-12bit-12bit-lossless-compression-3-2.srw (26249903 bytes).
     */
    SRW_NX500_12_LOSSLESS("rawpixls-samsung-nx500-12bit-12bit-lossless-compression-3-2.srw", "https://raw.pixls.us/getfile.php/3669/nice/Samsung%20-%20NX500%20-%2012bit%2012bit%20lossless%20compression%20%283%3A2%29.SRW",
            "77ed2a18ca6923a0c1f3a9ad41ade3676136a3272dfe6bf0d8f53d03b0c8db3d"),

    /**
     * rawpixls-samsung-nx500-14bit-14bit-normal-compression-3-2.srw (23951257 bytes).
     */
    SRW_NX500_14_NORMAL("rawpixls-samsung-nx500-14bit-14bit-normal-compression-3-2.srw", "https://raw.pixls.us/getfile.php/3670/nice/Samsung%20-%20NX500%20-%2014bit%2014bit%20normal%20compression%20%283%3A2%29.SRW",
            "3a1aeeb631b15dfa5be1bebe0e9c96f660ec96021eb6c45c073e33e982ab61b7"),

    /**
     * rawpixls-samsung-nx500-14bit-14bit-lossless-compression-3-2.srw (32831534 bytes).
     */
    SRW_NX500_14_LOSSLESS("rawpixls-samsung-nx500-14bit-14bit-lossless-compression-3-2.srw", "https://raw.pixls.us/getfile.php/3671/nice/Samsung%20-%20NX500%20-%2014bit%2014bit%20lossless%20compression%20%283%3A2%29.SRW",
            "8a3fac531057ab416fc92d4e09560840c7700388a4b566b685df178cd5be8213"),

    /**
     * rawpixls-samsung-nx-mini-12bit-3-2.srw (21052805 bytes).
     */
    SRW_NX_MINI("rawpixls-samsung-nx-mini-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/3765/nice/Samsung%20-%20NX%20mini%20-%2012bit%20%283%3A2%29.SRW",
            "8e1bd19fff8c3b458f63bd6cf0ceda97e708efaf576424cf6c8ebf58eb0c3fc3"),

    /**
     * rawpixls-samsung-wb2000-14bit-4-3.srw (25267103 bytes).
     */
    SRW_WB2000("rawpixls-samsung-wb2000-14bit-4-3.srw", "https://raw.pixls.us/getfile.php/4262/nice/Samsung%20-%20WB2000%20-%2014bit%20%284%3A3%29.SRW",
            "b9a1149fb0dda9d23aee0ad8c402bf2f9ba42832366d46c4c1b739c0b0f48497"),

    /**
     * rawpixls-samsung-nx1100-12bit-3-2-4422.srw (35266464 bytes).
     */
    SRW_NX1100_4422("rawpixls-samsung-nx1100-12bit-3-2-4422.srw", "https://raw.pixls.us/getfile.php/4422/nice/Samsung%20-%20NX1100%20-%2012bit%20%283%3A2%29.srw",
            "d230af65ee55314792c5337ea8e0e757626a8541e7a25ed03d63b8df202ed9cb"),

    /**
     * rawpixls-samsung-nx1100-12bit-3-2-4423.srw (34815904 bytes).
     */
    SRW_NX1100_4423("rawpixls-samsung-nx1100-12bit-3-2-4423.srw", "https://raw.pixls.us/getfile.php/4423/nice/Samsung%20-%20NX1100%20-%2012bit%20%283%3A2%29.srw",
            "56670e323d3457d6d5dbd1633517a022dcd68b26a8f9762475de5d325e5d865c"),

    /**
     * rawpixls-samsung-nx1100-12bit-3-2-4424.srw (34259360 bytes).
     */
    SRW_NX1100_4424("rawpixls-samsung-nx1100-12bit-3-2-4424.srw", "https://raw.pixls.us/getfile.php/4424/nice/Samsung%20-%20NX1100%20-%2012bit%20%283%3A2%29.srw",
            "1c85f21c0c786f02f1aa9c4d9bf09ba5f50cc60b81ed0ce7a661d8d43fd19a33"),

    /**
     * rawpixls-samsung-nx2000-12bit-3-2.srw (25748909 bytes).
     */
    SRW_NX2000("rawpixls-samsung-nx2000-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/5365/nice/Samsung%20-%20NX2000%20-%2012bit%20%283%3A2%29.SRW",
            "79ec05b29fbe86f978eac0e82ad76e026b8480e99772044142464505c32f2cd0"),

    /**
     * rawpixls-samsung-nx20-12bit-3-2.srw (38192032 bytes).
     */
    SRW_NX20("rawpixls-samsung-nx20-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/5370/nice/Samsung%20-%20NX20%20-%2012bit%20%283%3A2%29.SRW",
            "8a367db506b8f7a6661a6f33a730467cfb3bfd7e46020a9f0df702330a7b45bb"),

    /**
     * rawpixls-samsung-nx5-12bit-3-2.srw (25463376 bytes).
     */
    SRW_NX5("rawpixls-samsung-nx5-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/5684/nice/Samsung%20-%20NX5%20-%2012bit%20%283%3A2%29.SRW",
            "908fc61d5bbc1f0d0a5cea4f4e39e7753060c1af8673f7a4a9199284b70930f7"),

    /**
     * rawpixls-samsung-nx10-12bit-3-2.srw (28564528 bytes).
     */
    SRW_NX10("rawpixls-samsung-nx10-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/5913/nice/Samsung%20-%20NX10%20-%2012bit%20%283%3A2%29.SRW",
            "dead7b4ad83b739ff7b8a1218882589f66cb14efc09f79eeba7a1dcbf7abd29f"),

    /**
     * rawpixls-samsung-nx11-12bit-3-2.srw (28874496 bytes).
     */
    SRW_NX11("rawpixls-samsung-nx11-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/5985/nice/Samsung%20-%20NX11%20-%2012bit%20%283%3A2%29.SRW",
            "bdce6179b4180478057f4971b512e0303da71528db454cb3f399ab10e05b68fe"),

    /**
     * rawpixls-samsung-nx3300-12bit-3-2.srw (21900287 bytes).
     */
    SRW_NX3300("rawpixls-samsung-nx3300-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/6854/nice/Samsung%20-%20NX3300%20-%2012bit%20%283%3A2%29.SRW",
            "c56a6f57caa53c99df6c59943670d2fe422d2cb543d9d21cff95ec0ff2f70381"),

    /**
     * rawpixls-samsung-ek-gn120-12bit-3-2.srw (21273740 bytes).
     */
    SRW_EK_GN120("rawpixls-samsung-ek-gn120-12bit-3-2.srw", "https://raw.pixls.us/getfile.php/6880/nice/Samsung%20-%20EK-GN120%20-%2012bit%20%283%3A2%29.srw",
            "21f9dc02676e0d2e5e5b9b5b090852ca00e1423c3a7d7395214472e6d6ea9cea"),

    /**
     * rawpixls-epson-r-d1-12bit-3-2.erf (9958994 bytes).
     */
    ERF_R_D1("rawpixls-epson-r-d1-12bit-3-2.erf", "https://raw.pixls.us/getfile.php/2680/nice/Epson%20-%20R-D1%20-%2012bit%20%283%3A2%29.ERF",
            "09d8e533d93116294a9f3e161ed868e929454c7b946e083b644bd6bb75bcb5e4"),

    /**
     * rawpixls-epson-r-d1s-12bit-3-2.erf (10092985 bytes).
     */
    ERF_R_D1S("rawpixls-epson-r-d1s-12bit-3-2.erf", "https://raw.pixls.us/getfile.php/2834/nice/Epson%20-%20R-D1s%20-%2012bit%20%283%3A2%29.ERF",
            "45bb0081baf1c813c7cd9e05b3e6ae734b90aba408dd1195118dc94dc6abbbb9"),

    /**
     * rawpixls-epson-r-d1x-12bit-3-2.erf (9986661 bytes).
     */
    ERF_R_D1X("rawpixls-epson-r-d1x-12bit-3-2.erf", "https://raw.pixls.us/getfile.php/3158/nice/Epson%20-%20R-D1x%20-%2012bit%20%283%3A2%29.ERF",
            "fa1bff3e0b9b9a14f83b8db9fae022c2aca8d455896bf4f25f2c1a0034e295e6"),

    /**
     * rawpixls-hasselblad-h3d-16bit-4-3.3fr (53594112 bytes).
     */
    FFF_H3D_16BIT_4_3("rawpixls-hasselblad-h3d-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/2851/nice/Hasselblad%20-%20H3D%20-%2016bit%20%284%3A3%29.3FR",
            "34fdb93cb215e4f419ae0f7252c57b881fa381003124b4f9c143d27741f211b3"),

    /**
     * rawpixls-hasselblad-h4d-40-16bit-4-3.fff (73144108 bytes).
     */
    FFF_H4D_40_16BIT_4_3("rawpixls-hasselblad-h4d-40-16bit-4-3.fff", "https://raw.pixls.us/getfile.php/931/nice/Hasselblad%20-%20H4D-40%20-%2016bit%20%284%3A3%29.fff",
            "1bfe64fa6fc07453ae74ed50c2f817d77a12a310b34a7f9893d32dd03f8c2ad0"),

    /**
     * rawpixls-hasselblad-x1d-16bit-4-3.3fr (110379008 bytes).
     */
    FFF_X1D_16BIT_4_3("rawpixls-hasselblad-x1d-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/2058/nice/Hasselblad%20-%20X1D%20-%2016bit%20%284%3A3%29.3FR",
            "395acb2c706dc10270c58ec261e42a5e36f6ac8c39d2a767e86e83648aa318a3"),

    /**
     * rawpixls-hasselblad-hasselblad-h4d-16bit-4-3.3fr (81199104 bytes).
     */
    FFF_HASSELBLAD_H4D_16BIT_4_3("rawpixls-hasselblad-hasselblad-h4d-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/2631/nice/Hasselblad%20-%20Hasselblad%20H4D%20-%2016bit%20%284%3A3%29.3FR",
            "5c0b033e2978035637d07c3e1567bf7748a06c5d5c0ae168027c74d54df7ff80"),

    /**
     * rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr (108399104 bytes).
     */
    FFF_X1D_II_50C_16BIT_4_3("rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/3941/nice/Hasselblad%20-%20X1D%20II%2050C%20-%2016bit%20%284%3A3%29.3FR",
            "809d498263d6f07cbaa4b9a5be97933057b4ab21e7243bb7afbe3862f3b48b47"),

    /**
     * rawpixls-hasselblad-cfv-100c-16bit-4-3.3fr (211857408 bytes).
     */
    FFF_CFV_100C_16BIT_4_3("rawpixls-hasselblad-cfv-100c-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/7782/nice/Hasselblad%20-%20CFV%20100C%20-%2016bit%20%284%3A3%29.3FR",
            "e3375d7d19a231dd590aaaf07976ffef6e654c4c27d419fa74528bd72271ff4b"),

    /**
     * rawpixls-hasselblad-cfv-50c-16bit-4-3-7199.fff (88050502 bytes).
     */
    FFF_CFV_50C_16BIT_4_3_7199("rawpixls-hasselblad-cfv-50c-16bit-4-3-7199.fff", "https://raw.pixls.us/getfile.php/7199/nice/Hasselblad%20-%20CFV-50c%20-%2016bit%20%284%3A3%29.fff",
            "e8fccbf0f746561f6445b54cbe9c40e4962b8019a9436627c326b73c3a30c1b2"),

    /**
     * rawpixls-hasselblad-cfv-50c-16bit-4-3.3fr (72417280 bytes).
     */
    FFF_CFV_50C_16BIT_4_3("rawpixls-hasselblad-cfv-50c-16bit-4-3.3fr", "https://raw.pixls.us/getfile.php/7217/nice/Hasselblad%20-%20CFV-50c%20-%2016bit%20%284%3A3%29.3FR",
            "e27ff177519298fe7bae64506b975190789dc0e6ad70bea58be7c78fd094eae0"),

    /**
     * rawpixls-hasselblad-cfv-50c-16bit-4-3-7218.fff (79620782 bytes).
     */
    FFF_CFV_50C_16BIT_4_3_7218("rawpixls-hasselblad-cfv-50c-16bit-4-3-7218.fff", "https://raw.pixls.us/getfile.php/7218/nice/Hasselblad%20-%20CFV-50c%20-%2016bit%20%284%3A3%29.fff",
            "c8d78912ae725a59acff42a4b4731350bbddd4a600423f319579261bd41da932"),

    /**
     * rawpixls-leaf-aptus-22-16bit-4-3-3196.mos (23423633 bytes).
     */
    MOS_APTUS_22_16BIT_4_3_3196("rawpixls-leaf-aptus-22-16bit-4-3-3196.mos", "https://raw.pixls.us/getfile.php/3196/nice/Leaf%20-%20Aptus%2022%20-%2016bit%20%284%3A3%29.mos",
            "8a1a101cb3940b0648af54a721d92e6fb8612dd29a0909036ea2753196a00ec2"),

    /**
     * rawpixls-leaf-aptus-22-16bit-4-3-3197.mos (43365202 bytes).
     */
    MOS_APTUS_22_16BIT_4_3_3197("rawpixls-leaf-aptus-22-16bit-4-3-3197.mos", "https://raw.pixls.us/getfile.php/3197/nice/Leaf%20-%20Aptus%2022%20-%2016bit%20%284%3A3%29.mos",
            "2a54d6fb8da0f01a966aaa21b896a036428c83fb2009824bb2ba946b9216a81c"),

    /**
     * rawpixls-leaf-afi-ii-12-16bit-4-3.mos (84007412 bytes).
     */
    MOS_AFI_II_12_16BIT_4_3("rawpixls-leaf-afi-ii-12-16bit-4-3.mos", "https://raw.pixls.us/getfile.php/5432/nice/Leaf%20-%20AFi-II%2012%20-%2016bit%20%284%3A3%29.mos",
            "5442fb5342bacc2b9c7e461ee516f68aa01301623adef872363d1c6e68b78027"),

    /**
     * rawpixls-kodak-dcs760c-12bit-3-2.dcr (7547392 bytes).
     */
    DCR_DCS760C_12BIT_3_2("rawpixls-kodak-dcs760c-12bit-3-2.dcr", "https://raw.pixls.us/getfile.php/1347/nice/Kodak%20-%20DCS760C%20-%2012bit%20%283%3A2%29.DCR",
            "d0e6bd0a3339daff884e73a08fed8e75b20fe05bba438fc185e3f0bd019d0da8"),

    /**
     * rawpixls-kodak-dc120-8bit-4-3.kdc (843328 bytes).
     */
    DCR_DC120_8BIT_4_3("rawpixls-kodak-dc120-8bit-4-3.kdc", "https://raw.pixls.us/getfile.php/2338/nice/Kodak%20-%20DC120%20-%208bit%20%284%3A3%29.kdc",
            "967c3af47289e6cc5ab4f9bd24135b7e5386f574fc76deb3b13e884bfda87856"),

    /**
     * rawpixls-phase-one-iq140-iiq-l-sensor-4-3.tif (10897729 bytes).
     */
    IIQ_IQ140_IIQ_L_SENSOR_4_3("rawpixls-phase-one-iq140-iiq-l-sensor-4-3.tif", "https://raw.pixls.us/getfile.php/2022/nice/Phase%20One%20-%20IQ140%20-%20IIQ%20L%2C%20Sensor%2B%20%284%3A3%29.TIF",
            "56dbb1d9d200245fe84928b0c05d84229fccc7fb61a47db8c46605e2c1ff5e06"),

    /**
     * rawpixls-mamiya-zd.mef (36575308 bytes).
     */
    MEF_ZD("rawpixls-mamiya-zd.mef", "https://raw.pixls.us/getfile.php/562/nice/Mamiya%20-%20ZD.MEF",
            "bcd63507c3c4cc3ea1bad2945e3f88cb4677a1a3c762e1acc38df4445714eb7c"),

    /**
     * rawpixls-phase-one-p40-iiq-s-4-3.iiq (11172571 bytes).
     */
    IIQ_P40_IIQ_S_4_3("rawpixls-phase-one-p40-iiq-s-4-3.iiq", "https://raw.pixls.us/getfile.php/8010/nice/Phase%20One%20-%20P40%2B%20-%20IIQ%20S%20%284%3A3%29.IIQ",
            "61aa32c5947d386f0d5ee465c5183185df6c9cd7ff7eb66ffec12e84b487878c"),

    /**
     * rawpixls-kodak-p880-nan.kdc (13828802 bytes).
     */
    DCR_P880_NAN("rawpixls-kodak-p880-nan.kdc", "https://raw.pixls.us/getfile.php/2339/nice/Kodak%20-%20P880%20-%20NAN.KDC",
            "8138d068d14e9ce90c6877b175759fc92358024303b8cd5e9b9eb5ae11e8c4b1"),

    /**
     * rawpixls-kodak-kodak-p712-zoom-digital-camera-nan.kdc (12245402 bytes).
     */
    DCR_KODAK_P712_ZOOM_DIGITAL_CAMERA_NAN("rawpixls-kodak-kodak-p712-zoom-digital-camera-nan.kdc", "https://raw.pixls.us/getfile.php/2345/nice/Kodak%20-%20KODAK%20P712%20ZOOM%20DIGITAL%20CAMERA%20-%20NAN.KDC",
            "b1c3870c8c794f0271053a80a15296e3a9b575ab736796cdb53929d50c86a084"),

    /**
     * rawpixls-kodak-eos-dcs-3-8bit-4-3.tif (1374208 bytes).
     */
    DCR_EOS_DCS_3_8BIT_4_3("rawpixls-kodak-eos-dcs-3-8bit-4-3.tif", "https://raw.pixls.us/getfile.php/2465/nice/Kodak%20-%20EOS%20DCS%203%20-%208bit%20%284%3A3%29.TIF",
            "dd05419fedb091ca14c86485106e8c338509b09c8188dacf92eef6c78007deca"),

    /**
     * rawpixls-kodak-dcs520c-12bit-3-2.tif (1970973 bytes).
     */
    DCR_DCS520C_12BIT_3_2("rawpixls-kodak-dcs520c-12bit-3-2.tif", "https://raw.pixls.us/getfile.php/2573/nice/Kodak%20-%20DCS520C%20-%2012bit%20%283%3A2%29.TIF",
            "d95e983bea9cf13f356e49b0f59629306bc7a51882ff20766c74ccff5f007d69"),

    /**
     * rawpixls-kodak-easyshare-z990-nan.kdc (22483665 bytes).
     */
    DCR_EASYSHARE_Z990_NAN("rawpixls-kodak-easyshare-z990-nan.kdc", "https://raw.pixls.us/getfile.php/2969/nice/Kodak%20-%20EasyShare%20Z990%20-%20NAN.KDC",
            "378607dffc5e40f95d36fe7e0a5ce015c91b441c685843767b571d6f79ba2518"),

    /**
     * rawpixls-kodak-dcs-pro-14n-12bit-3-2.dcr (15913984 bytes).
     */
    DCR_DCS_PRO_14N_12BIT_3_2("rawpixls-kodak-dcs-pro-14n-12bit-3-2.dcr", "https://raw.pixls.us/getfile.php/2970/nice/Kodak%20-%20DCS%20Pro%2014n%20-%2012bit%20%283%3A2%29.DCR",
            "a2fa7abe9dc1a4978ec7bda31dd6b5e4b37ce1ddf9067e0eeb9123d38ea13f69"),

    /**
     * rawpixls-kodak-easyshare-z981.kdc (25702448 bytes).
     */
    DCR_EASYSHARE_Z981("rawpixls-kodak-easyshare-z981.kdc", "https://raw.pixls.us/getfile.php/3482/nice/Kodak%20-%20EasyShare%20Z981.KDC",
            "b2642a8ff212781b6990a9281e3f8bf7a404bfcf9fc249ec76abe258dd008bee"),

    /**
     * rawpixls-kodak-dcs-pro-14nx-12bit-3-2.dcr (13881344 bytes).
     */
    DCR_DCS_PRO_14NX_12BIT_3_2("rawpixls-kodak-dcs-pro-14nx-12bit-3-2.dcr", "https://raw.pixls.us/getfile.php/2319/nice/Kodak%20-%20DCS%20Pro%2014nx%20-%2012bit%20%283%3A2%29.DCR",
            "0850ca7337169b609cef369f0de1e71952646d922b50cf1752b7ed6a2ef81ed5"),

    /**
     * rawpixls-kodak-dcs-pro-slr-n-12bit-3-2.dcr (12880896 bytes).
     */
    DCR_DCS_PRO_SLR_N_12BIT_3_2("rawpixls-kodak-dcs-pro-slr-n-12bit-3-2.dcr", "https://raw.pixls.us/getfile.php/2635/nice/Kodak%20-%20DCS%20Pro%20SLR/n%20-%2012bit%20%283%3A2%29.DCR",
            "c32e54a18969751a5502c16c5d69b152fc841831151121c1d2ae18e6656fae86"),

    /**
     * rawpixls-kodak-dcs-pro-slr-c-12bit-3-2.dcr (14920832 bytes).
     */
    DCR_DCS_PRO_SLR_C_12BIT_3_2("rawpixls-kodak-dcs-pro-slr-c-12bit-3-2.dcr", "https://raw.pixls.us/getfile.php/5138/nice/Kodak%20-%20DCS%20Pro%20SLR/c%20-%2012bit%20%283%3A2%29.DCR",
            "dca493a81bd4f87428d1584fa4ee6a4e98c97d6540b16a7a42d25916b0fd6107"),

    /**
     * rawpixls-nikon-1-j1-12bit-12bit-compressed-lossy-type-2-3-2-1017.nef (9624896 bytes).
     */
    NEF_1_J1_LOSSY_12("rawpixls-nikon-1-j1-12bit-12bit-compressed-lossy-type-2-3-2-1017.nef", "https://raw.pixls.us/getfile.php/1017/nice/Nikon%20-%201%20J1%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "5965935729931b72ca8398041f8fc15cb0a226004db941d1f0626ccc2c5bb5f3"),

    /**
     * rawpixls-nikon-coolpix-a-14bit-14bit-compressed-lossy-type-2-3-2.nef (18027123 bytes).
     */
    NEF_COOLPIX_A_LOSSY_14("rawpixls-nikon-coolpix-a-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/2238/nice/Nikon%20-%20Coolpix%20A%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "c5ca0333a7cda8a2fc33fb2157d18dfa89f1844548dc49c2fd2e67f502f72e25"),

    /**
     * rawpixls-nikon-d3-12bit-12bit-compressed-lossy-type-2-3-2.nef (8891234 bytes).
     */
    NEF_D3_LOSSY_12("rawpixls-nikon-d3-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/3516/nice/Nikon%20-%20D3%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "880c60c5f611adf6b70de3d099f8433492de3a3d96866d570122f17f0a651fc8"),

    /**
     * rawpixls-nikon-d3-14bit-14bit-compressed-lossy-type-2-3-2.nef (11334958 bytes).
     */
    NEF_D3_LOSSY_14("rawpixls-nikon-d3-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/3519/nice/Nikon%20-%20D3%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "0626c09a58ca12ee4c12f919772abd4fd053c7d88c2f2b25e8d9be7c26cc7fd5"),

    /**
     * rawpixls-nikon-d5-14bit-14bit-compressed-lossy-type-2-3-2.nef (29971795 bytes).
     */
    NEF_D5_LOSSY_14("rawpixls-nikon-d5-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/2218/nice/Nikon%20-%20D5%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "febde1abbb52dcd207f10d44412742af491d63ef96a9cbd8a491a33b98944eeb"),

    /**
     * rawpixls-nikon-d500-12bit-12bit-compressed-lossy-type-2-3-2.nef (16691242 bytes).
     */
    NEF_D500_LOSSY_12("rawpixls-nikon-d500-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/2504/nice/Nikon%20-%20D500%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "33c96a2ba7167f780928767c0545719737d601883e5f98e5edba4f71bdc99d83"),

    /**
     * rawpixls-nikon-d750-14bit-14bit-compressed-lossy-type-2-3-2.nef (22861674 bytes).
     */
    NEF_D750_LOSSY_14("rawpixls-nikon-d750-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/896/nice/Nikon%20-%20D750%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "3389da2bd0997be2f8e1408d8d0fba09f5062de05c4017a0bbf2849d8f0cd2a1"),

    /**
     * rawpixls-nikon-d810-12bit-12bit-compressed-lossy-type-2-3-2.nef (29734746 bytes).
     */
    NEF_D810_LOSSY_12("rawpixls-nikon-d810-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/2034/nice/Nikon%20-%20D810%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "6aa49c8cdfcde936b10a342d4901391fe4dcdb54cfb6b9b5d02ce46c65ac5ebf"),

    /**
     * rawpixls-nikon-d850-14bit-14bit-compressed-lossy-type-2-3-2.nef (47609342 bytes).
     */
    NEF_D850_LOSSY_14("rawpixls-nikon-d850-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/1841/nice/Nikon%20-%20D850%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "e54b5d4f0d5c721a90309741c60754e687750f1b2d366ac2587d870eef121126"),

    /**
     * rawpixls-nikon-d90-12bit-12bit-compressed-lossy-type-2-3-2.nef (11653767 bytes).
     */
    NEF_D90_LOSSY_12("rawpixls-nikon-d90-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/1325/nice/Nikon%20-%20D90%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "9eb3daa4e3d1c1614bccc4c6d136c4166960f936f17c437cc4d11a5786b2df06"),

    /**
     * rawpixls-nikon-df-14bit-14bit-compressed-lossy-type-2-3-2.nef (16091700 bytes).
     */
    NEF_DF_LOSSY_14("rawpixls-nikon-df-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/1388/nice/Nikon%20-%20Df%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "452ed50be5e212cf7832cd5fc992d920206cebde8864c073988aeaf5a9d07708"),

    /**
     * rawpixls-nikon-d6-12bit-12bit-compressed-lossy-type-2-3-2.nef (14562481 bytes).
     */
    NEF_D6_LOSSY_12("rawpixls-nikon-d6-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4726/nice/Nikon%20-%20D6%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "3a098e704d0e1af97efc37541b43a37e5b3457d2f4e7828c0d8d9af37332f233"),

    /**
     * rawpixls-nikon-d6-14bit-14bit-compressed-lossy-type-2-3-2.nef (18600326 bytes).
     */
    NEF_D6_LOSSY_14("rawpixls-nikon-d6-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4721/nice/Nikon%20-%20D6%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "081d5dbc1d6c97935020d33ffa486dcaa8d4564cbde280cfadcf26ff61da2838"),

    /**
     * rawpixls-nikon-d780-12bit-12bit-compressed-lossy-type-2-3-2.nef (25019527 bytes).
     */
    NEF_D780_LOSSY_12("rawpixls-nikon-d780-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/3827/nice/Nikon%20-%20D780%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "986488947e552af25c6824667aed5a1622140a581dc57efd4b00f0b55b5e4ae0"),

    /**
     * rawpixls-nikon-d780-14bit-14bit-compressed-lossy-type-2-3-2.nef (30764714 bytes).
     */
    NEF_D780_LOSSY_14("rawpixls-nikon-d780-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/3829/nice/Nikon%20-%20D780%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "6ee9e13f6f2532a5ba66f43b75885cf8a3f27f252d39969545f0471eff7d3f6f"),

    /**
     * rawpixls-nikon-z-5-12bit-12bit-compressed-lossy-type-2-3-2.nef (22183585 bytes).
     */
    NEF_Z5_LOSSY_12("rawpixls-nikon-z-5-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4138/nice/Nikon%20-%20Z%205%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "d893b6f7e7d2c0aa7d4b016a559bf0d9af440c9ec7486119085c2b2368c7a2a4"),

    /**
     * rawpixls-nikon-z-5-14bit-14bit-compressed-lossy-type-2-3-2.nef (27265178 bytes).
     */
    NEF_Z5_LOSSY_14("rawpixls-nikon-z-5-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4139/nice/Nikon%20-%20Z%205%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "e675138e7185b026000c97b654b80ac230c5796f48a9180633672ddacee01bed"),

    /**
     * rawpixls-nikon-z-6-2-14bit-14bit-compressed-lossy-type-2-3-2.nef (35015960 bytes).
     */
    NEF_Z6II_LOSSY_14("rawpixls-nikon-z-6-2-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4161/nice/Nikon%20-%20Z%206_2%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "c79fa1f8986ba059771f125af283dc3b0ed8bd27c46db9e4f83d2a0e3fb34059"),

    /**
     * rawpixls-nikon-z-7-2-12bit-12bit-compressed-lossy-type-2-3-2.nef (38448793 bytes).
     */
    NEF_Z7II_LOSSY_12("rawpixls-nikon-z-7-2-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4216/nice/Nikon%20-%20Z%207_2%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "5b8322b8bb2bd72a5a824e558d5ee9cdc5790085baaf3e6c14825c3e3b919286"),

    /**
     * rawpixls-nikon-z-7-2-14bit-14bit-compressed-lossy-type-2-3-2.nef (47203410 bytes).
     */
    NEF_Z7II_LOSSY_14("rawpixls-nikon-z-7-2-14bit-14bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4215/nice/Nikon%20-%20Z%207_2%20-%2014bit%2014bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "60de3df0af395adac77c20db7f2714760450eaff99f8d0a76834c447bedf7381"),

    /**
     * rawpixls-nikon-d300-12bit-12bit-compressed-lossy-type-2-3-2.nef (9132977 bytes).
     */
    NEF_D300_LOSSY_12("rawpixls-nikon-d300-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/928/nice/Nikon%20-%20D300%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "f1162624678ae9b0e0e835fcbe77d97584fc88ceefd996e38ee126942b54bc8c"),

    /**
     * rawpixls-nikon-d700-12bit-12bit-compressed-lossy-type-2-3-2.nef (11434022 bytes).
     */
    NEF_D700_LOSSY_12("rawpixls-nikon-d700-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/2255/nice/Nikon%20-%20D700%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "bfd5e164c3fa10ffdc38237000ccb63f52a53bcd080ceb48de1443534dd17036"),

    /**
     * rawpixls-nikon-d3s-12bit-12bit-compressed-lossy-type-2-3-2.nef (11593381 bytes).
     */
    NEF_D3S_LOSSY_12("rawpixls-nikon-d3s-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/5207/nice/Nikon%20-%20D3S%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "322ec3cea1f0be42ea38b3e4974dcfdadd1ff6574743d82d2a59f07242852297"),

    /**
     * rawpixls-nikon-d300s-12bit-12bit-compressed-lossy-type-2-3-2.nef (10391867 bytes).
     */
    NEF_D300S_LOSSY_12("rawpixls-nikon-d300s-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/3055/nice/Nikon%20-%20D300S%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "bb9834d03bc158731aefebc5bef11ab1607c9c8cc12f8ae964e6c1b031696d05"),

    /**
     * rawpixls-nikon-d4-12bit-12bit-compressed-lossy-type-2-3-2.nef (15419789 bytes).
     */
    NEF_D4_LOSSY_12("rawpixls-nikon-d4-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/1978/nice/Nikon%20-%20D4%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "1148bcbf80106b21f7788d0b926751bf5a14ebb5b8cf45ff67bf5bfe6b4ac84f"),

    /**
     * rawpixls-nikon-d3100-12bit-12bit-compressed-lossy-type-2-3-2.nef (11388395 bytes).
     */
    NEF_D3100_LOSSY_12("rawpixls-nikon-d3100-12bit-12bit-compressed-lossy-type-2-3-2.nef", "https://raw.pixls.us/getfile.php/4229/nice/Nikon%20-%20D3100%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "e8113b6e7285155f9f2b88f81566dab63a59cf5559d6915113b74e8c3a8680f6"),

    /**
     * rawpixls-nikon-d5000-12bit-12bit-compressed-lossy-type-2-3-2-1341.nef (10269709 bytes).
     */
    NEF_D5000_LOSSY_12("rawpixls-nikon-d5000-12bit-12bit-compressed-lossy-type-2-3-2-1341.nef", "https://raw.pixls.us/getfile.php/1341/nice/Nikon%20-%20D5000%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%202%29%29%20%283%3A2%29.NEF",
            "5f8e8005be87366bbf7746d35c3c88f82a12a1dd18206c939d8f1254c5922360"),

    /**
     * rawpixls-gopro-hero5-black-16bit-4-3-936.gpr (3701646 bytes).
     */
    GPR_HERO5_BLACK_16BIT_4_3_936("rawpixls-gopro-hero5-black-16bit-4-3-936.gpr", "https://raw.pixls.us/getfile.php/936/nice/GoPro%20-%20HERO5%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "191901b64412083146256ccebeacd954783f6c40a4556ef355a067915136159b"),

    /**
     * rawpixls-gopro-hero5-black-16bit-4-3-1634.gpr (4579178 bytes).
     */
    GPR_HERO5_BLACK_16BIT_4_3_1634("rawpixls-gopro-hero5-black-16bit-4-3-1634.gpr", "https://raw.pixls.us/getfile.php/1634/nice/GoPro%20-%20HERO5%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "88ca49daa9c0f7d20b7f7de1dbabfc9228ca9cb2e19415f28887a6658ad0f918"),

    /**
     * rawpixls-gopro-hero6-black-16bit-4-3-2556.gpr (3835682 bytes).
     */
    GPR_HERO6_BLACK_16BIT_4_3_2556("rawpixls-gopro-hero6-black-16bit-4-3-2556.gpr", "https://raw.pixls.us/getfile.php/2556/nice/GoPro%20-%20HERO6%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "ca9b79fcdfab1cc071405209063fb736cc0b7169a7a88fe091fceee803f546fb"),

    /**
     * rawpixls-gopro-hero6-black-16bit-4-3-2557.gpr (3386578 bytes).
     */
    GPR_HERO6_BLACK_16BIT_4_3_2557("rawpixls-gopro-hero6-black-16bit-4-3-2557.gpr", "https://raw.pixls.us/getfile.php/2557/nice/GoPro%20-%20HERO6%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "c2ed9c01933f3136a6f886ae57fd59692ee5f565e4c07cc73087fb6bceaec80e"),

    /**
     * rawpixls-gopro-hero6-black-16bit-4-3-2558.gpr (3622730 bytes).
     */
    GPR_HERO6_BLACK_16BIT_4_3_2558("rawpixls-gopro-hero6-black-16bit-4-3-2558.gpr", "https://raw.pixls.us/getfile.php/2558/nice/GoPro%20-%20HERO6%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "17a24d42735464525773048172c888ea24d13ba4b907e64580b63c3bab987ae5"),

    /**
     * rawpixls-gopro-hero7-black-16bit-4-3-2786.gpr (4153712 bytes).
     */
    GPR_HERO7_BLACK_16BIT_4_3_2786("rawpixls-gopro-hero7-black-16bit-4-3-2786.gpr", "https://raw.pixls.us/getfile.php/2786/nice/GoPro%20-%20HERO7%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "0c2003137feb0ca7c9a8adf6cd832bc8fba7ff1b89cf5d77822b22558b9a20fe"),

    /**
     * rawpixls-gopro-hero7-black-16bit-4-3-2846.gpr (5223038 bytes).
     */
    GPR_HERO7_BLACK_16BIT_4_3_2846("rawpixls-gopro-hero7-black-16bit-4-3-2846.gpr", "https://raw.pixls.us/getfile.php/2846/nice/GoPro%20-%20HERO7%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "dbbda002a09e98e70bf8a5133783dc5c91610e5b9447d685127c43c255a0d055"),

    /**
     * rawpixls-gopro-hero7-black-16bit-4-3-2908.gpr (3045278 bytes).
     */
    GPR_HERO7_BLACK_16BIT_4_3_2908("rawpixls-gopro-hero7-black-16bit-4-3-2908.gpr", "https://raw.pixls.us/getfile.php/2908/nice/GoPro%20-%20HERO7%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "f7d8a5b693bc173a777164dbad48921c8d145d602d5422925a13090404514b66"),

    /**
     * rawpixls-gopro-hero8-black-16bit-4-3-3949.gpr (4073376 bytes).
     */
    GPR_HERO8_BLACK_16BIT_4_3_3949("rawpixls-gopro-hero8-black-16bit-4-3-3949.gpr", "https://raw.pixls.us/getfile.php/3949/nice/GoPro%20-%20HERO8%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "bfd992594af380748f3d46e37814dae595356cb6fce182d4188230ca978e6a9f"),

    /**
     * rawpixls-gopro-hero9-black-16bit-4-3.gpr (8055720 bytes).
     */
    GPR_HERO9_BLACK_16BIT_4_3("rawpixls-gopro-hero9-black-16bit-4-3.gpr", "https://raw.pixls.us/getfile.php/4483/nice/GoPro%20-%20HERO9%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "bf6f67d0c7096f347b864310f6d58be35237c3d0e715b47a1b30e650e9d785b1"),

    /**
     * rawpixls-gopro-hero10-black-16bit-4-3-4883.gpr (6069216 bytes).
     */
    GPR_HERO10_BLACK_16BIT_4_3_4883("rawpixls-gopro-hero10-black-16bit-4-3-4883.gpr", "https://raw.pixls.us/getfile.php/4883/nice/GoPro%20-%20HERO10%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "f462ea1e695a0491960a496e3998c8806a2a02a2830c9c696c7067c0039a437c"),

    /**
     * rawpixls-gopro-hero10-black-16bit-4-3-4894.gpr (8705046 bytes).
     */
    GPR_HERO10_BLACK_16BIT_4_3_4894("rawpixls-gopro-hero10-black-16bit-4-3-4894.gpr", "https://raw.pixls.us/getfile.php/4894/nice/GoPro%20-%20HERO10%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "92a47ef8a30b9e22440b0eb71c519e271e66e64c51765415b9de644c902b358f"),

    /**
     * rawpixls-gopro-hero7-black-16bit-4-3-5766.gpr (4291460 bytes).
     */
    GPR_HERO7_BLACK_16BIT_4_3_5766("rawpixls-gopro-hero7-black-16bit-4-3-5766.gpr", "https://raw.pixls.us/getfile.php/5766/nice/GoPro%20-%20HERO7%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "17998cd871ef258d29f85419163d4e506411bba57fda05f0c3e0488c2088344f"),

    /**
     * rawpixls-gopro-hero8-black-16bit-4-3-5767.gpr (6419796 bytes).
     */
    GPR_HERO8_BLACK_16BIT_4_3_5767("rawpixls-gopro-hero8-black-16bit-4-3-5767.gpr", "https://raw.pixls.us/getfile.php/5767/nice/GoPro%20-%20HERO8%20Black%20-%2016bit%20%284%3A3%29.GPR",
            "a0bc88bd5f4f18829b66314b4bbde0318d32bae427a2cd06fa3964eb92177f4e"),

    /**
     * rawpixls-gopro-hero11-black-16bit-5-4.gpr (13920706 bytes).
     */
    GPR_HERO11_BLACK_16BIT_5_4("rawpixls-gopro-hero11-black-16bit-5-4.gpr", "https://raw.pixls.us/getfile.php/7319/nice/GoPro%20-%20HERO11%20Black%20-%2016bit%20%285%3A4%29.GPR",
            "d7a6be163c847cb8456e6858a99aef3b85dc3d8836bca1da1b8886effe0d43e1"),

    /**
     * rawpixls-gopro-fusion-16bit-1-1-7370.gpr (4965234 bytes).
     */
    GPR_FUSION_16BIT_1_1_7370("rawpixls-gopro-fusion-16bit-1-1-7370.gpr", "https://raw.pixls.us/getfile.php/7370/nice/GoPro%20-%20FUSION%20-%2016bit%20%281%3A1%29.GPR",
            "f90535bbb28f8b24093c6112bd982f89c47cb0181bcfaba97471e191861a1e80"),

    /**
     * rawpixls-gopro-fusion-16bit-1-1-7371.gpr (4310812 bytes).
     */
    GPR_FUSION_16BIT_1_1_7371("rawpixls-gopro-fusion-16bit-1-1-7371.gpr", "https://raw.pixls.us/getfile.php/7371/nice/GoPro%20-%20FUSION%20-%2016bit%20%281%3A1%29.GPR",
            "f3eb9413ed30c92ac6e1834b8228ecf5c9a546c142e8e7ec01146c13035cb532"),

    /**
     * rawpixls-panasonic-dmc-fz8-4-3.raw (11615232 bytes).
     */
    RW2_DMC_FZ8_4_3("rawpixls-panasonic-dmc-fz8-4-3.raw", "https://raw.pixls.us/getfile.php/2282/nice/Panasonic%20-%20DMC-FZ8%20-%204%3A3.RAW",
            "a11f1534f3092a4d5f2b57d182f6b06f8d26cf766eb463c3e02b26a338b63b8b"),

    /**
     * rawpixls-panasonic-dmc-fz50-16-9.raw (15255996 bytes).
     */
    RW2_DMC_FZ50_16_9("rawpixls-panasonic-dmc-fz50-16-9.raw", "https://raw.pixls.us/getfile.php/2323/nice/Panasonic%20-%20DMC-FZ50%20-%2016%3A9.RAW",
            "30cac8cf14686387f2d5b8823c6d2ce3af19e62fc910e6abd77e6038e99412a7"),

    /**
     * rawpixls-panasonic-dmc-lx1-16-9.raw (16841216 bytes).
     */
    RW2_DMC_LX1_16_9("rawpixls-panasonic-dmc-lx1-16-9.raw", "https://raw.pixls.us/getfile.php/2639/nice/Panasonic%20-%20DMC-LX1%20-%2016%3A9.RAW",
            "8ff982526d215d337706640cc6aa0822cccbe65a86ebe01daef0fd43cb45a4ca"),

    /**
     * rawpixls-leica-digilux-3-4-3.raw (15041536 bytes).
     */
    RW2_DIGILUX_3_4_3("rawpixls-leica-digilux-3-4-3.raw", "https://raw.pixls.us/getfile.php/4884/nice/Leica%20-%20Digilux%203%20-%204%3A3.RAW",
            "d2a4c556fe3660b70df34846dbb19c900a4e1fda0416e5651b1f8b6be7bfdd38");

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
