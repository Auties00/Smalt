package com.github.auties00.smalt.image.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.internal.jbig.JbigDecoder;
import com.github.auties00.smalt.image.internal.lerc.LercDecoder;
import com.github.auties00.smalt.image.internal.xz.XzDecoder;
import com.github.auties00.smalt.image.internal.zstd.ZstdDecoder;
import com.github.auties00.smalt.image.internal.zstd.ZstdEncoder;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Fuzz targets of the image decoders of this module, one per format. Each input is probed, decoded at full and
 * reduced size, read as an animation and as pages (see {@link FuzzMedia#image}). The format-specific entry
 * points have targets of their own: the JPEG 2000 channel decoder ({@link Jp2Decoder#decodeChannels}), the JPEG XL
 * floating-point decoder ({@link JxlDecoder#decodeFloat}) and the JPEG XL to JPEG reconstruction
 * ({@link JxlDecoder#reconstructJpeg}), and the general-purpose decoders of the TIFF compressions: Zstandard
 * (whose output is also encoded and decoded back), XZ, LERC and JBIG.
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
    /** The largest output of the general-purpose decoder targets. */
    private static final int MAX_OUTPUT = 1 << 20;

    /**
     * An action of a general-purpose decoder, which reports invalid data with a {@link DataFormatException}.
     */
    private interface Decoding<T> {
        T run() throws DataFormatException;
    }

    private static <T> T malformed(Decoding<T> action) {
        try {
            return action.run();
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed(exception.getMessage(), exception);
        }
    }

    @FuzzTest
    void zstd(byte[] data) {
        var run = Fuzz.start("zstd");
        var out = new byte[MAX_OUTPUT];
        run.value("decompress", () -> malformed(() -> new ZstdDecoder().decompress(data, 0, data.length, out, 0,
                out.length))).ifPresent(produced -> {
            run.output();
            int size = produced;
            var frame = ZstdEncoder.compress(out, 0, size, 1 + (data.length == 0 ? 0 : data[0] & 7));
            var back = new byte[size];
            int decoded = malformed(() -> new ZstdDecoder().decompress(frame, 0, frame.length, back, 0, size));
            if (decoded != size || !Arrays.equals(out, 0, size, back, 0, size)) {
                throw new AssertionError("a Zstandard round trip changed " + size + " bytes");
            }
        });
    }

    @FuzzTest
    void xz(byte[] data) {
        var run = Fuzz.start("xz");
        var out = new byte[MAX_OUTPUT];
        run.value("decompress", () -> malformed(() -> new XzDecoder().decompress(data, 0, data.length, out, 0,
                out.length))).ifPresent(size -> run.output());
    }

    @FuzzTest
    void lerc(byte[] data) {
        var run = Fuzz.start("lerc");
        var decoder = new LercDecoder();
        run.value("info", () -> malformed(() -> decoder.info(data, 0, data.length))).ifPresent(info -> {
            var values = (long) info.rows() * info.columns() * info.depth() * info.bands();
            if (values > Fuzz.MAX_CONVERTED_PIXELS) {
                run.skip("more values than the target decodes");
                return;
            }
            var decoded = run.step("decode", () -> malformed(() -> {
                decoder.decode(data, 0, data.length, info, new double[(int) values],
                        new byte[info.rows() * info.columns() * info.bands()]);
                return null;
            }));
            if (decoded) {
                run.output();
            }
        });
    }

    @FuzzTest
    void jbig(byte[] data) {
        var run = Fuzz.start("jbig");
        run.value("decode", () -> malformed(() -> new JbigDecoder().decode(data, 0, data.length,
                Fuzz.MAX_CONVERTED_PIXELS))).ifPresent(image -> {
            for (var plane : image.planes()) {
                if (plane.length != (image.width() + 7) / 8 * image.height()) {
                    throw new AssertionError("a JBIG plane of " + plane.length + " bytes for " + image.width() + "x"
                            + image.height());
                }
            }
            run.output();
        });
    }
}
