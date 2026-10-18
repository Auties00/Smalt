package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the SILK-only RFC 8251 test vectors (02, 03 and 04) through the SILK driver and checks
 * every packet's final range against the vector and the int16 output against the libopus 1.5.2
 * reference harness, at several API rates and channel counts.
 */
class SilkRfcVectorsTest {
    private static final String VECTORS_SHA256 = "6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55";

    private static Map<String, byte[]> vectors;

    private static synchronized Map<String, byte[]> vectors() throws IOException {
        if (vectors == null) {
            var archive = Conformance.fetch("opus_testvectors-rfc8251.tar.gz",
                    "https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz", VECTORS_SHA256);
            var found = new HashMap<String, byte[]>();
            try (var in = new GZIPInputStream(Files.newInputStream(archive), 1 << 16)) {
                readTar(in, found);
            }
            vectors = found;
        }
        return vectors;
    }

    private static void readTar(InputStream in, Map<String, byte[]> found) throws IOException {
        var header = new byte[512];
        while (in.readNBytes(header, 0, 512) == 512) {
            if (header[0] == 0) {
                return;
            }
            var name = new String(header, 0, 100, StandardCharsets.US_ASCII).replace("\0", "").trim();
            var sizeText = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
            var size = sizeText.isEmpty() ? 0 : Long.parseLong(sizeText, 8);
            var padded = (size + 511) / 512 * 512;
            var wanted = name.endsWith("testvector02.bit") || name.endsWith("testvector03.bit")
                    || name.endsWith("testvector04.bit");
            if (wanted) {
                var data = in.readNBytes((int) size);
                found.put(name.substring(name.lastIndexOf('/') + 1), data);
                in.skipNBytes(padded - size);
            } else {
                in.skipNBytes(padded);
            }
        }
    }

    @ParameterizedTest(name = "testvector{0} @{1}x{2}")
    @CsvSource({
            "02,48000,2,4ad3b7aeaea242acc96cafebee69edb0914abc2e46c0c884f812ce10bbee4dbc",
            "02,48000,1,94367ca9d7cb8b43ba973ebe5668242c3fd5bc1922d457b3a1192fcdaae47718",
            "02,16000,2,2caef8aad21ae8948cc2b4c42ee50a090f67b6109df61b93e82f66f7a10a8b48",
            "02,16000,1,adad099a4c955b5a2c7926902c287e6c5c04bf232600acefd343601a258d492f",
            "02,8000,2,4c66762001f1b612fe3a07b25731361b460d82dbefa62864ea6048c1f1826097",
            "02,8000,1,3c086df9e663f7ebe9b12f0c3fb3ab6bd0f54e326516b6f23b14ff330e770efe",
            "03,48000,2,e00f4ee27f007d849fb67f4386db93254631d2ff31b580394d854819560f1bc7",
            "03,48000,1,2048ec5ddc2e562912a5f0cb7a01c5fe13312116013757faf73992c07420f30e",
            "03,16000,2,7488d015dd5c088731283ccdabd0258be4f5dcf591543429a7a1e4023dbd9c06",
            "03,16000,1,b361fe6d30f06d13778328667de6eb7cfc098ebb46345dc07fcb3fc4337da7fb",
            "03,8000,2,6e22f0ea150d26556ec4372066d828efea18c28585361a510685a9cac90fae85",
            "03,8000,1,a83f7f52b833aa29260e0e5b01907bcfdf44dd62118b2de4ce33d4b40aab78e7",
            "04,48000,2,ce7450716f4911284332e76dab66518dcfd5c84e320c0a8a54014e658f5c6908",
            "04,48000,1,7c14e250c4bee2b25527a3af31fb67643e7a3dd17bab11f3a6595d4cd90bfb9d",
            "04,16000,2,916f514a4bb36e47cf67ef23ffd25538ab4ff9bd62a40e2b4a1fc0ab1cd2e197",
            "04,16000,1,c29a0dcca024b208efb09dbf19e395bb9f9ffe15eae1a0c2b72a6b2213717527",
            "04,8000,2,b00196f0a6912d9db1a826433924a0337d0cc542f2d64adbcdd68bc092e3000e",
            "04,8000,1,2c0fe0a8a6d15c337a979db00dd7d46db54bca334ef19d22f6f730c073c799c5"
    })
    @DisplayName("decodes the SILK-only RFC 8251 vectors with matching final ranges and output")
    void vector(String number, int rate, int channels, String sha256) throws Exception {
        var bit = vectors().get("testvector" + number + ".bit");
        assertTrue(bit != null && bit.length > 0, "vector present");
        var driver = new SilkReferenceDriver(rate, channels);
        var pcm = driver.decodeAll(SilkReferenceDriver.parseBit(bit), 0, false);
        assertEquals(0, driver.rangeMismatches, "final range mismatches");
        assertTrue(driver.rangeChecks > 900, "packets checked");
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm)));
    }
}
