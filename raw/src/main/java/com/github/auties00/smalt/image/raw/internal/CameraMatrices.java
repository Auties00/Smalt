/* Ports the camera matrices derived from rawtoaces-data (commit e9b8503cf8a0641f40e5345d9757bde60c15f423: the camera
   spectral sensitivities, training reflectances and CIE 1931 color matching functions). Copyright Contributors to the
   rawtoaces Project; Apache License 2.0; the color matching functions CC BY 4.0, CIE. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Holds DNG-style color matrices of camera models whose raw files carry none, derived from
 * measured camera spectral sensitivities, and gives them to files of those models.
 *
 * <p>Each model has an XYZ to camera matrix for CIE illuminant A and one for D65, used like a DNG
 * file's {@code ColorMatrix1} and {@code ColorMatrix2}. A model is recognized by its maker (the
 * first word of the EXIF make) and its EXIF model name, compared on letters and digits only,
 * without case and without a leading repetition of the maker, or by one of the regional or
 * spelled-out names the maker also gives it.
 *
 * @implNote This implementation's matrices are derived from the camera spectral sensitivities,
 * training reflectances and CIE 1931 color matching functions of rawtoaces-data, with the CIE
 * daylight components as rawtoaces tabulates them: for each illuminant, the white-preserving matrix
 * from white-balanced camera values to XYZ that best predicts the training patches in CIELAB,
 * inverted and scaled to the DNG convention. A test re-derives every matrix from the pinned
 * archive; rendered against the cameras' embedded previews, they come out closer in chroma than
 * the generic matrix of {@link CameraColor}.
 */
final class CameraMatrices {
    /**
     * The DNG code of CIE illuminant A.
     */
    private static final int ILLUMINANT_A = 17;

    /**
     * The DNG code of CIE illuminant D65.
     */
    private static final int ILLUMINANT_D65 = 21;

    /**
     * The models, by {@link #key(String, String)}.
     */
    private static final Map<String, Entry> MODELS = models(
            new Entry("ARRI", "D21",
                    new double[]{0.9386, -0.0561, 0.0706, -0.3116, 0.8993, 0.2684, -0.0427, 0.2465, 0.7873},
                    new double[]{0.8319, 0.1183, -0.0506, -0.4151, 1.0541, 0.1743, -0.1255, 0.3576, 0.6994}),
            new Entry("Arashi Vision", "Insta360 X5",
                    new double[]{1.0436, -0.3897, 0.0337, -0.5029, 1.4110, 0.3973, -0.0621, 0.1725, 0.7320},
                    new double[]{0.6883, -0.1464, -0.0789, -0.5497, 1.3075, 0.1974, -0.1479, 0.2498, 0.5173}),
            new Entry("Canon", "Digital Rebel XTi",
                    new double[]{0.9875, -0.3297, -0.0132, -0.5085, 1.3653, 0.5428, -0.0058, 0.0667, 1.0157},
                    new double[]{0.6475, -0.1462, -0.0767, -0.5000, 1.1709, 0.2795, -0.0735, 0.1321, 0.6753}),
            new Entry("Canon", "EOS-1D X Mark II",
                    new double[]{0.8331, -0.1106, 0.0002, -0.4685, 1.3272, 0.5266, -0.0725, 0.1959, 0.8619},
                    new double[]{0.5507, 0.0192, -0.0746, -0.4880, 1.1816, 0.2591, -0.1452, 0.2616, 0.5651}),
            new Entry("Canon", "EOS-1Ds Mark III",
                    new double[]{0.9249, -0.2429, -0.0091, -0.5627, 1.4726, 0.4087, -0.0463, 0.0927, 0.9423},
                    new double[]{0.6142, -0.0669, -0.0836, -0.5471, 1.2849, 0.2159, -0.1088, 0.1563, 0.6626}),
            new Entry("Canon", "EOS-1Ds Mark II",
                    new double[]{0.9405, -0.2482, -0.0006, -0.5749, 1.4716, 0.4491, -0.0457, 0.1125, 1.0716},
                    new double[]{0.6227, -0.0723, -0.0722, -0.5544, 1.2765, 0.2299, -0.1136, 0.1805, 0.7355}),
            new Entry("Canon", "EOS 100D",
                    new double[]{0.8071, -0.1425, 0.0080, -0.4639, 1.3486, 0.4521, -0.0659, 0.1777, 0.8793},
                    new double[]{0.5339, 0.0009, -0.0708, -0.4884, 1.2112, 0.2324, -0.1409, 0.2458, 0.6118}),
            new Entry("Canon", "EOS 200D",
                    new double[]{0.8727, -0.1110, 0.0030, -0.4524, 1.3127, 0.5175, -0.0631, 0.2023, 0.8119},
                    new double[]{0.5671, 0.0319, -0.0786, -0.4758, 1.1679, 0.2611, -0.1271, 0.2524, 0.5434}),
            new Entry("Canon", "EOS 200D II",
                    new double[]{0.8146, -0.0814, -0.0053, -0.4512, 1.3065, 0.5311, -0.0619, 0.2006, 0.8236},
                    new double[]{0.5329, 0.0412, -0.0752, -0.4712, 1.1584, 0.2658, -0.1225, 0.2482, 0.5449}),
            new Entry("Canon", "EOS 5DS",
                    new double[]{0.7761, -0.1422, 0.0048, -0.4990, 1.3612, 0.5251, -0.0673, 0.1723, 0.8067},
                    new double[]{0.4995, -0.0036, -0.0692, -0.5109, 1.2027, 0.2598, -0.1320, 0.2300, 0.5299}),
            new Entry("Canon", "EOS 5D",
                    new double[]{0.9518, -0.2656, 0.0167, -0.5577, 1.4567, 0.4378, -0.0688, 0.1254, 0.9654},
                    new double[]{0.6090, -0.0600, -0.0826, -0.5532, 1.2809, 0.2248, -0.1444, 0.2010, 0.6590}),
            new Entry("Canon", "EOS 5D Mark III",
                    new double[]{0.7862, -0.1524, 0.0014, -0.4819, 1.3670, 0.4559, -0.0683, 0.1726, 0.8125},
                    new double[]{0.5276, -0.0150, -0.0721, -0.5029, 1.2307, 0.2271, -0.1409, 0.2408, 0.5528}),
            new Entry("Canon", "EOS 5D Mark II",
                    new double[]{0.7590, -0.1268, -0.0068, -0.5394, 1.4347, 0.4434, -0.0563, 0.1208, 0.8773},
                    new double[]{0.5054, 0.0008, -0.0723, -0.5321, 1.2601, 0.2256, -0.1194, 0.1848, 0.6043}),
            new Entry("Canon", "EOS 5D Mark IV",
                    new double[]{0.7543, -0.0843, 0.0094, -0.4460, 1.3027, 0.5260, -0.0798, 0.2394, 0.9042},
                    new double[]{0.4914, 0.0379, -0.0666, -0.4726, 1.1664, 0.2596, -0.1615, 0.3074, 0.5861}),
            new Entry("Canon", "EOS 600D",
                    new double[]{0.7851, -0.1417, 0.0108, -0.4717, 1.3583, 0.4492, -0.0559, 0.1703, 0.8678},
                    new double[]{0.5227, -0.0032, -0.0664, -0.4944, 1.2219, 0.2278, -0.1357, 0.2436, 0.5974}),
            new Entry("Canon", "EOS M",
                    new double[]{0.8415, -0.1773, 0.0038, -0.4902, 1.3803, 0.4441, -0.0695, 0.1724, 0.8823},
                    new double[]{0.5555, -0.0216, -0.0757, -0.5082, 1.2339, 0.2287, -0.1431, 0.2401, 0.6157}),
            new Entry("Canon", "EOS R10",
                    new double[]{0.8932, -0.1198, -0.0024, -0.4552, 1.3158, 0.5175, -0.0597, 0.2020, 0.7721},
                    new double[]{0.5830, 0.0251, -0.0811, -0.4776, 1.1720, 0.2589, -0.1213, 0.2490, 0.5104}),
            new Entry("Canon", "EOS R5",
                    new double[]{0.8909, -0.1731, -0.0133, -0.5018, 1.3708, 0.5070, -0.0567, 0.1314, 0.8041},
                    new double[]{0.5901, -0.0244, -0.0850, -0.5123, 1.2118, 0.2525, -0.1264, 0.2018, 0.5351}),
            new Entry("Canon", "EOS R5m2",
                    new double[]{0.9117, -0.1961, -0.0046, -0.5193, 1.3877, 0.5134, -0.0670, 0.1531, 0.9503},
                    new double[]{0.5974, -0.0331, -0.0857, -0.5294, 1.2303, 0.2505, -0.1513, 0.2386, 0.6256}),
            new Entry("Canon", "EOS R6",
                    new double[]{0.8707, -0.1675, 0.0059, -0.4728, 1.3387, 0.5073, -0.0717, 0.1960, 0.8777},
                    new double[]{0.5661, -0.0109, -0.0781, -0.4955, 1.1938, 0.2544, -0.1502, 0.2655, 0.5850}),
            new Entry("Canon", "EOS R6m2",
                    new double[]{0.8916, -0.1803, -0.0081, -0.4937, 1.3633, 0.5029, -0.0746, 0.1875, 0.9581},
                    new double[]{0.5891, -0.0273, -0.0853, -0.5092, 1.2119, 0.2498, -0.1560, 0.2641, 0.6394}),
            new Entry("Canon", "EOS RP",
                    new double[]{0.8287, -0.1516, -0.0014, -0.5049, 1.3717, 0.5139, -0.0719, 0.1751, 0.8544},
                    new double[]{0.5420, -0.0096, -0.0765, -0.5160, 1.2129, 0.2548, -0.1440, 0.2427, 0.5663}),
            new Entry("Canon", "EOS R",
                    new double[]{0.7234, -0.0797, 0.0159, -0.4594, 1.3191, 0.5211, -0.0831, 0.2447, 0.9158},
                    new double[]{0.4692, 0.0412, -0.0624, -0.4858, 1.1842, 0.2548, -0.1656, 0.3137, 0.5966}),
            new Entry("Canon", "PowerShot S90",
                    new double[]{1.2995, -0.5125, 0.0481, -0.2536, 1.1507, 0.3590, -0.0227, 0.0959, 0.5944},
                    new double[]{0.8700, -0.2134, -0.0832, -0.3503, 1.0906, 0.2225, -0.1199, 0.1852, 0.4416}),
            new Entry("Fujifilm", "GFX 100",
                    new double[]{1.3004, -0.5579, 0.0888, -0.4528, 1.3375, 0.4492, -0.0484, 0.1227, 0.7865},
                    new double[]{0.7971, -0.2012, -0.0723, -0.4994, 1.2067, 0.2461, -0.1407, 0.2067, 0.5434}),
            new Entry("Fujifilm", "X-T3",
                    new double[]{1.2377, -0.5333, 0.0818, -0.4249, 1.2994, 0.4700, -0.0459, 0.1199, 0.8145},
                    new double[]{0.7549, -0.1942, -0.0694, -0.4780, 1.1727, 0.2586, -0.1379, 0.2029, 0.5635}),
            new Entry("Fujifilm", "X-T4",
                    new double[]{1.2134, -0.5239, 0.0821, -0.3958, 1.2675, 0.4697, -0.0470, 0.1210, 0.7835},
                    new double[]{0.7422, -0.1899, -0.0687, -0.4619, 1.1590, 0.2571, -0.1383, 0.2039, 0.5433}),
            new Entry("Hasselblad", "L1D-20c",
                    new double[]{0.6751, -0.2048, 0.0307, -0.4096, 1.2910, 0.4464, -0.0474, 0.1618, 0.7590},
                    new double[]{0.4563, -0.0664, -0.0408, -0.4772, 1.2217, 0.2129, -0.1339, 0.2445, 0.5196}),
            new Entry("Hasselblad", "L2D-20c",
                    new double[]{0.7520, -0.2154, 0.0586, -0.4202, 1.2868, 0.4909, -0.0607, 0.1409, 0.7213},
                    new double[]{0.4768, -0.0495, -0.0330, -0.4565, 1.1498, 0.2609, -0.1372, 0.2114, 0.4789}),
            new Entry("Nikon", "D200",
                    new double[]{1.3357, -0.5222, 0.0319, -0.5987, 1.5110, 0.4120, -0.0227, 0.0627, 1.0052},
                    new double[]{0.8644, -0.2208, -0.0826, -0.5722, 1.2975, 0.2261, -0.1081, 0.1451, 0.6903}),
            new Entry("Nikon", "D3300",
                    new double[]{0.9770, -0.3342, 0.0462, -0.4735, 1.3394, 0.5076, -0.0744, 0.1584, 1.0349},
                    new double[]{0.6168, -0.1143, -0.0524, -0.4870, 1.1693, 0.2695, -0.1626, 0.2408, 0.6856}),
            new Entry("Nikon", "D5100",
                    new double[]{1.3200, -0.4656, 0.0442, -0.4610, 1.2791, 0.4784, -0.0725, 0.1504, 1.0020},
                    new double[]{0.8923, -0.1860, -0.0742, -0.4937, 1.1757, 0.2695, -0.1588, 0.2362, 0.7048}),
            new Entry("Nikon", "D5300",
                    new double[]{0.9274, -0.2948, 0.0379, -0.4799, 1.3414, 0.5218, -0.0740, 0.1578, 1.0438},
                    new double[]{0.5904, -0.0981, -0.0476, -0.4864, 1.1637, 0.2741, -0.1549, 0.2340, 0.6859}),
            new Entry("Nikon", "D7000",
                    new double[]{1.0295, -0.3463, 0.0447, -0.5060, 1.3744, 0.5098, -0.0729, 0.1500, 1.0256},
                    new double[]{0.6498, -0.1224, -0.0497, -0.4989, 1.1743, 0.2753, -0.1451, 0.2158, 0.6852}),
            new Entry("Nikon", "D700",
                    new double[]{1.2242, -0.4594, 0.0341, -0.6159, 1.5336, 0.4017, -0.0519, 0.0943, 1.0250},
                    new double[]{0.8042, -0.1923, -0.0707, -0.5908, 1.3280, 0.2144, -0.1297, 0.1723, 0.7126}),
            new Entry("Nikon", "D70",
                    new double[]{0.9414, -0.3164, 0.0202, -0.6468, 1.5465, 0.4608, -0.0166, 0.0493, 0.9089},
                    new double[]{0.6016, -0.1195, -0.0664, -0.5963, 1.2930, 0.2513, -0.0745, 0.1041, 0.6348}),
            new Entry("Nikon", "D800E",
                    new double[]{0.9605, -0.3064, 0.0503, -0.4941, 1.3553, 0.5264, -0.0750, 0.1568, 1.0668},
                    new double[]{0.6052, -0.0996, -0.0420, -0.4934, 1.1659, 0.2783, -0.1549, 0.2318, 0.7017}),
            new Entry("Nikon", "D810",
                    new double[]{0.9604, -0.3021, 0.0538, -0.5084, 1.3735, 0.5196, -0.0729, 0.1648, 1.0929},
                    new double[]{0.6019, -0.0918, -0.0420, -0.5075, 1.1844, 0.2736, -0.1568, 0.2414, 0.7212}),
            new Entry("Nikon", "D850",
                    new double[]{1.1370, -0.4288, 0.0101, -0.6076, 1.5070, 0.4507, -0.0960, 0.1889, 1.0197},
                    new double[]{0.7502, -0.1768, -0.0952, -0.6164, 1.3570, 0.2101, -0.1953, 0.2897, 0.6811}),
            new Entry("Nikon", "Z f",
                    new double[]{1.1278, -0.3770, 0.0545, -0.5173, 1.3987, 0.4761, -0.0803, 0.1749, 1.0933},
                    new double[]{0.7144, -0.1242, -0.0575, -0.5192, 1.2153, 0.2554, -0.1743, 0.2612, 0.7353}),
            new Entry("Panasonic", "DC-GX9",
                    new double[]{0.7875, -0.2248, 0.0540, -0.3777, 1.2441, 0.4797, -0.0541, 0.1420, 0.7601},
                    new double[]{0.5048, -0.0555, -0.0326, -0.4210, 1.1156, 0.2613, -0.1372, 0.2180, 0.5070}),
            new Entry("Sony", "DSC-RX100M4",
                    new double[]{0.7540, -0.2551, 0.0415, -0.4274, 1.3124, 0.4413, -0.0506, 0.1649, 0.7356},
                    new double[]{0.4978, -0.0851, -0.0431, -0.4939, 1.2366, 0.2138, -0.1380, 0.2461, 0.5058}),
            new Entry("Sony", "ILCE-6400",
                    new double[]{0.6612, -0.1814, 0.0392, -0.4326, 1.3037, 0.4818, -0.0604, 0.1279, 0.8023},
                    new double[]{0.4290, -0.0516, -0.0251, -0.4497, 1.1353, 0.2682, -0.1257, 0.1886, 0.5568}),
            new Entry("Sony", "ILCE-7CM2",
                    new double[]{0.7403, -0.2071, 0.0506, -0.4894, 1.3469, 0.5357, -0.0624, 0.1397, 0.9213},
                    new double[]{0.4674, -0.0575, -0.0255, -0.4861, 1.1496, 0.2869, -0.1236, 0.1941, 0.6207}),
            new Entry("Sony", "ILCE-7M3",
                    new double[]{0.7386, -0.2110, 0.0580, -0.5003, 1.3624, 0.5257, -0.0643, 0.1425, 0.9158},
                    new double[]{0.4641, -0.0560, -0.0219, -0.4995, 1.1704, 0.2794, -0.1301, 0.2018, 0.6149}),
            new Entry("Sony", "ILCE-7M4",
                    new double[]{0.7478, -0.2160, 0.0499, -0.5085, 1.3672, 0.5375, -0.0603, 0.1320, 0.9341},
                    new double[]{0.4697, -0.0621, -0.0250, -0.4991, 1.1619, 0.2869, -0.1203, 0.1862, 0.6269}),
            new Entry("Sony", "ILCE-7RM2",
                    new double[]{0.7560, -0.2368, 0.0394, -0.5228, 1.3885, 0.5218, -0.0588, 0.1253, 0.9357},
                    new double[]{0.4755, -0.0756, -0.0342, -0.5139, 1.1857, 0.2780, -0.1217, 0.1833, 0.6302}),
            new Entry("Sony", "ILCE-7RM3",
                    new double[]{0.7220, -0.2119, 0.0452, -0.4990, 1.3640, 0.5172, -0.0614, 0.1351, 0.9131},
                    new double[]{0.4551, -0.0605, -0.0285, -0.4975, 1.1712, 0.2770, -0.1259, 0.1936, 0.6149}),
            new Entry("Sony", "ILCE-7RM4",
                    new double[]{0.7191, -0.2130, 0.0378, -0.4955, 1.3558, 0.5295, -0.0574, 0.1275, 0.9027},
                    new double[]{0.4569, -0.0668, -0.0293, -0.4912, 1.1584, 0.2832, -0.1162, 0.1809, 0.6088}),
            new Entry("Sony", "ILCE-7SM2",
                    new double[]{0.5934, -0.1197, 0.0809, -0.4056, 1.2629, 0.5130, -0.0818, 0.1874, 0.8743},
                    new double[]{0.3736, -0.0001, -0.0007, -0.4385, 1.1156, 0.2766, -0.1684, 0.2650, 0.5847}),
            new Entry("Sony", "ILCE-7SM3",
                    new double[]{0.7308, -0.2139, 0.0479, -0.5084, 1.3669, 0.5382, -0.0696, 0.1598, 0.9509},
                    new double[]{0.4576, -0.0597, -0.0283, -0.5054, 1.1714, 0.2837, -0.1400, 0.2218, 0.6309}),
            new Entry("Sony", "ILCE-9",
                    new double[]{0.7288, -0.2017, 0.0655, -0.4972, 1.3559, 0.5343, -0.0683, 0.1552, 0.9688},
                    new double[]{0.4588, -0.0516, -0.0147, -0.4955, 1.1613, 0.2842, -0.1341, 0.2131, 0.6556})
    );

    /**
     * The other names models of {@link #MODELS} carry in the EXIF model tag, by
     * {@link #key(String, String)}, each mapped to the key of the name the spectral data uses.
     *
     * @implNote This implementation's names are the EXIF model names Canon gives the same camera in
     * different regions (EOS for Europe, Rebel for the Americas, Kiss for Japan), read from
     * the files of raw.pixls.us where it has them, and the spelled-out names of the models
     * Canon abbreviates in the EXIF model tag.
     */
    private static final Map<String, String> ALIASES = aliases(
            new Alias("Canon", "Digital Rebel XTi", "EOS DIGITAL REBEL XTi", "EOS 400D DIGITAL", "EOS Kiss Digital X"),
            new Alias("Canon", "EOS 100D", "EOS REBEL SL1", "EOS Kiss X7"),
            new Alias("Canon", "EOS 200D", "EOS REBEL SL2", "EOS Kiss X9"),
            new Alias("Canon", "EOS 200D II", "EOS 250D", "EOS Rebel SL3", "EOS Kiss X10"),
            new Alias("Canon", "EOS 600D", "EOS REBEL T3i", "EOS Kiss X5"),
            new Alias("Canon", "EOS R5m2", "EOS R5 Mark II"),
            new Alias("Canon", "EOS R6m2", "EOS R6 Mark II")
    );

    /**
     * Prevents instantiation.
     */
    private CameraMatrices() {
        throw new AssertionError();
    }

    /**
     * Gives a file without color matrices those of its model, when known.
     *
     * @param file the description to complete
     */
    static void complete(RawFile file) {
        if (file.color.channels() != 0 || file.color.cameraToSrgb != null || file.make == null || file.model == null) {
            return;
        }
        var entry = find(file.make, file.model);
        if (entry == null) {
            return;
        }
        file.color.colorMatrix[0] = entry.a().clone();
        file.color.illuminant[0] = ILLUMINANT_A;
        file.color.colorMatrix[1] = entry.d65().clone();
        file.color.illuminant[1] = ILLUMINANT_D65;
    }

    /**
     * Returns the matrices of a model.
     *
     * @param make the camera maker
     * @param model the model name
     * @return the matrices, or {@code null} when the model is not known
     */
    static Entry find(String make, String model) {
        var key = key(make, model);
        return MODELS.get(ALIASES.getOrDefault(key, key));
    }

    /**
     * Returns the key of a model: the maker's first word and the model name, letters and digits
     * only, upper case, the model without a leading repetition of the maker.
     *
     * @param make the camera maker
     * @param model the model name
     * @return the key
     */
    static String key(String make, String model) {
        var maker = make.strip().split("\\s+", 2)[0].toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        var name = model.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (name.startsWith(maker) && name.length() > maker.length()) {
            name = name.substring(maker.length());
        }
        return maker + "/" + name;
    }

    /**
     * Formats an entry as it appears in {@link #MODELS}, without the separating comma.
     *
     * @param make the camera maker
     * @param model the model name
     * @param a the matrix for illuminant A
     * @param d65 the matrix for illuminant D65
     * @return the source lines
     */
    static String format(String make, String model, double[] a, double[] d65) {
        return "            new Entry(\"" + make + "\", \"" + model + "\",\n"
                + "                    " + array(a) + ",\n"
                + "                    " + array(d65) + ")";
    }

    /**
     * Formats a matrix as an array expression.
     *
     * @param matrix the matrix entries, row by row
     * @return the expression
     */
    private static String array(double[] matrix) {
        var builder = new StringBuilder("new double[]{");
        for (var index = 0; index < matrix.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.ROOT, "%.4f", matrix[index]));
        }
        return builder.append('}').toString();
    }

    /**
     * Indexes the entries by key.
     *
     * @param entries the camera models and their matrices
     * @return the entries by key
     */
    private static Map<String, Entry> models(Entry... entries) {
        var models = new HashMap<String, Entry>();
        for (var entry : entries) {
            models.put(key(entry.make(), entry.model()), entry);
        }
        return Map.copyOf(models);
    }

    /**
     * Indexes the aliases by key.
     *
     * @param aliases the alternative names of camera models
     * @return the key of each alias name, mapped to the key of the model's name in {@link #MODELS}
     * @throws IllegalArgumentException if an alias names no model of {@link #MODELS}
     */
    private static Map<String, String> aliases(Alias... aliases) {
        var keys = new HashMap<String, String>();
        for (var alias : aliases) {
            var target = key(alias.make(), alias.model());
            if (!MODELS.containsKey(target)) {
                throw new IllegalArgumentException("no matrices for " + alias.make() + " " + alias.model());
            }
            for (var name : alias.names()) {
                keys.put(key(alias.make(), name), target);
            }
        }
        return Map.copyOf(keys);
    }

    /**
     * Holds the other names of a model.
     *
     * @param make the camera maker
     * @param model the model name, as the spectral data names it
     * @param names the other names, as the EXIF model tag carries them
     */
    private record Alias(String make, String model, String... names) {
    }

    /**
     * Holds the matrices of a model.
     *
     * @param make the camera maker, as the spectral data names it
     * @param model the model name, as the spectral data names it
     * @param a the XYZ to camera matrix for illuminant A, row by row
     * @param d65 the XYZ to camera matrix for illuminant D65, row by row
     */
    record Entry(String make, String model, double[] a, double[] d65) {
    }
}
