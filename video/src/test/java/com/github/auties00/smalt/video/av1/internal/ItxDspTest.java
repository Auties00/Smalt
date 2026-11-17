package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the inverse transforms against dav1d's C reference: for every transform size and type
 * pair, at 8 and 10 bits, 48 random blocks (random prediction, end-of-block position and
 * coefficient magnitudes, from a shared xorshift32 generator) are transformed, and the FNV-1a
 * hash of every destination buffer (with its padding) and of the coefficient buffer after the
 * call must equal the hash dav1d's C kernels produced from the same inputs (harness
 * {@code opt-av1dec-work/itx/harness_tmpl.c} in the media workspace).
 */
@DisplayName("AV1 inverse transforms")
class ItxDspTest {
    /**
     * Bit depth, transform size, transform type, and the dav1d C hash of the group.
     */
    private static final String C_HASHES = """
            8 0 0 9d2b75281d3a189b
            8 0 1 cb3a3dc6a854cae6
            8 0 2 72d642d132498b3b
            8 0 3 525038fc6db1d1b6
            8 0 4 c9d1d6a37e9a8397
            8 0 5 1be58cb2adf638d7
            8 0 6 5c54824d0ac5c16a
            8 0 7 b3163ec9806a825f
            8 0 8 4a174f36602f235e
            8 0 9 54fa9ac78689b9fb
            8 0 10 a7d15ae3152764cf
            8 0 11 3e49fbb62bd91c1f
            8 0 12 bd5435b9266d13e5
            8 0 13 f99ed226ab90882c
            8 0 14 67f4e8d32cc5dadc
            8 0 15 271bf1e8b968015a
            8 0 16 799941bf7676ef83
            8 1 0 c601093ccafb59b1
            8 1 1 620e3c2b5c7c9bf6
            8 1 2 c27f2591a97cc0ed
            8 1 3 e79f8720177437aa
            8 1 4 6f5f53f62803248f
            8 1 5 ae5532c6f6e67034
            8 1 6 16f52ea33b2013f2
            8 1 7 9d4ff90aa61ade26
            8 1 8 cc050597c6c0ab7d
            8 1 9 41f2a4a074e9deca
            8 1 10 a1f752919bd51b7f
            8 1 11 3520b1d59f4a0729
            8 1 12 a9f80abaaef6d684
            8 1 13 5b3dca52ce2a58f3
            8 1 14 b159ce0ab1ce9879
            8 1 15 3914b7e08a7b1160
            8 2 0 e69198c03cd951d5
            8 2 1 890240e92100f402
            8 2 2 f3d432de31ef85bc
            8 2 3 f8c7c36ef0186d3b
            8 2 4 094b96d2754fe3ea
            8 2 5 c1eade2b823a9fed
            8 2 6 eab5569639f01b53
            8 2 7 46a87723a6c68170
            8 2 8 f4b07802bad9532e
            8 2 9 9a82366c694c07a8
            8 2 10 2b14782f4f55de52
            8 2 11 c096512641831d5a
            8 3 0 5bca62f11cfe96f9
            8 3 9 0856038d860ed311
            8 4 0 b180c0fe248037a7
            8 5 0 7254f93b289ae9be
            8 5 1 506b7d7106792637
            8 5 2 440bb56ad55ab2a9
            8 5 3 7d39d6a2c62b68b5
            8 5 4 48dce50b5d2da869
            8 5 5 1baa201796308c75
            8 5 6 11afe80ea808ac3a
            8 5 7 f4552172aa819c4c
            8 5 8 0052daea3a3ce122
            8 5 9 c4c55f1a4b901383
            8 5 10 5bba30d3f7c23fcb
            8 5 11 fbc1db457a7df3e2
            8 5 12 db7d7c4be30dd76f
            8 5 13 e782c61c62cd538c
            8 5 14 d98493af7e181876
            8 5 15 747385db5c7f5a18
            8 6 0 29f155efe724d73f
            8 6 1 088cbf2c15247fd0
            8 6 2 699f8f11b19036f0
            8 6 3 45e28447c508bed8
            8 6 4 be2a4435734610cc
            8 6 5 d477a2c0d23d377f
            8 6 6 842dc3ecd03a0d35
            8 6 7 ce0a25b5c8309471
            8 6 8 494433088a640e14
            8 6 9 deddcad66f48e668
            8 6 10 8a2731d13995e4dc
            8 6 11 178aa4395e2306ec
            8 6 12 5c6d3980e18283a2
            8 6 13 61520f719867dabd
            8 6 14 420fcfff1d072de1
            8 6 15 ba1e5e87b9d9cde8
            8 7 0 1e65f9f82b2fa650
            8 7 1 8f8eb72a698ed975
            8 7 2 c492402f178877ce
            8 7 3 bfdffe3a44b17283
            8 7 4 b7062b29279950df
            8 7 5 a80c25cc91923fe5
            8 7 6 cfe6ddb30e1cbc58
            8 7 7 bf551ea6f0739c6b
            8 7 8 66e33b1b53cc0dc9
            8 7 9 5c9d18f39b4dbeec
            8 7 10 3cf0d5056ecc07a5
            8 7 11 ea28ed4e37d486a4
            8 7 12 fe9598a89d9efc77
            8 7 13 01281b1cca497c47
            8 7 14 cd997c319d61c1d5
            8 7 15 ca7aa923e0a5c24e
            8 8 0 91b81ffa1be06e93
            8 8 1 d38d7b7f994f18a6
            8 8 2 9313f30edf753363
            8 8 3 d8c30eac4c859ba4
            8 8 4 1fdfcd25d797b056
            8 8 5 7664b9cdf0e4d131
            8 8 6 1f21766ee1e09f52
            8 8 7 ed3ee3d4f06ce168
            8 8 8 df60d1e9fc84f8df
            8 8 9 db4fb958e546001a
            8 8 10 db1780a30c352296
            8 8 11 ce075b06f62f883a
            8 8 12 5d7166191d378e08
            8 8 13 0794c0d0cb9a9da6
            8 8 14 5af4676660eb1ec2
            8 8 15 8d82c4df5bd6c51d
            8 9 0 0e4c68e7f6f37d9e
            8 9 9 04ab3ee0a7d7a64d
            8 10 0 1ebaf60807128bda
            8 10 9 79c76bccccec96ee
            8 11 0 6b17197e42695717
            8 12 0 a3c45d2be73406b6
            8 13 0 4bbae73f64c2355e
            8 13 1 6b83e5e1ef2bffc4
            8 13 2 c64759ee95fdfddd
            8 13 3 57892d355abd5d74
            8 13 4 a0420d89db61617c
            8 13 5 99b1f9fcc10495f9
            8 13 6 78eaf3859c861c10
            8 13 7 31a70f8224947e53
            8 13 8 e8574c7183d7cc34
            8 13 9 94c2ae391f0f20a6
            8 13 10 00928e001dce2e35
            8 13 11 ebb95e7bf70ec72b
            8 13 12 2f2f02afb94d9a9a
            8 13 13 b57fcccd68e7e85d
            8 13 14 ee0203a1a3b87c4a
            8 13 15 3648f363db1aac60
            8 14 0 d3c8e226256c52f6
            8 14 1 f221b3d7c8afede0
            8 14 2 bca56cc4b6cb8a15
            8 14 3 9308b0257595c16a
            8 14 4 87d7050cb6b6b63f
            8 14 5 89f12301359629c0
            8 14 6 4a531640f5eba47e
            8 14 7 8e7d1035b105222d
            8 14 8 1ca6e2e8b4f4fab4
            8 14 9 0737ed57cb20961f
            8 14 10 3df64f77ac921f41
            8 14 11 1ea3d01df4459441
            8 14 12 698da09c61ce1e2b
            8 14 13 e8f0871840c188b7
            8 14 14 e810994bd7eb0f13
            8 14 15 11a90d17d95b61eb
            8 15 0 5af5f615ce8b72a7
            8 15 9 c9330880981a804c
            8 16 0 20ad4358f247de26
            8 16 9 1f4bf6e394713dcc
            8 17 0 06f948bdcc638c24
            8 18 0 8179b8aa5d645633
            10 0 0 75d6f5b65b04ee1e
            10 0 1 d5aace19b57888b2
            10 0 2 0270d15c0e84b7fa
            10 0 3 d133d112c5e18eab
            10 0 4 72f4cf018c2c9438
            10 0 5 7233fff36f2a0354
            10 0 6 53edc21b2b8f7ff4
            10 0 7 173aa8ae90eb9ecf
            10 0 8 fc7febb841482251
            10 0 9 fccb74c85bd0d585
            10 0 10 e5a9d3c5b043bbf0
            10 0 11 e27283f531009c52
            10 0 12 9ccc241df754320a
            10 0 13 d9bc52be2303830d
            10 0 14 0810b60918587922
            10 0 15 030a9bc97a2f5ea4
            10 0 16 99d48f046622286f
            10 1 0 e4411c2b9e9edf94
            10 1 1 f81e399e50d3f1f9
            10 1 2 acfa46b5fba4c18a
            10 1 3 0d8c9d5f3f5c80ae
            10 1 4 aaf0b77fd7b98bf3
            10 1 5 7172473fca8bacc5
            10 1 6 f592899f82338068
            10 1 7 ef295034d7b8d691
            10 1 8 f858689b6836f195
            10 1 9 274699270f4ddd23
            10 1 10 07969276e9eaeaa4
            10 1 11 18b466bbfc314fc0
            10 1 12 ff0e26321cee97d3
            10 1 13 2409ffbb382bfd5e
            10 1 14 18fbbfccf3c9746e
            10 1 15 58e850c3ca83cf6a
            10 2 0 9e5f1e14993ba739
            10 2 1 85cf379194b287d4
            10 2 2 42fbb133b57d3f92
            10 2 3 f9aa72cacd367a4d
            10 2 4 b9e22df778837558
            10 2 5 92fe913ae17587af
            10 2 6 b622a5161747ca6b
            10 2 7 19f80f1eef729fc9
            10 2 8 8c46b331d387ebd5
            10 2 9 00945b7ce6068f2d
            10 2 10 fc589611768b5fe8
            10 2 11 5680f29253333499
            10 3 0 325749bbe66e9229
            10 3 9 700d4830dfc3bd9a
            10 4 0 15932968ac4c724d
            10 5 0 76fc34c366c7661a
            10 5 1 ac49397a1f211c21
            10 5 2 4d72888b965ff606
            10 5 3 101f7e6a78d604a4
            10 5 4 3f5c52d31782c931
            10 5 5 639adc688e3ed20a
            10 5 6 7b1bf01ddef0466c
            10 5 7 b51438a7afbe5cd2
            10 5 8 8b5f919455928c65
            10 5 9 70673f41b5196cb8
            10 5 10 d0ceab536364d25f
            10 5 11 3e1d612d953851db
            10 5 12 629faa01bbe28cef
            10 5 13 4a5e885aa28eb1dc
            10 5 14 27ecdd829467e37d
            10 5 15 3bee9834f3485256
            10 6 0 535fde8aacc1d4e2
            10 6 1 a866c352439fab31
            10 6 2 b4a338b51984e600
            10 6 3 47a182749e8b9317
            10 6 4 5bd697d0a24fcd41
            10 6 5 2e00f0bfc963574e
            10 6 6 71e81d2a6ad0c1b5
            10 6 7 d62bce9cffa30ae2
            10 6 8 41c87582569fcc7d
            10 6 9 24d223b00a60e5a4
            10 6 10 b7ec5f85ed897010
            10 6 11 078ab01505334686
            10 6 12 a8af5ac205e4f585
            10 6 13 5d5a34f01ce382de
            10 6 14 b2b8f69dc522cab1
            10 6 15 e974d67f9d08a641
            10 7 0 d6697028756a6807
            10 7 1 5e15241e5741703b
            10 7 2 cfa8a5edc97abb54
            10 7 3 98f04bc357264c5f
            10 7 4 3f494ac97f722552
            10 7 5 7b73fb2272a40a23
            10 7 6 913c8e629170b82a
            10 7 7 d71c827b85465b1e
            10 7 8 9b01095e332f99bb
            10 7 9 0d71db76b6ba2a06
            10 7 10 421f50974b065974
            10 7 11 3a141c01b13a1524
            10 7 12 6cbfffdc16ef94bc
            10 7 13 e1173d45cbf93527
            10 7 14 1b43c12e278c4a2e
            10 7 15 c3f4faab4a90cef7
            10 8 0 c645886ea7260d67
            10 8 1 64e433d0da20c2f4
            10 8 2 ead4ddf38dd9775a
            10 8 3 c5ec2c6ca199a84f
            10 8 4 326e058c575f88f0
            10 8 5 f982531a44540d84
            10 8 6 bafa11f6e3303c00
            10 8 7 7fca0cc507666066
            10 8 8 86057fbd5be55d4f
            10 8 9 861088e7d5b17524
            10 8 10 f267fff3aace8bd7
            10 8 11 91fda3913a3d7f29
            10 8 12 01860244d4ff2902
            10 8 13 c343c71400f35217
            10 8 14 0394cdb9ed2db210
            10 8 15 7c79e2aa48924980
            10 9 0 ec2da508bbe65d4f
            10 9 9 33f7ce99e62f20b4
            10 10 0 61c971d0d4ccb3d8
            10 10 9 fa82823cc281b9ea
            10 11 0 8434398db9802770
            10 12 0 3c9bab9be23f7e62
            10 13 0 5a99a2a3b1a514a8
            10 13 1 e069917bee3458f1
            10 13 2 1dafb7ed7402e819
            10 13 3 77d2c8eba41f9296
            10 13 4 dbeae237d7c7624c
            10 13 5 a3d6f092365eba27
            10 13 6 69973f4cf64fdedb
            10 13 7 1ae465f9316f3da4
            10 13 8 ef77cfeb81e41129
            10 13 9 d0f47b40e08fc918
            10 13 10 ee9ff4e87cae0ab1
            10 13 11 17eed0abb9d08954
            10 13 12 d0e7fd2af6bc87fb
            10 13 13 a29c478217aef98d
            10 13 14 a29b08ee0c4e9603
            10 13 15 ee85c3eaa5787d5b
            10 14 0 31ef085679c906ac
            10 14 1 afa78dcc70708d96
            10 14 2 51638f1e8fe23516
            10 14 3 14860b56c0d4e08e
            10 14 4 d0e588af75309370
            10 14 5 e84366b8dd5a0adf
            10 14 6 6d1778ed7d25ff96
            10 14 7 7bdf3a7bdfbbcf58
            10 14 8 7bd1e61b6e9e1be5
            10 14 9 aaaa92a3d6f9e5dd
            10 14 10 a9713462cfaa2d68
            10 14 11 a010f0f465ed6fd1
            10 14 12 5a714f223f43d14e
            10 14 13 87e6f42fc9b70393
            10 14 14 ce8f5c4cf7a5ba9b
            10 14 15 9485c0b18fbe3325
            10 15 0 d5817ffe2bdadf22
            10 15 9 b43f9a6be119b63b
            10 16 0 2301848f6c2e1017
            10 16 9 20247dd1ccf89000
            10 17 0 2fa4f77ac5948a65
            10 18 0 593a15d6eca98c5e
            """;

    private static final int[] LOG2_W = {0, 1, 2, 3, 4, 0, 1, 1, 2, 2, 3, 3, 4, 0, 2, 1, 3, 2, 4};
    private static final int[] LOG2_H = {0, 1, 2, 3, 4, 1, 0, 2, 1, 3, 2, 4, 3, 2, 0, 3, 1, 4, 2};
    private static final int CASES = 48;

    private int state;
    private long hash;

    static Stream<Arguments> groups() {
        return C_HASHES.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(" "))
                .map(parts -> Arguments.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Long.parseUnsignedLong(parts[3], 16)));
    }

    @ParameterizedTest(name = "{0}-bit size {1} type {2}")
    @MethodSource("groups")
    @DisplayName("matches dav1d's C kernels bit for bit")
    void matchesDav1d(int bitdepth, int txSize, int txType, long expected) {
        assertEquals(Long.toHexString(expected), Long.toHexString(group(new ScalarItxDsp(), bitdepth, txSize, txType)));
    }

    @ParameterizedTest(name = "{0}-bit size {1} type {2}")
    @MethodSource("groups")
    @DisplayName("the vector kernels match dav1d's C kernels bit for bit")
    void vectorMatchesDav1d(int bitdepth, int txSize, int txType, long expected) {
        Skip.unlessVector(Simd.ENABLED);
        assertEquals(Long.toHexString(expected), Long.toHexString(group(VectorItxDsp.create(), bitdepth, txSize, txType)));
    }

    @Test
    @DisplayName("accepts exactly the size and type pairs dav1d instantiates")
    void supportedPairs() {
        var expected = new HashSet<Integer>();
        groups().forEach(arguments -> {
            var values = arguments.get();
            expected.add((Integer) values[1] * 32 + (Integer) values[2]);
        });
        var dsp = new ScalarItxDsp();
        for (var txSize = 0; txSize < ItxDsp.TX_SIZES; txSize++) {
            for (var txType = 0; txType <= ItxDsp.WHT_WHT; txType++) {
                var supported = expected.contains(txSize * 32 + txType);
                assertEquals(supported, ItxDsp.isSupported(txSize, txType), "size " + txSize + " type " + txType);
                if (!supported) {
                    var size = txSize;
                    var type = txType;
                    assertThrows(IllegalArgumentException.class, () -> dsp.itxfmAdd(size, type, new short[64 * 64], 0,
                            64, new int[1024], 0, 0, new int[ItxDsp.SCRATCH_INTS], 255));
                }
            }
        }
    }

    @Test
    @DisplayName("leaves the coefficient buffer all-zero")
    void clearsCoefficients() {
        for (var dsp : Simd.ENABLED ? new ItxDsp[]{new ScalarItxDsp(), VectorItxDsp.create()} : new ItxDsp[]{new ScalarItxDsp()}) {
            clearsCoefficients(dsp);
        }
    }

    private static void clearsCoefficients(ItxDsp dsp) {
        var coef = new int[1024];
        var dst = new short[64 * 64];
        var scratch = new int[ItxDsp.SCRATCH_INTS];
        for (var txSize = 0; txSize < ItxDsp.TX_SIZES; txSize++) {
            var scan = Scans.SCANS[txSize];
            var area = Math.min(4 << LOG2_W[txSize], 32) * Math.min(4 << LOG2_H[txSize], 32);
            for (var i = 0; i < area; i++) {
                coef[scan[i]] = i % 7 - 3;
            }
            dsp.itxfmAdd(txSize, 0, dst, 0, 64, coef, 0, area - 1, scratch, 1023);
            assertEquals(0, Arrays.stream(coef).filter(value -> value != 0).count(), "size " + txSize);
        }
    }

    private long group(ItxDsp dsp, int bitdepth, int txSize, int txType) {
        var bitdepthMax = (1 << bitdepth) - 1;
        var coefMax = bitdepth == 8 ? 32767 : ~(~127 << bitdepth);
        var w = 4 << LOG2_W[txSize];
        var h = 4 << LOG2_H[txSize];
        var coded = Math.min(w, 32) * Math.min(h, 32);
        var stride = w + 8;
        var scan = Scans.SCANS[txSize];
        var dst = new short[stride * h];
        var coef = new int[32 * 32];
        var scratch = new int[ItxDsp.SCRATCH_INTS];
        int[] magnitudes = {15, 255, 4095, coefMax};
        state = 0x9E3779B9 ^ (bitdepth << 24) ^ (txSize << 8) ^ txType;
        hash = 0xcbf29ce484222325L;
        for (var n = 0; n < CASES; n++) {
            for (var i = 0; i < dst.length; i++) {
                dst[i] = (short) (next() & bitdepthMax);
            }
            var shape = Integer.remainderUnsigned(next(), 4);
            var eob = shape == 0 ? 0 : shape == 1 ? coded - 1 : Integer.remainderUnsigned(next(), coded);
            var magnitude = magnitudes[Integer.remainderUnsigned(next(), 4)];
            Arrays.fill(coef, 0);
            for (var i = 0; i <= eob; i++) {
                coef[scan[i]] = Integer.remainderUnsigned(next(), 2 * magnitude + 1) - magnitude;
            }
            dsp.itxfmAdd(txSize, txType, dst, 4, stride, coef, 0, eob, scratch, bitdepthMax);
            for (var value : dst) {
                fold(value);
            }
            for (var value : coef) {
                fold(value);
            }
        }
        return hash;
    }

    private int next() {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }

    private void fold(int value) {
        for (var i = 0; i < 4; i++) {
            hash ^= (value >>> (8 * i)) & 0xFF;
            hash *= 0x100000001b3L;
        }
    }
}
