package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The HEIF conformance files of Nokia (github.com/nokiatech/heif_conformance, directory
 * {@code conformance_files}) and the HEIC samples of pillow_heif (github.com/bigcat88/pillow_heif,
 * BSD-3-Clause: iPhone, Xiaomi and Canon photos and libheif-written test images), each pinned to
 * one commit and to its SHA-256. The Nokia repository carries no license file; its files are only
 * downloaded into the local cache, never redistributed. Cache names are {@code heic-nokia-} and
 * {@code heic-pillow-} followed by the path with slashes replaced by dashes.
 */
final class HeicVectors {
    private static final String NOKIA =
            "https://raw.githubusercontent.com/nokiatech/heif_conformance/f17e517f7518984b4450349a88edc09519082c74/conformance_files/";

    private static final String PILLOW =
            "https://raw.githubusercontent.com/bigcat88/pillow_heif/b16be1196dfa465a342d68894696e685ca3655cb/";

    private static final Map<String, String> NOKIA_SHA256 = Map.ofEntries(
            Map.entry("C001.heic", "b0ea1bcca6f4a1317b7a513bbf23f279e3b47dc6c6ba22031d8939cceb341456"),
            Map.entry("C002.heic", "2b836102e528f7b465b295724b6928c5725a5ac3fe326c6bb54e5e8a18fc180f"),
            Map.entry("C003.heic", "ce14515a9be842859c340f6695c3c65911f356bf292cf84d1f23a2aeaec4b028"),
            Map.entry("C004.heic", "a5cccb3f7cb602d21d44b8e50dedec4c2aacd425da8653a053215e846e7b42db"),
            Map.entry("C005.heic", "8df455f463861613a3fc09327472739663c876af1d8d096f68cc3e16d25edeb8"),
            Map.entry("C006.heic", "41699c9a89dacb7a9536f43ae98f6554b9f272feb7397174cdc38b25387d7934"),
            Map.entry("C007.heic", "789ef64b3dfc7d39c38c407da8329642dcccb90473228c04ab6826c390ca4345"),
            Map.entry("C008.heic", "1d4b5cce1192b0961e4d8505a62099d39ee4f6f6ba516892e730b567997e5987"),
            Map.entry("C009.heic", "7d1aa4012bb69a13fc4cad8d875368fa703407cd74c20363455b8520b791b412"),
            Map.entry("C010.heic", "35a82540cca9d9b38f7d6e3f304a0006696f5a9f20874d544be7ad63f6e105e6"),
            Map.entry("C011.heic", "99fdfcb7a0d7861c96747e3d2ac5fc25bff0ef3b39d530c9f52d07a0ed65d1c2"),
            Map.entry("C012.heic", "117106d67be07d1488eb89b0c9b18670b021f4e1d6376f27fdc57cd7cdca7355"),
            Map.entry("C013.heic", "433057d6ee4faf13490fd616921889eabd7194e9e1bea9748be7e941d880255e"),
            Map.entry("C014.heic", "4acf8abc58bd5a8f9cc4e4f05e01a3712934d2a86824dd2ff65d3c92d8060176"),
            Map.entry("C015.heic", "b25c5191b40a1ba5fe6c5c281c5042499ac77b267529de90248fd7ad94104c11"),
            Map.entry("C016.heic", "0a8bc81b1ba4b8437e527a9fe3b254fe48933b0b5672ff6a23177f76b24950fb"),
            Map.entry("C017.heic", "7bd45ec3b278a601d4ba1905964856cf0003b15896adfffe3f645fd83c0417cd"),
            Map.entry("C018.heic", "67a5bde732421439557b9e43e601781210556456a040a43036af199f1880eae0"),
            Map.entry("C019.heic", "9f094bb21cd5cf5da53b354ea22c37a059390b1c121c4eafe88f359a0623f402"),
            Map.entry("C020.heic", "4f95f844cfa7615150a0acfe6dd6599228bceb7f76885a305a46278b666c26c3"),
            Map.entry("C021.heic", "addd129b0847cacd8d4cc2403e6baf337cfc30e978e1e54771f547156a17c12e"),
            Map.entry("C022.heic", "07cb91171df0b324b44d3fee09b1f205524652997928445b530e1c463a8c57de"),
            Map.entry("C023.heic", "35555c641992f40db8d12b37cc3ea4264e5b45eb25e425f08ee7093b47ccffe5"),
            Map.entry("C024.heic", "039cbb9631558d76fe8240a8c45f9a2a3a0645ba19bdf7665b9f9951e65c2764"),
            Map.entry("C025.heic", "8921aa6ccb29aa49a1122c602cdcbecb0a2dcdcae3cd1422631ac794bad72a69"),
            Map.entry("C026.heic", "c2561b8da054723a5e85e4f551620c00e956d86bc07053c8ff44309c829876cd"),
            Map.entry("C027.heic", "7a2f66f785d38676fe5c4a2e37365617c80931052eb2aecc866154afb7c14cdc"),
            Map.entry("C028.heic", "2bafef3c563913e4653292da8be9b21a36fe15e87cdf6510dfaf588fe2e145b0"),
            Map.entry("C029.heic", "9b8678d222576f834c371d9cc499854b0a5c7bb8ceecbc816cdee2d3fbed42ad"),
            Map.entry("C030.heic", "018bf04b8ab7354f8d14a438a299cdd25e685e3217c7387508c47d32d91436d0"),
            Map.entry("C031.heic", "cf9e46d7ed8e899d091db068ded7b5e02aa00d33848463484eaa736b3aeb6558"),
            Map.entry("C032.heic", "7b14be0e0a0a8ae8c0909b0c594a8cdc7a16143e1cea3ce476f2be59bd4e5402"),
            Map.entry("C034.heic", "d2d61c040eba858cff05d7804c0999fb8955bcfe3ec99e5fd9f0b90d2dd2fe97"),
            Map.entry("C036.heic", "85b45f190ec7c3dd26f09ce703f9aa6c2edeadb69f4648044ba9c3a059fab8a4"),
            Map.entry("C037.heic", "86d538a4175b25cd2f75c31b64f4d2fce431adf4b0e9cf1239a01203d64cb8e4"),
            Map.entry("C038.heic", "a2b57365e6dbfdd96b20cabc12e47c02c9a137539f8313312a8841de8af6c7dc"),
            Map.entry("C039.heic", "507e4fe241b73e098050ac12d6cefe84efbf2acf0d7f8b0b23f23480f6911658"),
            Map.entry("C040.heic", "7d9160ff8f2e0f195c870484dee255383f549243ec782d70c3cc9431bc5ec268"),
            Map.entry("C041.heic", "7a90757b22d3448267f44cc163e19611961e1228cd67a614b6ac8c4144bf082d"),
            Map.entry("C042.heic", "ac0c075dfe02520fabc3d1b078d8502eabf950e77011a663dbd9015fbb886aeb"),
            Map.entry("C043.heic", "4d9e131a9e896625348b374605d4b65d62b64178d31c938b5302fb704c2a2042"),
            Map.entry("C044.heic", "550443448520e724af11734f86d51e50a8e42e3f4b5ed47debc2c5d25fdb3190"),
            Map.entry("C045.heic", "9dd45ad0c14713cf82703e753048580b22152c3f35aa4610e446418efb47d654"),
            Map.entry("C047.heic", "8c5fe84142d463ae621636f54603d33fdfa154072713477a58fc9a4c1de890ce"),
            Map.entry("C048.heic", "3c47929c8908605e0958e105172f07a834f5cb91da70bd35489f687dfb13690c"),
            Map.entry("C049.heic", "c63c05ee4a19f661d8454c02f537e80118123010c9bbba24c33a79fc11ab2389"),
            Map.entry("C050.heic", "3be11849d3b744c9a96e58dc22361a6f34fbb8db440326957529823cc43b5955"),
            Map.entry("C051.heic", "d7f22fca9974cca7e62e1481044b6b379c534a44ec97258ab4b28e95fd2caa59"),
            Map.entry("C052.heic", "05d2017881b23b1e9adc5585e9ec725b47dec1ca74b6854ba2375772f0b18166"),
            Map.entry("C053.heic", "c641d26a9189371f9320827ba035eb05bc241975fdc9e6e60540f52de3feae97"),
            Map.entry("MIAF001.heic", "c15fcd574e108a9a2933cb6e5e5680df1cbf1e917379d88306e6c89c63b22055"),
            Map.entry("MIAF002.heic", "006baff837e3a8736154206f901a6595b0951eb62e282463e7dfc4a33c3da775"),
            Map.entry("MIAF003.heic", "499c8ef32ff744f42b06cc89d6404dcc3754043d2251f4b6f8fedf2f6f04a513"),
            Map.entry("MIAF004.heic", "6625d8a5f13390134c808ac3a86f7c2f293400f2efc381383502b2910415ebc1"),
            Map.entry("MIAF005.heic", "d0dae956c59747326c2966786a1e88d3c8c2818ae5e701211da81328fa828d49"),
            Map.entry("MIAF006.heic", "944952d316c9b1681afe67b2ad6a60bda53c9413ece54fe6a04f003b0cf5fd7d"),
            Map.entry("MIAF007.heic", "370489520fd6f97dab4dbf2e81c1d77f9e19b0bfb28996245a2dc313af8ea81c"),
            Map.entry("multilayer001.heic", "7b50db4483d31d78306cd1bf6e5b7d43d497d1cba6ac5b6895cadc546883164c"),
            Map.entry("multilayer003.heic", "8e3963d7a0f997ad78be13809cccbe125482c5c961a30a0cb043b6aba0c870cb"),
            Map.entry("multilayer004.heic", "25fa4a3ff860370a1f1da643cd2b96a16520dd1255d7e70fbcac564374a49cfb"),
            Map.entry("multilayer005.heic", "43cd906e0f04e12ceb007e683d637b68c72184f2118a69882e19f286c69f73d2")
    );

    private static final Map<String, String> PILLOW_SHA256 = Map.ofEntries(
            Map.entry("tests/images/heif/L_8__29x100.heif", "4fbbb035e6444bd6d661aefa4597d757cd0ea451a07b2c8fc37a46c269540baf"),
            Map.entry("tests/images/heif/RGBA_10__29x100.heif", "0499c3a3d8aa1c4383494f90cc127a693f036ab6228f0b91ddcf96f662520129"),
            Map.entry("tests/images/heif/RGBA_8__128x128.heif", "df634be2fa51f3d9a244dc9c987d7179411814e7b171bc40273a4486181c559f"),
            Map.entry("tests/images/heif/zPug_3.heic", "daf1515c651e15968ad6ec142e443759156fee76c65715e4f5713cf1ad072774"),
            Map.entry("tests/images/heif_other/L_exif_xmp_iptc.heic", "0083645511ffdee8acf04973237272678d3eb2554d77bde2adeeca102c220d51"),
            Map.entry("tests/images/heif_other/arrow.heic", "73f604d7353df4848505a1f45a5517f736857fe047372f95c2f3d671df92c22c"),
            Map.entry("tests/images/heif_other/cat.hif", "3a396202a8bb50c5cccbfd89d3cf6ef0a0fa47794afd785693d882edc2eb30b7"),
            Map.entry("tests/images/heif_other/nokia/alpha.heic", "4dbbf50e1a0178261c46781bd764626fc75bf11549e744204c68ef212755c4c1"),
            Map.entry("tests/images/heif_other/pug.heic", "1ddce96766efbfa0f6bdf634bdab26bdeff831393df8d67f4cdc7b7a7afebc67"),
            Map.entry("tests/images/heif_other/spatial_photo.heic", "ef941d328072a2df034adb3c3b46f41117606bfc8fcc100dff975d31f55ec028"),
            Map.entry("tests/images/heif_special/xiaomi.heic", "cb5c7d9131931df4abd9bfe1c3de47617b68d1fae54a1c367fc5981ffea6aeed")
    );

    private HeicVectors() {
    }

    static Path nokia(String name) {
        var sha = NOKIA_SHA256.get(name);
        if (sha == null) {
            throw new IllegalArgumentException("unpinned Nokia vector " + name);
        }
        return Conformance.fetch("heic-nokia-" + name, NOKIA + name, sha);
    }

    static Path pillow(String path) {
        var sha = PILLOW_SHA256.get(path);
        if (sha == null) {
            throw new IllegalArgumentException("unpinned pillow_heif sample " + path);
        }
        return Conformance.fetch("heic-pillow-" + path.replace('/', '-'), PILLOW + path, sha);
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
