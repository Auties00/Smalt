package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how a HEIF JPEG 2000 item's header boxes and properties choose the color model of its components (the
 * enumerated color space of a {@code colr} box, an ICC profile in the item header or as an item property, an
 * {@code nclx} matrix, subsampling and the codestream's own component transform), and that a codestream with a
 * damaged size marker is refused.
 */
@DisplayName("HEIF JPEG 2000 item header boxes")
class HeicJpeg2000BoxesTest {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static Jp2Channel channel(int subsampling, int association, int seed) {
        var w = (WIDTH + subsampling - 1) / subsampling;
        var h = (HEIGHT + subsampling - 1) / subsampling;
        var samples = new int[w * h];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = (index * 37 + seed * 11) & 0xFF;
        }
        return new Jp2Channel(0, 0, w, h, subsampling, subsampling, 8, false, Jp2ChannelType.COLOR, association,
                samples);
    }

    private static byte[] codestream(boolean transform, int... subsamplings) {
        var channels = new ArrayList<Jp2Channel>();
        for (var index = 0; index < subsamplings.length; index++) {
            channels.add(channel(subsamplings[index], index + 1, index));
        }
        var space = channels.size() >= 3 ? Jp2ColorSpace.SRGB : Jp2ColorSpace.GRAYSCALE;
        var image = new Jp2Image(0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT, 0, List.copyOf(channels), space, Optional.empty(),
                Optional.empty(), Optional.empty());
        return Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                .colorTransform(transform).build());
    }

    private static byte[] j2kH(byte[]... boxes) {
        var out = new ByteArrayOutputStream();
        for (var box : boxes) {
            out.writeBytes(box);
        }
        return HeifBuilder.box("j2kH", out.toByteArray());
    }

    private static byte[] colr(int enumerated) {
        return HeifBuilder.box("colr", new byte[]{1, 0, 0, (byte) (enumerated >>> 24), (byte) (enumerated >>> 16),
                (byte) (enumerated >>> 8), (byte) enumerated});
    }

    private static byte[] colrIcc(byte[] profile) {
        var payload = new byte[3 + profile.length];
        payload[0] = 2;
        System.arraycopy(profile, 0, payload, 3, profile.length);
        return HeifBuilder.box("colr", payload);
    }

    private static Picture decode(byte[] codestream, HeifBuilder.Property... extra) {
        return decode(WIDE, codestream, extra);
    }

    private static Picture decode(ImageDecoderOptions options, byte[] codestream, HeifBuilder.Property... extra) {
        var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
        var properties = new ArrayList<HeifBuilder.Property>();
        properties.add(plain(HeifBuilder.ispe(WIDTH, HEIGHT)));
        properties.addAll(Arrays.asList(extra));
        builder.primary(builder.item("j2k1", codestream, properties.toArray(HeifBuilder.Property[]::new)));
        return HeicDecoder.decode(MediaSource.of(builder.build()), options).picture();
    }

    /**
     * {@return whether a decoded picture holds Y'CbCr samples rather than RGB ones in identity-matrix planes}
     */
    private static boolean ycc(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.color().matrix() != MatrixCoefficients.IDENTITY;
            case Yuv16Picture yuv -> yuv.color().matrix() != MatrixCoefficients.IDENTITY;
            default -> false;
        };
    }

    @Test
    @DisplayName("an enumerated sYCC space codes Y'CbCr and sRGB codes RGB, the first colr box winning")
    void enumerated() {
        var codestream = codestream(false, 1, 1, 1);
        assertTrue(ycc(decode(codestream, plain(j2kH(colr(18), colr(16))))), "sYCC");
        assertFalse(ycc(decode(codestream, plain(j2kH(colr(16), colr(18))))), "sRGB");
        assertFalse(ycc(decode(codestream, plain(j2kH(HeifBuilder.box("colr", new byte[]{1, 0}))))),
                "a colr box too short to hold its space");
    }

    @Test
    @DisplayName("without a declared space, subsampling or an nclx matrix codes Y'CbCr")
    void inferred() {
        assertTrue(ycc(decode(codestream(false, 1, 2, 2))), "4:2:0");
        assertTrue(ycc(decode(codestream(false, 1, 1, 1), plain(HeifBuilder.nclx(1, 13, 1, true)))), "BT.709");
        assertFalse(ycc(decode(codestream(false, 1, 1, 1), plain(HeifBuilder.nclx(1, 13, 0, true)))), "identity");
        assertFalse(ycc(decode(codestream(true, 1, 1, 1), plain(HeifBuilder.nclx(1, 13, 1, true)))),
                "the codestream's own component transform");
        var gray = decode(codestream(false, 1));
        assertEquals(ChromaFormat.MONOCHROME, gray instanceof YuvPicture yuv ? yuv.chromaFormat()
                : ((Yuv16Picture) gray).chromaFormat());
    }

    @Test
    @DisplayName("a CMYK ICC profile in the item header or as a property makes four channels inks")
    void inks() {
        var codestream = codestream(false, 1, 1, 1, 1);
        var keep = ImageDecoderOptions.builder().keepChannels(true).build();
        var header = decode(keep, codestream, plain(j2kH(colrIcc(SyntheticProfiles.cmyk()))));
        var property = decode(keep, codestream, plain(HeifBuilder.icc(SyntheticProfiles.cmyk())));
        for (var picture : List.of(header, property)) {
            assertTrue(picture instanceof com.github.auties00.smalt.image.ChannelPicture channels
                    && channels.layout().colorModel() == com.github.auties00.smalt.image.ColorModel.CMYK, "CMYK inks");
        }
        var rgbProfile = com.github.auties00.smalt.image.color.ColorProfile.srgb().toByteArray();
        var three = codestream(false, 1, 1, 1);
        for (var picture : List.of(decode(keep, three, plain(HeifBuilder.icc(rgbProfile))),
                decode(keep, three, plain(j2kH(colrIcc(rgbProfile), HeifBuilder.box("colr", new byte[]{2, 0})))))) {
            assertFalse(picture instanceof com.github.auties00.smalt.image.ChannelPicture channels
                    && channels.layout().colorModel() == com.github.auties00.smalt.image.ColorModel.CMYK,
                    "an RGB profile makes no inks");
        }
    }

    @Test
    @DisplayName("refuses a size marker that declares more components than it holds")
    void components() {
        var good = codestream(false, 1);
        var many = good.clone();
        many[40] = 0x10;
        assertThrows(MediaException.Malformed.class, () -> decode(many));
    }

    @Test
    @DisplayName("refuses an item that does not start with a valid codestream size marker")
    void malformed() {
        var good = codestream(false, 1);
        assertThrows(MediaException.Malformed.class, () -> decode(Arrays.copyOf(good, 41)));
        for (var index : new int[]{0, 1, 2, 3}) {
            var bad = good.clone();
            bad[index] = 0;
            assertThrows(MediaException.Malformed.class, () -> decode(bad), "marker byte " + index);
        }
        var noComponents = good.clone();
        noComponents[40] = 0;
        noComponents[41] = 0;
        assertThrows(MediaException.Malformed.class, () -> decode(noComponents));
        var offset = good.clone();
        System.arraycopy(good, 8, offset, 16, 4);
        assertThrows(MediaException.Malformed.class, () -> decode(offset), "an origin past the width");
        var longSiz = good.clone();
        longSiz[4] = (byte) 0x7F;
        assertThrows(MediaException.Malformed.class, () -> decode(longSiz), "a size marker past the item");
    }
}
