package com.github.auties00.smalt.image.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;

/**
 * Fuzz targets of the image decoders of this module, one per format. Each input is probed, decoded at full and
 * reduced size, read as an animation and as pages (see {@link FuzzMedia#image}). The format-specific entry
 * points have targets of their own: the JPEG 2000 channel decoder ({@link Jp2Decoder#decodeChannels}), the JPEG XL
 * floating-point decoder ({@link JxlDecoder#decodeFloat}) and the JPEG XL to JPEG reconstruction
 * ({@link JxlDecoder#reconstructJpeg}).
 */
class ImageFuzzTest {
    @FuzzTest
    void jpeg(byte[] data) {
        FuzzMedia.image(ImageFormat.JPEG, data);
    }

    @FuzzTest
    void png(byte[] data) {
        FuzzMedia.image(ImageFormat.PNG, data);
    }

    @FuzzTest
    void gif(byte[] data) {
        FuzzMedia.image(ImageFormat.GIF, data);
    }

    @FuzzTest
    void webp(byte[] data) {
        FuzzMedia.image(ImageFormat.WEBP, data);
    }

    @FuzzTest
    void bmp(byte[] data) {
        FuzzMedia.image(ImageFormat.BMP, data);
    }

    @FuzzTest
    void tiff(byte[] data) {
        FuzzMedia.image(ImageFormat.TIFF, data);
    }

    @FuzzTest
    void ico(byte[] data) {
        FuzzMedia.image(ImageFormat.ICO, data);
    }

    @FuzzTest
    void jp2(byte[] data) {
        FuzzMedia.image(ImageFormat.JP2, data);
    }

    @FuzzTest
    void jxl(byte[] data) {
        FuzzMedia.image(ImageFormat.JXL, data);
    }

    @FuzzTest
    void jp2Channels(byte[] data) {
        var run = Fuzz.start("jp2Channels");
        // the last byte, which mutations of it explore, picks the reduction and the quality layers
        var selector = data.length == 0 ? 0 : data[data.length - 1] & 0xFF;
        var options = Jp2DecoderOptions.builder().limits(Fuzz.LIMITS).reduction(selector % 3);
        if ((selector & 4) != 0) {
            options.qualityLayers(1 + (selector >>> 3) % 4);
        }
        run.value("channels", () -> Jp2Decoder.decodeChannels(MediaSource.of(data), options.build()))
                .ifPresent(image -> {
                    for (var channel : image.channels()) {
                        // a sample keeps the precision and signedness the file declares
                        if (channel.precision() < 31) {
                            var min = channel.signed() ? -(1L << (channel.precision() - 1)) : 0;
                            var max = channel.signed() ? (1L << (channel.precision() - 1)) - 1
                                    : (1L << channel.precision()) - 1;
                            for (var sample : channel.samples()) {
                                if (sample < min || sample > max) {
                                    throw new AssertionError("sample " + sample + " outside the " + channel.precision()
                                            + "-bit range of " + channel);
                                }
                            }
                        }
                    }
                    run.output();
                });
    }

    @FuzzTest
    void jxlFloat(byte[] data) {
        var run = Fuzz.start("jxlFloat");
        run.value("decode", () -> JxlDecoder.decodeFloat(MediaSource.of(data), ImageDecoderOptions.builder()
                .limits(Fuzz.LIMITS)
                .build())).ifPresent(image -> {
                    // the record checks the plane count and sizes against the canvas
                    for (var type : image.extraChannelTypes()) {
                        image.extraChannel(type).orElseThrow();
                    }
                    run.output();
                });
    }

    @FuzzTest
    void jxlJpeg(byte[] data) {
        var run = Fuzz.start("jxlJpeg");
        var options = ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build();
        run.value("reconstruct", () -> JxlDecoder.reconstructJpeg(MediaSource.of(data), options).orElse(null))
                .ifPresent(jpeg -> {
                    run.output();
                    run.step("decode the JPEG", () -> Images.decode(MediaSource.of(jpeg), options));
                });
    }
}
