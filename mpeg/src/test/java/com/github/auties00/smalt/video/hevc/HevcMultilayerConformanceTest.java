package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the JCT-VC multi-layer conformance suites (ITU-T H.265.1): the SHVC bitstreams (spatial,
 * quality, bit depth and color gamut scalability, auxiliary alpha and depth layers, POC resets, output
 * layer sets, representation formats, reference layer offsets and phases) and the MV-HEVC bitstreams
 * (two and three views, with and without inter-view prediction, texture and depth). Each SHVC output
 * layer set's output must match the reference decoder's output picture log: the layer and the MD5 of
 * every plane of every output picture, in output order (8-bit samples for 8-bit pictures, 16-bit
 * little-endian samples otherwise, a monochrome picture's chroma planes mid-gray 4:2:0 planes). The
 * archives without such logs, and the MV-HEVC ones, give the MD5 of each layer's whole output, which
 * the output of the first output layer set naming the layer must match. Every decoded picture hash SEI
 * message is verified on the way. Each archive is read from the hosted test data by
 * {@link Conformance#vector}.
 */
@DisplayName("HEVC multi-layer conformance bitstreams (ITU-T H.265.1, SHVC and MV-HEVC)")
class HevcMultilayerConformanceTest {
    /**
     * Name | directory | archive | bitstream entry.
     */
    private static final String MANIFEST = """
            8LAYERS_QUALCOMM_1|SHVC|8LAYERS_QUALCOMM_1.zip|8LAYERS_QUALCOMM_1.bit
            ADAPTRES_A_ERICSSON_1|SHVC|ADAPTRES_A_ERICSSON_1.zip|ADAPTRES_A_ERICSSON_1.bit
            ALPHA_A_BBC_1|SHVC|ALPHA_A_BBC_1.zip|ALPHA_A_BBC_1.bit
            CGS_A_TECHNICOLOR_1|SHVC|CGS_A_TECHNICOLOR_1.zip|CGS_A_TECHNICOLOR_1.bit
            CGS_B_TECHNICOLOR_1|SHVC|CGS_B_TECHNICOLOR_1.zip|CGS_B_TECHNICOLOR_1.bit
            CGS_C_TECHNICOLOR_1|SHVC|CGS_C_TECHNICOLOR_1.zip|CGS_C_TECHNICOLOR_1.bit
            CGS_D_TECHNICOLOR_1|SHVC|CGS_D_TECHNICOLOR_1.zip|CGS_D_TECHNICOLOR_1.bit
            CGS_E_TECHNICOLOR_1|SHVC|CGS_E_TECHNICOLOR_1.zip|CGS_E_TECHNICOLOR_1.bit
            CGS_F_TECHNICOLOR_1|SHVC|CGS_F_TECHNICOLOR_1.zip|CGS_F_TECHNICOLOR_1.bit
            CGS_G_TECHNICOLOR_1|SHVC|CGS_G_TECHNICOLOR_1.zip|CGS_G_TECHNICOLOR_1.bit
            CGS_H_TECHNICOLOR_1|SHVC|CGS_H_TECHNICOLOR_1.zip|CGS_H_TECHNICOLOR_1.bit
            CGS_I_TECHNICOLOR_1|SHVC|CGS_I_TECHNICOLOR_1.zip|CGS_I_TECHNICOLOR_1.bit
            CONFCROP_A_VIDYO_2|SHVC|CONFCROP_A_VIDYO_2.zip|CONFCROP_A_VIDYO_2.bit
            CONFCROP_B_VIDYO_2|SHVC|CONFCROP_B_VIDYO_2.zip|CONFCROP_B_VIDYO_2.bit
            CONFCROP_C_VIDYO_3|SHVC|CONFCROP_C_VIDYO_3.zip|CONFCROP_C_VIDYO_3.bit
            DEPTH_A_NOKIA_1|SHVC|DEPTH_A_NOKIA_1.zip|DEPTH_A_NOKIA_1.bit
            DISFLAG_A_QUALCOMM_1|SHVC|DISFLAG_A_QUALCOMM_1.zip|DISFLAG_A_QUALCOMM_1.bit
            DPB_A_VIDYO_2|SHVC|DPB_A_VIDYO_2.zip|DPB_A_VIDYO_2.bit
            DPB_B_VIDYO_2|SHVC|DPB_B_VIDYO_2.zip|DPB_B_VIDYO_2.bit
            INACTIVE_A_QCOM_1|SHVC|INACTIVE_A_QCOM_1.zip|INACTIVE_A_QCOM_1.bin
            INBLD_A_NOKIA_2|SHVC|INBLD_A_NOKIA_2.zip|INBLD_A_NOKIA_2.bit
            LAYERID63_A_HHI_1|SHVC|LAYERID63_A_HHI_1.zip|LAYERID63_A_HHI_1/LAYERID63_A_HHI_1.bin
            LAYERID_A_NOKIA_2|SHVC|LAYERID_A_NOKIA_2.zip|LAYERID_A_NOKIA_2.bit
            MAXTID_A_ETRI_2|SHVC|MAXTID_A_ETRI_2.zip|MAXTID_A_ETRI_2.bit
            MAXTID_B_ETRI_2|SHVC|MAXTID_B_ETRI_2.zip|MAXTID_B_ETRI_2.bit
            MAXTID_C_ETRI_2|SHVC|MAXTID_C_ETRI_2.zip|MAXTID_C_ETRI_2.bit
            MVD_A_IDCC_1|SHVC|MVD_A_IDCC_1.zip|MVD_A_IDCC_1/MVD_A_IDCC_1.bit
            MVD_A_NOKIA_1|SHVC|MVD_A_NOKIA_1.zip|MVD_A_NOKIA_1.bit
            NONVUI_A_QUALCOMM_1|SHVC|NONVUI_A_QUALCOMM_1.zip|NONVUI_A_QUALCOMM_1.bit
            NONVUI_B_QUALCOMM_1|SHVC|NONVUI_B_QUALCOMM_1.zip|NONVUI_B_QUALCOMM_1.bit
            NONVUI_C_QUALCOMM_1|SHVC|NONVUI_C_QUALCOMM_1.zip|NONVUI_C_QUALCOMM_1.bit
            OLS_A_NOKIA_1|SHVC|OLS_A_NOKIA_1.zip|OLS_A_NOKIA_1.bit
            OLS_B_NOKIA_1|SHVC|OLS_B_NOKIA_1.zip|OLS_B_NOKIA_1.bit
            OLS_C_NOKIA_1|SHVC|OLS_C_NOKIA_1.zip|OLS_C_NOKIA_1.bit
            POC_A_Ericsson_1|SHVC|POC_A_Ericsson_1.zip|POC_A_Ericsson_1.bit
            POC_B_Ericsson_1|SHVC|POC_B_Ericsson_1.zip|POC_B_Ericsson_1.bit
            PPSSLIST_A_Sony_2|SHVC|PPSSLIST_A_Sony_2.zip|PPSSLIST_A_Sony_2/PPSSLIST_A_Sony_2.bit
            PSEXT_A_VIDYO_2|SHVC|PSEXT_A_VIDYO_2.zip|PSEXT_A_VIDYO_2.bit
            REFLAYER_A_VIDYO_2|SHVC|REFLAYER_A_VIDYO_2.zip|REFLAYER_A_VIDYO_2.bit
            REFLAYER_B_VIDYO_2|SHVC|REFLAYER_B_VIDYO_2.zip|REFLAYER_B_VIDYO_2.bit
            REFLAYER_C_VIDYO_2|SHVC|REFLAYER_C_VIDYO_2.zip|REFLAYER_C_VIDYO_2.bit
            REFLAYER_D_VIDYO_2|SHVC|REFLAYER_D_VIDYO_2.zip|REFLAYER_D_VIDYO_2.bit
            REFREGOFF_A_SHARP_1|SHVC|REFREGOFF_A_SHARP_1.zip|REFREGOFF_A_SHARP_1.bit
            REPFMT_A_VIDYO_2|SHVC|REPFMT_A_VIDYO_2.zip|REPFMT_A_VIDYO_2.bit
            REPFMT_B_VIDYO_2|SHVC|REPFMT_B_VIDYO_2.zip|REPFMT_B_VIDYO_2.bit
            REPFMT_C_VIDYO_2|SHVC|REPFMT_C_VIDYO_2.zip|REPFMT_C_VIDYO_2.bit
            RESCHANGE_A_VIDYO_1|SHVC|RESCHANGE_A_VIDYO_1.zip|RESCHANGE_A_VIDYO_1.bit
            RESPHASE_A_SAMSUNG_2|SHVC|RESPHASE_A_SAMSUNG_2.zip|RESPHASE_A_SAMSUNG_2.bit
            SCREFOFF_A_QCOM_1|SHVC|SCREFOFF_A_QCOM_1.zip|SCREFOFF_A_QCOM_1.bit
            SIM_A_IDCC_1|SHVC|SIM_A_IDCC_1.zip|SIM_A_IDCC_1/SIM_A_IDCC_1.bit
            SIM_B_IDCC_1|SHVC|SIM_B_IDCC_1.zip|SIM_B_IDCC_1/SIM_B_IDCC_1.bit
            SLLEV_A_VIDYO_1|SHVC|SLLEV_A_VIDYO_1.zip|SLLEV_A_VIDYO_1.bit
            SNR_A_IDCC_1|SHVC|SNR_A_IDCC_1.zip|SNR_A_IDCC_1/SNR_A_IDCC_1.bit
            SNR_B_IDCC_1|SHVC|SNR_B_IDCC_1.zip|SNR_B_IDCC_1/SNR_B_IDCC_1.bit
            SNR_C_IDCC_1|SHVC|SNR_C_IDCC_1.zip|SNR_C_IDCC_1/SNR_C_IDCC_1.bit
            SPLITFLAG_A_HHI_1|SHVC|SPLITFLAG_A_HHI_1.zip|SPLITFLAG_A_HHI_1.bit
            SPSREPFMT_A_Sony_2|SHVC|SPSREPFMT_A_Sony_2.zip|SPSREPFMT_A_Sony_2/SPSREPFMT_A_Sony_2.bit
            SPSSLIST_A_Sony_2|SHVC|SPSSLIST_A_Sony_2.zip|SPSSLIST_A_Sony_2/SPSSLIST_A_Sony_2.bit
            SRATIOS_A_SAMSUNG_3|SHVC|SRATIOS_A_SAMSUNG_3.zip|SRATIOS_A_SAMSUNG_3/SRATIOS_A_SAMSUNG_3.bit
            SRATIOS_B_SAMSUNG_2|SHVC|SRATIOS_B_SAMSUNG_2.zip|SRATIOS_B_SAMSUNG_2/SRATIOS_B_SAMSUNG_2.bit
            SREXT_A_FUJITSU_1|SHVC|SREXT_A_FUJITSU_1.zip|SREXT_A_FUJITSU_1.bit
            SREXT_B_FUJITSU_1|SHVC|SREXT_B_FUJITSU_1.zip|SREXT_B_FUJITSU_1.bit
            SREXT_C_FUJITSU_1|SHVC|SREXT_C_FUJITSU_1.zip|SREXT_C_FUJITSU_1.bit
            SREXT_D_FUJITSU_1|SHVC|SREXT_D_FUJITSU_1.zip|SREXT_D_FUJITSU_1.bit
            SREXT_E_FUJITSU_1|SHVC|SREXT_E_FUJITSU_1.zip|SREXT_E_FUJITSU_1.bit
            SREXT_F_FUJITSU_1|SHVC|SREXT_F_FUJITSU_1.zip|SREXT_F_FUJITSU_1.bit
            VUI_A_QUALCOMM_1|SHVC|VUI_A_QUALCOMM_1.zip|VUI_A_QUALCOMM_1.bit
            VUI_B_QUALCOMM_1|SHVC|VUI_B_QUALCOMM_1.zip|VUI_B_QUALCOMM_1.bit
            VUI_C_QUALCOMM_1|SHVC|VUI_C_QUALCOMM_1.zip|VUI_C_QUALCOMM_1.bit
            MVHEVCS_A_Qualcomm_3|MV-HEVC|MVHEVCS_A_Qualcomm_3.zip|MVHEVCS_A_Qualcomm_3/MVHEVCS_A.bit
            MVHEVCS_B_Sharp_3|MV-HEVC|MVHEVCS_B_Sharp_3.zip|MVHEVCS_B_Sharp_3/MVHEVCS_B.bit
            MVHEVCS_C_Sony_3|MV-HEVC|MVHEVCS_C_Sony_3.zip|MVHEVCS_C/MVHEVCS_C.bit
            MVHEVCS_D_NTT_3|MV-HEVC|MVHEVCS_D_NTT_3.zip|MVHEVCS_D_NTT_3/MVHEVCS_D.bit
            MVHEVCS_E_Qualcomm_3|MV-HEVC|MVHEVCS_E_Qualcomm_3.zip|MVHEVCS_E_Qualcomm_3/MVHEVCS_E.bit
            MVHEVCS_F_Qualcomm_3|MV-HEVC|MVHEVCS_F_Qualcomm_3.zip|MVHEVCS_F_Qualcomm_3/MVHEVCS_F.bit
            MVHEVCS_G_NTT_3|MV-HEVC|MVHEVCS_G_NTT_3.zip|MVHEVCS_G_NTT_3/MVHEVCS_G.bit
            MVHEVCS_H_LGE_3|MV-HEVC|MVHEVCS_H_LGE_3.zip|MVHEVCS_H.bit
            MVHEVCS_I_Nokia_3|MV-HEVC|MVHEVCS_I_Nokia_3.zip|MVHEVCS_I.bit
            """;

    record Vector(String name, String directory, String archive, String bitstream) {
        @Override
        public String toString() {
            return name;
        }

        boolean multiview() {
            return directory.equals("MV-HEVC");
        }
    }

    /**
     * One output layer set of a vector and its output picture log.
     */
    record OutputLayerSet(Vector vector, int index, String log) {
        @Override
        public String toString() {
            return vector.name() + " OLS " + index;
        }
    }

    /**
     * One output picture: its layer and the MD5 of each of its planes.
     */
    record Output(int layer, String y, String u, String v) {
    }

    /**
     * One decoded picture of the reference decoder's log or of the engine: its layer, picture order
     * count and the MD5 of each of its planes, the chroma ones {@code null} when the log gives luma
     * only.
     */
    record Logged(int layer, int poc, String y, String u, String v) {
        boolean matches(Logged actual) {
            return poc == actual.poc && matchesSamples(actual);
        }

        boolean matchesSamples(Logged actual) {
            return layer == actual.layer && y.equals(actual.y) && (u == null || u.equals(actual.u) && v.equals(actual.v));
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3]));
        }
        return result;
    }

    static ZipFile archive(Vector vector) throws IOException {
        var prefix = vector.multiview() ? "hevc-mvhevc-" : "hevc-shvc-";
        var file = Conformance.vector(prefix + vector.archive());
        return new ZipFile(file.toFile());
    }

    static byte[] entry(ZipFile zip, String name) throws IOException {
        try (var in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    private static final Pattern OLS = Pattern.compile("[-_]OLS(\\d+)\\.opl$");

    static Stream<OutputLayerSet> logged() throws IOException {
        var result = new ArrayList<OutputLayerSet>();
        for (var vector : vectors()) {
            if (vector.multiview()) {
                continue;
            }
            try (var zip = archive(vector)) {
                var entries = zip.stream().map(java.util.zip.ZipEntry::getName).sorted().toList();
                for (var name : entries) {
                    var matcher = OLS.matcher(name);
                    if (matcher.find()) {
                        result.add(new OutputLayerSet(vector, Integer.parseInt(matcher.group(1)),
                                new String(entry(zip, name), StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        return result.stream();
    }

    static Stream<Vector> unlogged() throws IOException {
        var result = new ArrayList<Vector>();
        for (var vector : vectors()) {
            try (var zip = archive(vector)) {
                var logs = zip.stream().anyMatch(entry -> OLS.matcher(entry.getName()).find());
                if (!logs) {
                    result.add(vector);
                }
            }
        }
        return result.stream();
    }

    private static final Pattern DECODE_LOG = Pattern.compile("POC\\s+(-?\\d+)\\s+LId:\\s+(\\d+).*MD5:([0-9a-f,]+)");

    /**
     * Parses a reference decoder log: the output picture log ({@code LayerId POC MD5:y,u,v} per
     * line) or the decoder's console log ({@code POC n LId: l ... [MD5:y,u,v,(OK)]}), in which
     * every picture is listed once in decoding order.
     */
    static List<Logged> parseLog(String log) {
        // a console log may hold an interrupted run before the complete one
        var run = log.lastIndexOf("Decoder Version");
        if (run > 0) {
            log = log.substring(run);
        }
        var result = new ArrayList<Logged>();
        for (var line : log.split("\\R")) {
            var console = DECODE_LOG.matcher(line);
            String layer;
            String poc;
            String md5;
            if (console.find()) {
                poc = console.group(1);
                layer = console.group(2);
                md5 = console.group(3);
            } else {
                var parts = line.strip().split("\\s+");
                if (parts.length < 3 || !parts[2].startsWith("MD5:")) {
                    continue;
                }
                layer = parts[0];
                poc = parts[1];
                md5 = parts[2].substring(4);
            }
            var hashes = md5.split(",");
            result.add(new Logged(Integer.parseInt(layer), Integer.parseInt(poc), hashes[0],
                    hashes.length > 1 ? hashes[1] : null, hashes.length > 2 ? hashes[2] : null));
        }
        return result;
    }

    /**
     * Decodes a stream with the engine and returns every output picture, uncropped as the reference
     * decoder logs it, with its layer and picture order count.
     */
    static List<Logged> decodeLogged(byte[] stream, int ols, int threads) {
        var result = new ArrayList<Logged>();
        var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, ols, false,
                frame -> result.add(log(frame)));
        try {
            for (var nal : HevcTestStreams.nalUnits(stream)) {
                engine.decodeNal(nal, 0, nal.length);
            }
            engine.flush();
        } finally {
            engine.close();
        }
        return result;
    }

    private static Logged log(HevcFrame frame) {
        var lumaSize = frame.width * frame.height;
        String y;
        String u;
        String v;
        if (frame.eightBit()) {
            y = hex(java.util.Arrays.copyOf(frame.y8, lumaSize));
            if (frame.cb8 != null) {
                var chromaSize = frame.chromaWidth * frame.chromaHeight;
                u = hex(java.util.Arrays.copyOf(frame.cb8, chromaSize));
                v = hex(java.util.Arrays.copyOf(frame.cr8, chromaSize));
            } else {
                var gray = new byte[((frame.width + 1) / 2) * ((frame.height + 1) / 2)];
                java.util.Arrays.fill(gray, (byte) 128);
                u = hex(gray);
                v = u;
            }
        } else {
            y = hex(shorts(frame.y, frame.width, frame.width, frame.height));
            if (frame.cb != null) {
                u = hex(shorts(frame.cb, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight));
                v = hex(shorts(frame.cr, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight));
            } else {
                var width = (frame.width + 1) / 2;
                var height = (frame.height + 1) / 2;
                var gray = new short[width * height];
                java.util.Arrays.fill(gray, (short) (1 << (frame.sps.bitDepthChroma - 1)));
                u = hex(shorts(gray, width, width, height));
                v = u;
            }
        }
        return new Logged(frame.layerId, frame.outputPoc, y, u, v);
    }

    /**
     * Asserts that the engine output the pictures of the log: the same pictures of each layer, in
     * the log's order per layer and picture order count (the logs list pictures in output or in
     * decoding order), and no other picture.
     */
    static void assertLogged(String name, List<Logged> expected, List<Logged> actual) {
        var expectedByKey = new LinkedHashMap<String, List<Logged>>();
        for (var picture : expected) {
            expectedByKey.computeIfAbsent(picture.layer() + "/" + picture.poc(), _ -> new ArrayList<>()).add(picture);
        }
        var actualByKey = new LinkedHashMap<String, List<Logged>>();
        for (var picture : actual) {
            actualByKey.computeIfAbsent(picture.layer() + "/" + picture.poc(), _ -> new ArrayList<>()).add(picture);
        }
        for (var entry : expectedByKey.entrySet()) {
            var pictures = actualByKey.getOrDefault(entry.getKey(), List.of());
            for (var k = 0; k < entry.getValue().size(); k++) {
                var wanted = entry.getValue().get(k);
                if (k >= pictures.size()) {
                    fail(name + ": no output picture for " + wanted + "; output " + summary(actual));
                }
                if (!wanted.matches(pictures.get(k))) {
                    fail(name + ": picture differs: expected " + wanted + ", got " + pictures.get(k));
                }
            }
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count; output " + summary(actual));
    }

    /**
     * Asserts that the engine output the pictures of the log, matched by their samples alone: the
     * base layer decoded on its own (output layer set 0) takes its picture order counts from the
     * single-layer process, which ignores the picture order count resets of the multi-layer
     * extensions the reference decoder applies, so only the labels of its pictures may differ.
     */
    static void assertLoggedSamples(String name, List<Logged> expected, List<Logged> actual) {
        var remaining = new ArrayList<>(actual);
        for (var wanted : expected) {
            var match = remaining.stream().filter(wanted::matchesSamples).findFirst();
            if (match.isEmpty()) {
                fail(name + ": no output picture with the samples of " + wanted + "; output " + summary(actual));
            }
            remaining.remove(match.get());
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count; output " + summary(actual));
    }

    private static String summary(List<Logged> pictures) {
        var out = new StringBuilder();
        for (var picture : pictures) {
            out.append(picture.layer()).append(':').append(picture.poc()).append(' ');
        }
        return out.toString();
    }

    /**
     * Decodes a stream fed one NAL unit per packet and returns the output pictures in order.
     */
    static List<Output> decode(byte[] stream, HevcDecoderOptions options, Map<Integer, MessageDigest> whole) {
        var result = new ArrayList<Output>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                var layer = frame.sideData().layer().map(VideoLayer::id).orElse(0);
                var planes = planes(frame.picture());
                result.add(new Output(layer, hex(planes[0]), hex(planes[1]), hex(planes[2])));
                if (whole != null) {
                    var digest = whole.computeIfAbsent(layer, _ -> HevcTestStreams.md5());
                    for (var plane : planes) {
                        digest.update(plane);
                    }
                }
            };
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return result;
    }

    static String hex(byte[] data) {
        return HexFormat.of().formatHex(HevcTestStreams.md5().digest(data));
    }

    /**
     * Returns the three planes of a picture as raw samples, a monochrome picture's chroma as
     * mid-gray 4:2:0 planes.
     */
    static byte[][] planes(Picture picture) {
        return switch (picture) {
            case YuvPicture p -> {
                var y = new byte[p.width() * p.height()];
                for (var row = 0; row < p.height(); row++) {
                    System.arraycopy(p.y(), row * p.yStride(), y, row * p.width(), p.width());
                }
                if (p.u() == null) {
                    var gray = new byte[((p.width() + 1) / 2) * ((p.height() + 1) / 2)];
                    java.util.Arrays.fill(gray, (byte) 128);
                    yield new byte[][]{y, gray, gray};
                }
                var u = new byte[p.chromaWidth() * p.chromaHeight()];
                var v = new byte[u.length];
                for (var row = 0; row < p.chromaHeight(); row++) {
                    System.arraycopy(p.u(), row * p.uvStride(), u, row * p.chromaWidth(), p.chromaWidth());
                    System.arraycopy(p.v(), row * p.uvStride(), v, row * p.chromaWidth(), p.chromaWidth());
                }
                yield new byte[][]{y, u, v};
            }
            case Yuv16Picture p -> {
                var y = shorts(p.y(), p.yStride(), p.width(), p.height());
                if (p.u() == null) {
                    var width = (p.width() + 1) / 2;
                    var height = (p.height() + 1) / 2;
                    var gray = new short[width * height];
                    java.util.Arrays.fill(gray, (short) (1 << (p.bitDepth() - 1)));
                    var plane = shorts(gray, width, width, height);
                    yield new byte[][]{y, plane, plane};
                }
                yield new byte[][]{y, shorts(p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight()),
                        shorts(p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight())};
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        };
    }

    private static byte[] shorts(short[] plane, int stride, int width, int height) {
        var out = new byte[width * height * 2];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                var value = plane[row * stride + x];
                out[2 * (row * width + x)] = (byte) value;
                out[2 * (row * width + x) + 1] = (byte) (value >> 8);
            }
        }
        return out;
    }

    static HevcDecoderOptions options(int ols, int threads) {
        return HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).alpha(false).threads(threads)
                .outputLayerSet(ols).build();
    }

    /**
     * Returns the first video parameter set of a stream.
     */
    static HevcVps vps(byte[] stream) {
        for (var start : HevcTestStreams.nalUnits(stream)) {
            if (HevcTestStreams.type(start) == 32) {
                var rbsp = new byte[start.length + H264BitReader.PADDING];
                var removed = new int[start.length];
                var size = (int) NalUnits.unescape(start, 2, start.length - 2, rbsp, removed);
                var reader = new H264BitReader();
                reader.init(rbsp, 0, size);
                return HevcVps.parse(reader);
            }
        }
        throw new AssertionError("no video parameter set");
    }

    /**
     * The output layers the archived logs list beyond those of the specification's output layer
     * sets: the alpha layer of ALPHA_A depends on the base layer and its VPS makes only the highest
     * layer the output layer of output layer set 1 ({@code default_output_layer_idc} 1); its log
     * lists the base layer too, which SHM-12.4 decodes but does not output either.
     */
    private static final Map<String, Integer> LOGGED_NON_OUTPUT = Map.of("ALPHA_A_BBC_1 OLS 1", 0);

    static void assertOutputs(String name, List<Output> expected, List<Output> actual) {
        var count = Math.min(expected.size(), actual.size());
        for (var i = 0; i < count; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": output picture " + i + " differs: expected " + expected.get(i) + ", got "
                        + actual.get(i));
            }
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logged")
    @DisplayName("each output layer set matches the reference decoder's output picture log")
    void outputLayerSets(OutputLayerSet set) throws IOException {
        byte[] stream;
        try (var zip = archive(set.vector())) {
            stream = entry(zip, set.vector().bitstream());
        }
        var expected = expected(set);
        if (set.log().contains("Decoder Version")) {
            // a console log lists every decoded picture, an output picture log only the output ones
            var vps = vps(stream);
            var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[set.index()]];
            var output = new java.util.HashSet<Integer>();
            for (var j = 0; j < list.length; j++) {
                if (vps.outputLayerFlag[set.index()][j]) {
                    output.add(list[j]);
                }
            }
            expected.removeIf(picture -> !output.contains(picture.layer()));
        }
        assertFalse(expected.isEmpty(), "the log lists pictures");
        List<Logged> actual;
        try {
            actual = decodeLogged(stream, set.index(), 1);
        } catch (MediaException exception) {
            throw new AssertionError(set + ": " + exception.getMessage(), exception);
        }
        if (set.index() == 0) {
            assertLoggedSamples(set.toString(), expected, actual);
        } else {
            assertLogged(set.toString(), expected, actual);
        }
    }

    static List<Logged> expected(OutputLayerSet set) {
        var expected = parseLog(set.log());
        var excluded = LOGGED_NON_OUTPUT.get(set.toString());
        if (excluded != null) {
            expected.removeIf(picture -> picture.layer() == excluded);
        }
        return expected;
    }

    static Stream<OutputLayerSet> threadedSets() throws IOException {
        return logged().filter(set -> set.vector().name().matches("SNR_B_IDCC_1|SIM_A_IDCC_1|CGS_E_TECHNICOLOR_1"
                + "|POC_B_Ericsson_1|MVD_A_NOKIA_1|ADAPTRES_A_ERICSSON_1|REFLAYER_D_VIDYO_2"))
                .filter(set -> set.index() > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadedSets")
    @DisplayName("decodes the layers bit-exactly on several threads")
    void outputLayerSetsThreaded(OutputLayerSet set) throws IOException {
        byte[] stream;
        try (var zip = archive(set.vector())) {
            stream = entry(zip, set.vector().bitstream());
        }
        assertLogged(set + " threaded", expected(set), decodeLogged(stream, set.index(), 4));
    }

    private static final Pattern LAYER_MD5 = Pattern.compile("(?:[-_]L|_)(\\d+)(?:_rec)?\\.md5$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MD5_LINE = Pattern.compile("^\\s*([0-9a-fA-F]{32})\\b");

    private static final Pattern OUTPUT_FILE = Pattern.compile("(?:[-_]L|_)(\\d+)\\.(?:yuv|alpha)\\s*$",
            Pattern.CASE_INSENSITIVE);

    @ParameterizedTest(name = "{0}")
    @MethodSource("unlogged")
    @DisplayName("each layer's whole output matches the reference decoder's MD5")
    void wholeLayers(Vector vector) throws IOException {
        assertWholeLayers(vector, 1);
    }

    static Stream<Vector> threadedViews() throws IOException {
        // three views whose third predicts from two inter-layer references of one picture order count
        return unlogged().filter(vector -> vector.name().matches("MVHEVCS_H_LGE_3|MVHEVCS_I_Nokia_3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadedViews")
    @DisplayName("decodes the views bit-exactly on several threads")
    void wholeLayersThreaded(Vector vector) throws IOException {
        assertWholeLayers(vector, 4);
    }

    static void assertWholeLayers(Vector vector, int threads) throws IOException {
        byte[] stream;
        var expected = new TreeMap<Integer, String>();
        try (var zip = archive(vector)) {
            stream = entry(zip, vector.bitstream());
            for (var entry : zip.stream().toList()) {
                if (!entry.getName().toLowerCase().endsWith(".md5")) {
                    continue;
                }
                var file = LAYER_MD5.matcher(entry.getName());
                var text = new String(entry(zip, entry.getName()), StandardCharsets.US_ASCII);
                for (var line : text.split("\\R")) {
                    var hash = MD5_LINE.matcher(line);
                    if (!hash.find()) {
                        continue;
                    }
                    var named = OUTPUT_FILE.matcher(line);
                    if (named.find()) {
                        expected.put(Integer.parseInt(named.group(1)), hash.group(1).toLowerCase());
                    } else if (file.find(0)) {
                        expected.put(Integer.parseInt(file.group(1)), hash.group(1).toLowerCase());
                    }
                }
            }
        }
        assertFalse(expected.isEmpty(), "the archive gives layer MD5s");
        var vps = vps(stream);
        var actual = new LinkedHashMap<Integer, String>();
        for (var index : expected.keySet()) {
            // the MV-HEVC files number their views by layer index, the SHVC files by layer identifier
            var layerId = vector.multiview() && index < vps.maxLayers ? vps.layerIdInNuh[index] : index;
            var ols = firstOutputting(vps, layerId);
            var whole = new LinkedHashMap<Integer, MessageDigest>();
            try {
                decode(stream, options(ols, threads), whole);
            } catch (MediaException exception) {
                throw new AssertionError(vector + " layer " + layerId + ": " + exception.getMessage(), exception);
            }
            var digest = whole.get(layerId);
            actual.put(index, digest == null ? "no output" : HexFormat.of().formatHex(digest.digest()));
        }
        assertEquals(expected, actual, vector + " per-layer MD5s");
    }

    /**
     * Returns the first output layer set that outputs a layer, or the last one.
     */
    static int firstOutputting(HevcVps vps, int layerId) {
        for (var ols = 0; ols < vps.numOutputLayerSets(); ols++) {
            var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[ols]];
            for (var j = 0; j < list.length; j++) {
                if (list[j] == layerId && vps.outputLayerFlag[ols][j]) {
                    return ols;
                }
            }
        }
        return vps.numOutputLayerSets() - 1;
    }

    /** Returns a copy of a recycled picture. */
    static YuvPicture copy(YuvPicture picture) {
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false,
                picture.color());
        for (var row = 0; row < picture.height(); row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), copy.y(), row * copy.yStride(), picture.width());
        }
        return copy;
    }

    @Nested
    @DisplayName("external base layer")
    class Hybrid {
        private static final Vector HYBRID = new Vector("HYBRID_A_QUALCOMM_1", "SHVC", "HYBRID_A_QUALCOMM_1.zip",
                "HYBRID_A_QUALCOMM_1.bit");

        @ParameterizedTest(name = "on {0} threads")
        @org.junit.jupiter.params.provider.ValueSource(ints = {1, 4})
        @DisplayName("HYBRID_A decodes its enhancement layer over the base layer pictures the archive supplies")
        void hybrid(int threads) throws IOException {
            byte[] stream;
            byte[] base;
            String log;
            try (var zip = archive(HYBRID)) {
                stream = entry(zip, HYBRID.bitstream());
                base = entry(zip, "BaseLayerSequence.yuv");
                log = new String(entry(zip, "HYBRID_A_QUALCOMM_1-OLS1.opl"), StandardCharsets.UTF_8);
            }
            var result = new ArrayList<Logged>();
            var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, 1, false,
                    frame -> result.add(log(frame)));
            var supplied = new int[1];
            // the archive keeps the base layer pictures in output order, as the reference decoder reads them
            engine.externalBaseLayer((format, pocLsb) -> {
                var luma = format.width() * format.height();
                var chroma = luma / 4;
                var start = (long) pocLsb * (luma + 2 * chroma);
                if (start + luma + 2L * chroma > base.length) {
                    return null;
                }
                var frame = new HevcFrame(format.width(), format.height(), format.chromaFormatIdc(), true);
                System.arraycopy(base, (int) start, frame.y8, 0, luma);
                System.arraycopy(base, (int) start + luma, frame.cb8, 0, chroma);
                System.arraycopy(base, (int) start + luma + chroma, frame.cr8, 0, chroma);
                var irap = supplied[0]++ == 0;
                return new HevcDecoderEngine.ExternalBaseLayer.Picture(frame, irap, HevcNal.IDR_W_RADL);
            });
            try {
                for (var nal : HevcTestStreams.nalUnits(stream)) {
                    engine.decodeNal(nal, 0, nal.length);
                }
                engine.flush();
            } finally {
                engine.close();
            }
            assertLogged(HYBRID + " OLS 1", parseLog(log), result);
        }
    }

    @Nested
    @DisplayName("alpha auxiliary layer")
    class Alpha {
        @Test
        @DisplayName("ALPHA_A attaches the alpha layer, interpreted by its alpha channel information, to the frames")
        void attached() throws IOException {
            var vector = vectors().stream().filter(v -> v.name().equals("ALPHA_A_BBC_1")).findFirst().orElseThrow();
            byte[] stream;
            try (var zip = archive(vector)) {
                stream = entry(zip, vector.bitstream());
            }
            // the alpha layer alone (output layer set 1 outputs it as monochrome frames) and the color
            // layer alone (output layer set 0), then the color layer with its alpha attached
            var lumas = new ArrayList<byte[]>();
            var separate = new ArrayList<VideoFrame>();
            for (var ols = 0; ols < 2; ols++) {
                var target = ols == 0 ? null : lumas;
                try (var decoder = new HevcDecoder(HevcTestStreams.track(), options(ols, 1))) {
                    Consumer<VideoFrame> sink = frame -> {
                        if (target != null) {
                            assertEquals(VideoLayer.Type.ALPHA, frame.sideData().layer().orElseThrow().type());
                            target.add(planes(frame.picture())[0]);
                        } else {
                            separate.add(new VideoFrame(copy((YuvPicture) frame.picture()), frame.pts(),
                                    frame.duration(), frame.keyframe()));
                        }
                    };
                    for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                        decoder.decode(HevcTestStreams.packet(nal), sink);
                    }
                    decoder.flush(sink);
                }
            }
            var attached = new ArrayList<byte[]>();
            var colors = new ArrayList<String>();
            var attachOptions = options(0, 1).toBuilder().alpha(true).build();
            try (var decoder = new HevcDecoder(HevcTestStreams.track(), attachOptions)) {
                Consumer<VideoFrame> sink = frame -> {
                    var picture = (YuvPicture) frame.picture();
                    assertTrue(picture.hasAlpha(), "the frame carries alpha");
                    assertEquals(VideoLayer.Type.PRIMARY, frame.sideData().layer().map(VideoLayer::type)
                            .orElse(VideoLayer.Type.PRIMARY));
                    var alpha = new byte[picture.width() * picture.height()];
                    for (var row = 0; row < picture.height(); row++) {
                        System.arraycopy(picture.alpha(), row * picture.alphaStride(), alpha, row * picture.width(),
                                picture.width());
                    }
                    attached.add(alpha);
                    colors.add(hex(planes(picture)[0]));
                };
                for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                    decoder.decode(HevcTestStreams.packet(nal), sink);
                }
                decoder.flush(sink);
            }
            assertEquals(lumas.size(), attached.size(), "one alpha plane per frame");
            assertEquals(separate.size(), attached.size(), "one frame per color picture");
            assertFalse(attached.isEmpty());
            for (var i = 0; i < attached.size(); i++) {
                var expected = lumas.get(i).clone();
                for (var k = 0; k < expected.length; k++) {
                    // transparent 0, opaque 255, clipping of type 0: values above 127 are opaque
                    expected[k] = (byte) ((expected[k] & 0xFF) > 127 ? 255 : 0);
                }
                assertEquals(hex(expected), hex(attached.get(i)), "alpha of frame " + i);
                assertEquals(hex(planes(separate.get(i).picture())[0]), colors.get(i), "color of frame " + i);
            }
        }
    }
}
