package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the 3D-AVC streams that {@code tools/testdata/h264-3davc/atm-streams.sh} codes with the 3DV-ATM 14.0
 * encoder (ITU-T H.264.2) from synthetic two-view sequences, and compares every texture and depth view with the
 * encoder's reconstruction: depth-range-based weighted prediction of P depth slices with a depth range per view
 * ({@code atm-drwp-p}), of P and B depth slices with a depth parameter set per access unit ({@code atm-drwp-b}),
 * both reproduced by the 3DV-ATM decoder too, and depth-based motion vector prediction in CAVLC slices in
 * depth-first and texture-first coding ({@code atm-cavlc-*}, which the 3DV-ATM decoder does not decode), and every
 * texture coding tool of CABAC slices in texture-first coding ({@code atm-tools-texture-first}: view synthesis skip
 * and direct macroblocks, block-based view synthesis prediction with disparities refined from the base view's
 * depth, adaptive luminance compensation and run-length skip coding), which the 3DV-ATM decoder reproduces.
 */
@DisplayName("H.264 3D-AVC streams coded by the 3DV-ATM encoder")
class H264Avc3dEncodedTest {
    record Stream3d(String name, String sha256, Map<String, String> md5s) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Stream3d> streams() {
        return Stream.of(
                new Stream3d("atm-drwp-p", "486609cb61181157edf5eb91d458ccffdc1547623333c53331e1ef418ff68b14",
                        Map.of("v3", "5e58fe4dc91e0d6f68568edffb68359c", "v1", "626b5803d7e6a16d96e23bd43e1e3fb8",
                                "d3", "27cd5a4f885d6f1ba58e48960e7030e9", "d1", "8a2076a558182c8ef9e5ab9c408b68c2")),
                new Stream3d("atm-drwp-b", "ca638a41c842f0c418d8f8895bfca9f47727f0139f52aabf391566c46f031bf7",
                        Map.of("v3", "0156111c86fc03978b03b76389d881b8", "v1", "0436bcd7a13c26069db8bdb20de8cca8",
                                "d3", "473ca371ebdf472473fed10cb82314b2", "d1", "4c050ef1407a1cc541b440967b148042")),
                new Stream3d("atm-cavlc-dmvp", "237925184fefccf0990989b7c77790c806d392b9ef38bb2fcbb543c8a8634803",
                        Map.of("v3", "7a392cd8143c37a7a4229b1f38527046", "v1", "fc682e5d64b5b9072f0e194b81a77216",
                                "d3", "d3b9d076573cfd21c6c9ab3bc9dbfe21", "d1", "797e8f1cf1966ea85e36d24fe7f2b62c")),
                new Stream3d("atm-cavlc-dmvp-texture-first",
                        "7abc4a4aad158c8d31c2a0a640f7d89a16b641cbd052a2984189facc072eaffa",
                        Map.of("v3", "7a392cd8143c37a7a4229b1f38527046", "v1", "e185ccf7bb7343ca966028acaa549408",
                                "d3", "d3b9d076573cfd21c6c9ab3bc9dbfe21", "d1", "797e8f1cf1966ea85e36d24fe7f2b62c")),
                new Stream3d("atm-tools-texture-first",
                        "f1ecf151e779eb3f4c21c423fc2487bd385bcd107835bae8fda10b09546c1ab2",
                        Map.of("v3", "5433749dbe2d568a5349a9c5e55dc90c", "v1", "e31606e12fbbd1a4ee91594015222cce",
                                "d3", "011887b0003ae0901ff47c687b796d1a", "d1", "9847a6be9ae522c482924cad39e44673")));
    }

    static byte[] bytes(Stream3d stream) throws IOException {
        try (var input = H264Avc3dEncodedTest.class.getResourceAsStream("avc3d/" + stream.name() + ".264")) {
            return input.readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("the stream is the one streams.tsv pins")
    void pinned(Stream3d stream) throws IOException, NoSuchAlgorithmException {
        assertEquals(stream.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes(stream))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("decodes every texture and depth view as the encoder reconstructed it")
    void allViews(Stream3d stream) throws IOException {
        var failures = new ArrayList<MediaException.Unsupported>();
        var output = H264Avc3dConformanceTest.decode(bytes(stream), H264DecoderOptions.builder().allViews()
                .depth(true).build(), failures);
        assertEquals(Map.copyOf(stream.md5s()), Map.copyOf(output.md5s()), stream.toString());
        assertEquals(List.of(), failures);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("decodes the same views with four frame threads")
    void threaded(Stream3d stream) throws IOException {
        var output = H264Avc3dConformanceTest.decode(bytes(stream), H264DecoderOptions.builder().allViews()
                .depth(true).threads(4).build(), new ArrayList<>());
        assertEquals(Map.copyOf(stream.md5s()), Map.copyOf(output.md5s()), stream.toString());
    }
}
