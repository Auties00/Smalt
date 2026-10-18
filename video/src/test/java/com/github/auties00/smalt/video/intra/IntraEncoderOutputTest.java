package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the bytes the ProRes and DNxHD encoders produce for deterministic synthetic pictures, so
 * that changes to their rate control, entropy coding and scheduling keep the bitstreams exactly
 * as they are: the CRC-32 of every packet of a short sequence is compared with the recorded value.
 * A changed value is either a regression or a deliberate change of the encoders' output, which
 * then re-records the table.
 */
@DisplayName("intra encoder output")
class IntraEncoderOutputTest {
    static final Rational FRAME_RATE = new Rational(25, 1);

    /**
     * CRC-32 of the concatenated packets of each case.
     */
    static final Map<String, Long> EXPECTED = Map.ofEntries(
            Map.entry("prores PROXY", 2581018022L),
            Map.entry("prores LT", 212698405L),
            Map.entry("prores STANDARD", 1044587644L),
            Map.entry("prores HQ", 4094204337L),
            Map.entry("prores HQ slices of 1", 748984200L),
            Map.entry("prores HQ fixed index", 3061001836L),
            Map.entry("prores HQ low rate", 3417216548L),
            Map.entry("prores HQ interlaced", 833065365L),
            Map.entry("prores HQ from 8-bit 4:2:2", 3293939684L),
            Map.entry("prores HQ from 8-bit 4:2:0", 1196856802L),
            Map.entry("prores HQ from RGBA", 778113574L),
            Map.entry("prores HQ from RGBA in FCC", 163928508L),
            Map.entry("prores 4444 from 12-bit 4:4:4 with alpha", 1065816919L),
            Map.entry("prores 4444 XQ from 12-bit 4:4:4", 3642980310L),
            Map.entry("dnxhr DNXHR_LB", 624004291L),
            Map.entry("dnxhr DNXHR_SQ", 1604709839L),
            Map.entry("dnxhr DNXHR_HQ", 2403206705L),
            Map.entry("dnxhr HQX from 10-bit 4:2:2", 552347928L),
            Map.entry("dnxhr HQ 4:2:0", 3252516500L),
            Map.entry("dnxhr 444 from 10-bit 4:4:4", 3683465218L),
            Map.entry("dnxhr 444 RGB from RGBA", 1232702390L),
            Map.entry("dnxhr 444 with alpha", 3281261196L),
            Map.entry("dnxhr 444 with lossless alpha", 3388685127L),
            Map.entry("dnxhd 1080p", 3512493680L)
    );

    static int next(int state) {
        return state * 1664525 + 1013904223;
    }

    static void fill(short[] plane, int stride, int width, int height, int depth, int frame, int seed, int detail) {
        var state = seed * 7919 + frame * 104729;
        var max = (1 << depth) - 1;
        var low = 16 << (depth - 8);
        var span = 219 << (depth - 8);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                state = next(state);
                var noise = ((state >>> 16) & 0xFF) - 128;
                var edge = ((x / 13 + y / 11 + frame) & 1) * 40;
                var value = low + span * ((x * 3 + y * 5 + frame * 7) % 256) / 255 + (noise * detail >> 4) + (edge << (depth - 8));
                plane[y * stride + x] = (short) Math.clamp(value, 0, max);
            }
        }
    }

    static Picture yuv16(int width, int height, ChromaFormat format, int depth, int frame, boolean alpha) {
        var chromaWidth = format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var chromaHeight = format == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
        var y = new short[width * height];
        var u = new short[chromaWidth * chromaHeight];
        var v = new short[chromaWidth * chromaHeight];
        fill(y, width, width, height, depth, frame, 1, 6 + 10 * frame);
        fill(u, chromaWidth, chromaWidth, chromaHeight, depth, frame, 2, 3);
        fill(v, chromaWidth, chromaWidth, chromaHeight, depth, frame, 3, 3);
        var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(depth)
                .color(ColorInfo.BT709_LIMITED).luma(y, width).chroma(u, v, chromaWidth);
        if (alpha) {
            var a = new short[width * height];
            for (var i = 0; i < a.length; i++) {
                a[i] = (short) ((i % width + frame * 5) * ((1 << depth) - 1) / Math.max(1, width - 1) / (1 + i / 997 % 3));
            }
            builder.alpha(a, width);
        }
        return builder.build();
    }

    static Picture yuv8(int width, int height, ChromaFormat format, int frame) {
        var wide = (Yuv16Picture) yuv16(width, height, format, 8, frame, false);
        var chromaWidth = wide.chromaWidth();
        var chromaHeight = wide.chromaHeight();
        var y = new byte[width * height];
        var u = new byte[chromaWidth * chromaHeight];
        var v = new byte[chromaWidth * chromaHeight];
        for (var i = 0; i < y.length; i++) {
            y[i] = (byte) wide.y()[i];
        }
        for (var i = 0; i < u.length; i++) {
            u[i] = (byte) wide.u()[i];
            v[i] = (byte) wide.v()[i];
        }
        return YuvPicture.builder().size(width, height).chromaFormat(format).color(ColorInfo.BT709_LIMITED)
                .luma(y, width).chroma(u, v, chromaWidth).build();
    }

    static Picture rgba(int width, int height, int frame) {
        var pixels = new byte[width * height * 4];
        var state = 99 + frame;
        for (var i = 0; i < pixels.length; i += 4) {
            state = next(state);
            var x = i / 4 % width;
            var y = i / 4 / width;
            pixels[i] = (byte) (x * 255 / width + ((state >>> 20) & 15));
            pixels[i + 1] = (byte) (y * 255 / height + ((state >>> 16) & 15));
            pixels[i + 2] = (byte) ((x + y) * 255 / (width + height) + ((state >>> 12) & 15));
            pixels[i + 3] = (byte) (x * 255 / width);
        }
        return RgbaPicture.of(width, height, width * 4, pixels, true);
    }

    static long crc(Supplier<VideoEncoder> encoder, List<Picture> pictures) {
        var crc = new CRC32();
        var packets = new ArrayList<Packet>();
        try (var e = encoder.get()) {
            var pts = 0L;
            for (var picture : pictures) {
                e.encode(new VideoFrame(picture, pts, 1, true), packets::add);
                pts += 1;
            }
            e.flush(packets::add);
        }
        packets.forEach(packet -> crc.update(packet.data()));
        return crc.getValue();
    }

    static List<Picture> sequence(int frames, java.util.function.IntFunction<Picture> picture) {
        var pictures = new ArrayList<Picture>();
        for (var f = 0; f < frames; f++) {
            pictures.add(picture.apply(f));
        }
        return pictures;
    }

    record Case(String name, Supplier<VideoEncoder> encoder, List<Picture> pictures) {
        @Override
        public String toString() {
            return name;
        }
    }

    static ProResEncoderOptions pro(ProResProfile profile) {
        return ProResEncoderOptions.of(profile).toBuilder().frameRate(FRAME_RATE).threads(1).build();
    }

    static DnxhdEncoderOptions dnx(DnxhdProfile profile) {
        return DnxhdEncoderOptions.of(profile).toBuilder().frameRate(FRAME_RATE).threads(1).build();
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        var w = 99;
        var h = 67;
        var ten422 = sequence(4, f -> yuv16(w, h, ChromaFormat.YUV422, 10, f, false));
        for (var profile : List.of(ProResProfile.PROXY, ProResProfile.LT, ProResProfile.STANDARD, ProResProfile.HQ)) {
            cases.add(new Case("prores " + profile, () -> new ProResEncoder(w, h, pro(profile)), ten422));
        }
        cases.add(new Case("prores HQ slices of 1", () -> new ProResEncoder(w, h, pro(ProResProfile.HQ).toBuilder()
                .sliceSize(1).build()), ten422));
        cases.add(new Case("prores HQ fixed index", () -> new ProResEncoder(w, h,
                pro(ProResProfile.HQ).toBuilder().quantizationIndex(7).build()), ten422));
        cases.add(new Case("prores HQ low rate", () -> new ProResEncoder(w, h,
                pro(ProResProfile.HQ).toBuilder().bitrate(2_000_000).build()), ten422));
        cases.add(new Case("prores HQ interlaced", () -> new ProResEncoder(w, h,
                pro(ProResProfile.HQ).toBuilder().fieldOrder(FieldOrder.TOP_FIRST).build()), ten422));
        cases.add(new Case("prores HQ from 8-bit 4:2:2", () -> new ProResEncoder(w, h, pro(ProResProfile.HQ)),
                sequence(3, f -> yuv8(w, h, ChromaFormat.YUV422, f))));
        cases.add(new Case("prores HQ from 8-bit 4:2:0", () -> new ProResEncoder(w, h, pro(ProResProfile.HQ)),
                sequence(3, f -> yuv8(w, h, ChromaFormat.YUV420, f))));
        cases.add(new Case("prores HQ from RGBA", () -> new ProResEncoder(w, h, pro(ProResProfile.HQ)),
                sequence(3, f -> rgba(w, h, f))));
        cases.add(new Case("prores HQ from RGBA in FCC", () -> new ProResEncoder(w, h, pro(ProResProfile.HQ).toBuilder()
                .color(ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.FCC)).build()),
                sequence(3, f -> rgba(w, h, f))));
        cases.add(new Case("prores 4444 from 12-bit 4:4:4 with alpha", () -> new ProResEncoder(w, h,
                pro(ProResProfile.PRORES_4444)), sequence(3, f -> yuv16(w, h, ChromaFormat.YUV444, 12, f, true))));
        cases.add(new Case("prores 4444 XQ from 12-bit 4:4:4", () -> new ProResEncoder(w, h,
                pro(ProResProfile.PRORES_4444_XQ)), sequence(3, f -> yuv16(w, h, ChromaFormat.YUV444, 12, f, false))));
        var dw = 98;
        var eight422 = sequence(4, f -> yuv8(dw, h, ChromaFormat.YUV422, f));
        for (var profile : List.of(DnxhdProfile.DNXHR_LB, DnxhdProfile.DNXHR_SQ, DnxhdProfile.DNXHR_HQ)) {
            cases.add(new Case("dnxhr " + profile, () -> new DnxhdEncoder(dw, h, dnx(profile)), eight422));
        }
        cases.add(new Case("dnxhr HQX from 10-bit 4:2:2", () -> new DnxhdEncoder(dw, h, dnx(DnxhdProfile.DNXHR_HQX)), sequence(4, f -> yuv16(dw, h, ChromaFormat.YUV422, 10, f, false))));
        cases.add(new Case("dnxhr HQ 4:2:0", () -> new DnxhdEncoder(dw, 68,
                dnx(DnxhdProfile.DNXHR_HQ).toBuilder().chromaFormat(ChromaFormat.YUV420).build()),
                sequence(3, f -> yuv8(dw, 68, ChromaFormat.YUV420, f))));
        cases.add(new Case("dnxhr 444 from 10-bit 4:4:4", () -> new DnxhdEncoder(dw, h, dnx(DnxhdProfile.DNXHR_444)),
                sequence(3, f -> yuv16(dw, h, ChromaFormat.YUV444, 10, f, false))));
        cases.add(new Case("dnxhr 444 RGB from RGBA", () -> new DnxhdEncoder(dw, h,
                dnx(DnxhdProfile.DNXHR_444).toBuilder().rgb(true).build()), sequence(3, f -> rgba(dw, h, f))));
        cases.add(new Case("dnxhr 444 with alpha", () -> new DnxhdEncoder(dw, h,
                dnx(DnxhdProfile.DNXHR_444).toBuilder().alpha(true).build()),
                sequence(3, f -> yuv16(dw, h, ChromaFormat.YUV444, 10, f, true))));
        cases.add(new Case("dnxhr 444 with lossless alpha", () -> new DnxhdEncoder(dw, h,
                dnx(DnxhdProfile.DNXHR_444).toBuilder().alpha(true).losslessAlpha(true).build()),
                sequence(3, f -> yuv16(dw, h, ChromaFormat.YUV444, 10, f, true))));
        cases.add(new Case("dnxhd 1080p", () -> new DnxhdEncoder(1920, 1080, dnx(DnxhdProfile.DNXHD)),
                sequence(2, f -> yuv8(1920, 1080, ChromaFormat.YUV422, f))));
        return cases;
    }

    static Stream<Case> table() {
        return cases().stream();
    }

    static Map<String, Long> compute() {
        var values = new LinkedHashMap<String, Long>();
        for (var c : cases()) {
            values.put(c.name(), crc(c.encoder(), c.pictures()));
        }
        return values;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    @DisplayName("produces the recorded bytes")
    void recorded(Case c) {
        assertEquals(EXPECTED.get(c.name()), crc(c.encoder(), c.pictures()));
    }

    @Test
    @DisplayName("produces the same bytes on every thread count")
    void threads() {
        var w = 320;
        var h = 180;
        var pictures = sequence(3, f -> yuv16(w, h, ChromaFormat.YUV422, 10, f, false));
        assertEquals(crc(() -> new ProResEncoder(w, h, pro(ProResProfile.HQ)), pictures),
                crc(() -> new ProResEncoder(w, h, pro(ProResProfile.HQ).toBuilder().threads(3).build()), pictures));
        var eight = sequence(3, f -> yuv8(w, h, ChromaFormat.YUV422, f));
        assertEquals(crc(() -> new DnxhdEncoder(w, h, dnx(DnxhdProfile.DNXHR_HQ)), eight),
                crc(() -> new DnxhdEncoder(w, h, dnx(DnxhdProfile.DNXHR_HQ).toBuilder().threads(3).build()), eight));
    }
}
