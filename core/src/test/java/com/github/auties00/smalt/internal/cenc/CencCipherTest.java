package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the sample cipher of Common Encryption against the AES-128 vectors of NIST SP 800-38A, against an
 * independent block-by-block model of each scheme's subsample and pattern rules built on the JDK's AES, and for
 * round trips.
 */
@DisplayName("CencCipher")
class CencCipherTest {
    private static final HexFormat HEX = HexFormat.of();

    // NIST SP 800-38A appendix F: the AES-128 key and the four plaintext blocks of every mode
    private static final byte[] NIST_KEY = HEX.parseHex("2b7e151628aed2a6abf7158809cf4f3c");
    private static final byte[] NIST_PLAINTEXT = HEX.parseHex("6bc1bee22e409f96e93d7e117393172a"
            + "ae2d8a571e03ac9c9eb76fac45af8e51" + "30c81c46a35ce411e5fbc1191a0a52ef" + "f69f2445df4f9b17ad2b417be66c3710");

    @Nested
    @DisplayName("NIST SP 800-38A")
    class Nist {
        @Test
        @DisplayName("F.5.1 CTR-AES128.Encrypt as a cenc sample")
        void ctr() {
            var data = NIST_PLAINTEXT.clone();
            var iv = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
            new CencCipher(NIST_KEY).encrypt(CencScheme.CENC, data, 0, data.length, iv, 0, 16, null, 0, 0, 0, 0);
            assertArrayEquals(HEX.parseHex("874d6191b620e3261bef6864990db6ce" + "9806f66b7970fdff8617187bb9fffdff"
                    + "5ae4df3edbd5d35e5b4f09020db03eab" + "1e031dda2fbe03d1792170a0f3009cee"), data);
        }

        @Test
        @DisplayName("F.2.1 CBC-AES128.Encrypt as a cbc1 sample, and F.2.2 back")
        void cbc() {
            var data = NIST_PLAINTEXT.clone();
            var iv = HEX.parseHex("000102030405060708090a0b0c0d0e0f");
            var cipher = new CencCipher(NIST_KEY);
            cipher.encrypt(CencScheme.CBC1, data, 0, data.length, iv, 0, 16, null, 0, 0, 0, 0);
            assertArrayEquals(HEX.parseHex("7649abac8119b246cee98e9b12e9197d" + "5086cb9b507219ee95db113a917678b2"
                    + "73bed6b8e3c1743b7116e69e22229516" + "3ff1caa1681fac09120eca307586e1a7"), data);
            cipher.decrypt(CencScheme.CBC1, data, 0, data.length, iv, 0, 16, null, 0, 0, 0, 0);
            assertArrayEquals(NIST_PLAINTEXT, data);
        }
    }

    @Nested
    @DisplayName("scheme rules")
    class Rules {
        @Test
        @DisplayName("cenc counts blocks in the last eight bytes of the counter, wrapping without a carry")
        void counterWraps() throws Exception {
            var key = bytes(16, 1);
            var iv = HEX.parseHex("0102030405060708ffffffffffffffff");
            var data = bytes(48, 2);
            var expected = data.clone();
            var ecb = ecb(key);
            var counters = new byte[48];
            System.arraycopy(iv, 0, counters, 0, 16);
            System.arraycopy(iv, 0, counters, 16, 8);
            System.arraycopy(iv, 0, counters, 32, 8);
            counters[47] = 1;
            var stream = ecb.doFinal(counters);
            for (var index = 0; index < 48; index++) {
                expected[index] ^= stream[index];
            }
            new CencCipher(key).encrypt(CencScheme.CENC, data, 0, data.length, iv, 0, 16, null, 0, 0, 0, 0);
            assertArrayEquals(expected, data);
        }

        @Test
        @DisplayName("an 8-byte initialization vector is followed by a zero block counter")
        void shortIv() throws Exception {
            var key = bytes(16, 3);
            var data = bytes(20, 4);
            var expected = data.clone();
            var jce = Cipher.getInstance("AES/CTR/NoPadding");
            jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(HEX.parseHex("0102030405060708" + "0000000000000000")));
            expected = jce.doFinal(expected);
            new CencCipher(key).encrypt(CencScheme.CENC, data, 0, data.length, HEX.parseHex("0102030405060708"), 0, 8,
                    null, 0, 0, 0, 0);
            assertArrayEquals(expected, data);
        }

        @ParameterizedTest
        @EnumSource(CencScheme.class)
        @DisplayName("matches a block-by-block model with subsamples and patterns")
        void model(CencScheme scheme) throws Exception {
            var random = new Random(scheme.ordinal() * 31L + 7);
            for (var trial = 0; trial < 200; trial++) {
                var key = new byte[16];
                random.nextBytes(key);
                var iv = new byte[random.nextBoolean() || scheme.isCbc() ? 16 : 8];
                random.nextBytes(iv);
                var pairs = random.nextInt(5);
                var subsamples = new int[pairs * 2];
                var length = 0;
                for (var pair = 0; pair < pairs; pair++) {
                    subsamples[2 * pair] = random.nextInt(40);
                    subsamples[2 * pair + 1] = random.nextInt(4) == 0 ? random.nextInt(20) : random.nextInt(400);
                    length += subsamples[2 * pair] + subsamples[2 * pair + 1];
                }
                if (pairs == 0) {
                    length = random.nextInt(600);
                }
                var crypt = scheme.usesPattern() ? random.nextInt(4) : 0;
                var skip = scheme.usesPattern() ? random.nextInt(10) : 0;
                var offset = random.nextInt(5);
                var data = new byte[offset + length + 3];
                random.nextBytes(data);
                var plaintext = data.clone();
                var expected = encryptModel(scheme, key, iv, plaintext, offset, length, pairs == 0 ? null : subsamples, crypt,
                        skip);
                var cipher = new CencCipher(key);
                cipher.encrypt(scheme, data, offset, length, iv, 0, iv.length, pairs == 0 ? null : subsamples, 0,
                        pairs, crypt, skip);
                assertArrayEquals(expected, data, scheme + " trial " + trial);
                cipher.decrypt(scheme, data, offset, length, iv, 0, iv.length, pairs == 0 ? null : subsamples, 0,
                        pairs, crypt, skip);
                assertArrayEquals(plaintext, data, scheme + " round trip " + trial);
            }
        }

        @Test
        @DisplayName("leaves the clear bytes and the bytes after the last whole block of a CBC run clear")
        void clearBytes() {
            var data = bytes(100, 5);
            var original = data.clone();
            new CencCipher(bytes(16, 6)).encrypt(CencScheme.CBCS, data, 0, 100, bytes(16, 7), 0, 16,
                    new int[]{10, 40, 20, 30}, 0, 2, 1, 9);
            assertArrayEquals(Arrays.copyOfRange(original, 0, 10), Arrays.copyOfRange(data, 0, 10));
            assertFalse(Arrays.equals(Arrays.copyOfRange(original, 10, 26), Arrays.copyOfRange(data, 10, 26)));
            assertArrayEquals(Arrays.copyOfRange(original, 26, 70), Arrays.copyOfRange(data, 26, 70));
            assertFalse(Arrays.equals(Arrays.copyOfRange(original, 70, 86), Arrays.copyOfRange(data, 70, 86)));
            assertArrayEquals(Arrays.copyOfRange(original, 86, 100), Arrays.copyOfRange(data, 86, 100));
        }

        @Test
        @DisplayName("rejects subsamples that do not cover the sample")
        void coverage() {
            var cipher = new CencCipher(bytes(16, 8));
            var data = new byte[64];
            assertThrows(MediaException.Malformed.class, () -> cipher.decrypt(CencScheme.CENC, data, 0, 64,
                    new byte[8], 0, 8, new int[]{10, 20}, 0, 1, 0, 0));
            assertThrows(MediaException.Malformed.class, () -> cipher.decrypt(CencScheme.CENC, data, 0, 64,
                    new byte[8], 0, 8, new int[]{10, 60}, 0, 1, 0, 0));
            assertThrows(MediaException.Malformed.class, () -> cipher.decrypt(CencScheme.CENC, data, 0, 64,
                    new byte[8], 0, 8, new int[]{10, -1}, 0, 1, 0, 0));
            assertThrows(MediaException.Malformed.class, () -> cipher.decrypt(CencScheme.CENC, data, 0, 64,
                    new byte[12], 0, 12, null, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("continues one CBC chain over runs, the bytes after a run's last whole block left out of it")
        void chained() throws Exception {
            var key = bytes(16, 9);
            var iv = bytes(16, 10);
            var first = bytes(20, 11);
            var second = bytes(32, 12);
            var jce = Cipher.getInstance("AES/CBC/NoPadding");
            jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            var joined = new byte[48];
            System.arraycopy(first, 0, joined, 0, 16);
            System.arraycopy(second, 0, joined, 16, 32);
            var expected = jce.doFinal(joined);
            var cipher = new CencCipher(key);
            var a = first.clone();
            var b = second.clone();
            cipher.restartChain(iv);
            cipher.cbcChained(true, a, 0, a.length);
            cipher.cbcChained(true, b, 0, b.length);
            assertArrayEquals(java.util.Arrays.copyOf(expected, 16), java.util.Arrays.copyOf(a, 16));
            assertArrayEquals(java.util.Arrays.copyOfRange(first, 16, 20), java.util.Arrays.copyOfRange(a, 16, 20));
            assertArrayEquals(java.util.Arrays.copyOfRange(expected, 16, 48), b);
            cipher.restartChain(iv);
            cipher.cbcChained(false, a, 0, a.length);
            cipher.cbcChained(false, b, 0, b.length);
            assertArrayEquals(first, a);
            assertArrayEquals(second, b);
        }
    }

    @Test
    @DisplayName("maps scheme types")
    void schemes() {
        for (var scheme : CencScheme.values()) {
            assertEquals(scheme, CencScheme.of(scheme.fourCc()));
        }
        assertEquals(null, CencScheme.of(0x70696666));
        assertEquals(0x63626373, CencScheme.CBCS.fourCc());
    }

    /**
     * Encrypts a sample block by block as ISO/IEC 23001-7 describes, independently of the class under test.
     */
    private static byte[] encryptModel(CencScheme scheme, byte[] key, byte[] iv, byte[] input, int offset, int length,
                                       int[] subsamples, int crypt, int skip) throws Exception {
        var data = input.clone();
        if (crypt == 0 && skip == 0) {
            crypt = 1;
        }
        var ranges = new java.util.ArrayList<int[]>();
        if (subsamples == null) {
            ranges.add(new int[]{offset, length});
        } else {
            var position = offset;
            for (var pair = 0; pair < subsamples.length / 2; pair++) {
                position += subsamples[2 * pair];
                ranges.add(new int[]{position, subsamples[2 * pair + 1]});
                position += subsamples[2 * pair + 1];
            }
        }
        var ecb = ecb(key);
        var full = Arrays.copyOf(iv, 16);
        if (!scheme.isCbc()) {
            var counter = full.clone();
            var keyStream = new byte[0];
            var used = 0;
            for (var range : ranges) {
                for (var index = 0; index < range[1]; index++) {
                    var block = index / 16;
                    var selected = scheme == CencScheme.CENC
                            || (block % (crypt + skip) < crypt && (block + 1) * 16 <= range[1]);
                    if (!selected) {
                        continue;
                    }
                    if (used == keyStream.length) {
                        keyStream = ecb.doFinal(counter);
                        used = 0;
                        for (var at = 15; at >= 8; at--) {
                            if (++counter[at] != 0) {
                                break;
                            }
                        }
                    }
                    data[range[0] + index] ^= keyStream[used++];
                }
            }
            return data;
        }
        var chain = full.clone();
        for (var range : ranges) {
            if (scheme == CencScheme.CBCS) {
                chain = full.clone();
            }
            for (var block = 0; (block + 1) * 16 <= range[1]; block++) {
                var selected = scheme == CencScheme.CBC1 || block % (crypt + skip) < crypt;
                if (!selected) {
                    continue;
                }
                var start = range[0] + block * 16;
                var plain = new byte[16];
                for (var index = 0; index < 16; index++) {
                    plain[index] = (byte) (data[start + index] ^ chain[index]);
                }
                chain = ecb.doFinal(plain);
                System.arraycopy(chain, 0, data, start, 16);
            }
        }
        return data;
    }

    private static Cipher ecb(byte[] key) throws Exception {
        var cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher;
    }

    private static byte[] bytes(int length, int seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }
}
