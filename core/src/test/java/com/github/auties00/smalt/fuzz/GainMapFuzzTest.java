package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

import java.util.Optional;

/**
 * Fuzz targets of the gain map metadata parsers: the binary ISO 21496-1 structure and the {@code hdrgm} XMP
 * properties. Besides the exception contract they check that parsed metadata serializes and parses back to close
 * values, and that a small image renders with it.
 */
class GainMapFuzzTest {
    @FuzzTest
    void iso(byte[] data) {
        var run = Fuzz.start("gainmap-iso");
        var parsed = run.value("parse", () -> GainMapMetadata.parse(data));
        parsed.ifPresent(metadata -> {
            var again = GainMapMetadata.parse(metadata.toByteArray());
            same(metadata, again);
            render(metadata);
            run.output();
        });
    }

    @FuzzTest
    void xmp(byte[] data) {
        var run = Fuzz.start("gainmap-xmp");
        var packet = run.value("packet", () -> XmpPacket.parse(data));
        if (packet.isEmpty()) {
            return;
        }
        var parsed = run.value("metadata", () -> GainMapMetadata.fromXmp(packet.get())).flatMap(value -> value);
        parsed.ifPresent(metadata -> {
            var again = GainMapMetadata.fromXmp(XmpPacket.parse(metadata.toXmp().toBytes())).orElseThrow();
            same(metadata, again);
            render(metadata);
            run.output();
        });
    }

    /** Checks that two metadata hold close values. */
    private static void same(GainMapMetadata expected, GainMapMetadata actual) {
        if (expected.isMultichannel() != actual.isMultichannel() || expected.isBaseBrighter() != actual.isBaseBrighter()
                || !close(expected.baseHeadroom(), actual.baseHeadroom())
                || !close(expected.alternateHeadroom(), actual.alternateHeadroom())) {
            throw new IllegalStateException("metadata changed: " + expected + " became " + actual);
        }
        for (var channel = 0; channel < 3; channel++) {
            var a = expected.channel(channel);
            var b = actual.channel(channel);
            if (!close(a.minimum(), b.minimum()) || !close(a.maximum(), b.maximum()) || !close(a.gamma(), b.gamma())
                    || !close(a.baseOffset(), b.baseOffset()) || !close(a.alternateOffset(), b.alternateOffset())) {
                throw new IllegalStateException("channel " + channel + " changed: " + a + " became " + b);
            }
        }
    }

    /** {@return whether two values agree to 32-bit fraction precision} */
    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(a));
    }

    /** Renders a 4x4 image with a gain map of the metadata. */
    private static void render(GainMapMetadata metadata) {
        var base = RgbaPicture.allocate(4, 4, false);
        for (var i = 0; i < base.pixels().length; i++) {
            base.pixels()[i] = (byte) (i * 37);
        }
        var plane = new byte[4];
        for (var i = 0; i < plane.length; i++) {
            plane[i] = (byte) (i * 85);
        }
        var image = YuvPicture.builder().size(2, 2).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                .luma(plane, 2).build();
        var decoded = new DecodedImage(base, ImageFormat.JPEG, Orientation.NORMAL, 4, 4, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        var rendered = GainMaps.apply(decoded, new GainMap(metadata, image)).picture();
        if (rendered.width() != 4 || rendered.height() != 4) {
            throw new IllegalStateException("rendered " + rendered.width() + "x" + rendered.height());
        }
    }
}
