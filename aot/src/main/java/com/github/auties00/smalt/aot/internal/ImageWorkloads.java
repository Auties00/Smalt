package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.gif.GifAnimationEncoder;
import com.github.auties00.smalt.image.gif.GifDecoder;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.gif.GifEncoderOptions;
import com.github.auties00.smalt.image.ico.AniDecoder;
import com.github.auties00.smalt.image.ico.AniEncoder;
import com.github.auties00.smalt.image.ico.AniEncoderOptions;
import com.github.auties00.smalt.image.ico.AniStep;
import com.github.auties00.smalt.image.ico.IcoDecoder;
import com.github.auties00.smalt.image.ico.IcoEncoder;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2CodeBlockStyle;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegFill;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.image.jpeg.JpegTransforms;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.MjpegEncoder;
import com.github.auties00.smalt.image.jpeg.MjpegEncoderOptions;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.png.ApngEncoder;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffCompression;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffPredictor;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.Vp8Encoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.auties00.smalt.aot.internal.VideoStreams.HEIGHT;
import static com.github.auties00.smalt.aot.internal.VideoStreams.TIME_BASE;
import static com.github.auties00.smalt.aot.internal.VideoStreams.WIDTH;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.realtime;

/**
 * Creates the workloads of {@link TrainingFamily#IMAGE}, the codecs of {@code smalt-image}: the
 * still-image and animation codecs and their metadata, each on synthetic content that is encoded
 * once, when the workload is created, and the VP8 video codec.
 */
final class ImageWorkloads {
    /**
     * The qualities of the animations encoded at once, from the first to the fourth.
     */
    private static final int[] ANIMATION_QUALITIES = {75, 50, 35, 20};

    /**
     * The bitrate of the VP8 streams, in bits per second.
     */
    private static final int VP8_BITRATE = 800_000;

    /**
     * Prevents instantiation.
     */
    private ImageWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        // TODO: train the JPEG XL decoder, which needs a file that no Smalt encoder produces
        return List.of(
                realtime("vp8-encode-rt-s6-t1", () -> vp8Encode(1, 6)),
                realtime("vp8-encode-rt-s6-t2", () -> vp8Encode(2, 6)),
                realtime("vp8-decode-rt", () -> VideoStreams.decode("vp8-decode-rt", vp8Stream(),
                        new Vp8Decoder(VideoStreams.track(Codec.VP8, null)))),
                realtime("vp8-session-t2", () -> VideoStreams.session("vp8-session-t2", Codec.VP8,
                        () -> new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.forRealtime().toBuilder()
                                .timeBase(TIME_BASE).bitrate(VP8_BITRATE).threads(2).build()),
                        Vp8Decoder::new)),
                file("vp8-encode-file-t2", () -> VideoStreams.encode("vp8-encode-file-t2",
                        new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.builder().timeBase(TIME_BASE)
                                .bitrate(VP8_BITRATE).threads(2).build()))),
                file("vp8-decode-t2", () -> VideoStreams.decode("vp8-decode-t2", vp8Stream(),
                        new Vp8Decoder(VideoStreams.track(Codec.VP8, null),
                                VideoDecoderOptions.builder().threads(2).build()))),
                file("jpeg-decode-reduced", () -> jpegDecodeReduced(2)),
                file("jpeg-decode-full", () -> jpegDecodeFull(2)),
                file("jpeg-lossless-decode", ImageWorkloads::jpegLosslessDecode),
                file("jpeg-lossless-variants-decode", ImageWorkloads::jpegLosslessVariantsDecode),
                file("jpeg-decode-12bit", ImageWorkloads::jpegDecode12),
                file("jpeg-encode", ImageWorkloads::jpegEncode),
                file("jpeg-encode-modes", ImageWorkloads::jpegEncodeModes),
                file("jpeg-transform", ImageWorkloads::jpegTransform),
                file("mjpeg-decode", ImageWorkloads::mjpegDecode),
                file("mjpeg-encode", ImageWorkloads::mjpegEncode),
                file("png-decode", () -> pngDecode(2)),
                file("png-encode", ImageWorkloads::pngEncode),
                file("apng", ImageWorkloads::apng),
                file("gif-decode", () -> gifDecode(4)),
                file("gif-encode", ImageWorkloads::gifEncode),
                file("webp-encode", ImageWorkloads::webpEncode),
                file("webp-decode", ImageWorkloads::webpDecode),
                file("webp-animation-c2", () -> webpAnimation(2, 4)),
                file("tiff", ImageWorkloads::tiff),
                file("jpeg2000", ImageWorkloads::jpeg2000),
                file("htj2k", ImageWorkloads::highThroughputJpeg2000),
                file("jpeg2000-part2", ImageWorkloads::part2Jpeg2000),
                file("ico-bmp", ImageWorkloads::icoBmp),
                file("bmp", ImageWorkloads::bmp),
                file("image-metadata", ImageWorkloads::metadata));
    }

    /**
     * Returns a workload that encodes with the real-time VP8 encoder, error resilient, with a
     * one-second buffer model.
     *
     * @param threads the encoder threads
     * @param speed the speed level, as {@code Vp8EncoderSpeed.of} takes it
     * @return the workload
     */
    private static TrainingWorkload vp8Encode(int threads, int speed) {
        var encoder = new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.builder()
                .timeBase(TIME_BASE)
                .bitrate(VP8_BITRATE)
                .quantizers(4, 63)
                .noPeriodicKeyframes()
                .speed(Vp8EncoderSpeed.of(speed))
                .errorResilient(true)
                .bufferSize(Duration.ofMillis(1000))
                .initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600))
                .threads(threads)
                .build());
        return VideoStreams.encode("vp8-encode-rt-s" + speed + "-t" + threads, encoder);
    }

    /**
     * Returns {@value VideoStreams#DECODED_FRAMES} synthetic frames coded by the real-time VP8
     * encoder.
     *
     * @return the packets
     */
    private static List<Packet> vp8Stream() {
        var encoder = new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.forRealtime().toBuilder()
                .timeBase(TIME_BASE).bitrate(VP8_BITRATE).build());
        return VideoStreams.encodeAll(encoder, SyntheticVideo.natural(WIDTH, HEIGHT, VideoStreams.DECODED_FRAMES));
    }

    /**
     * Returns a workload that decodes a 2560x1440 JPEG at half size through the size hint, fits it
     * within 1200 pixels with Lanczos and re-encodes it at quality 80.
     *
     * @param count the photos per round
     * @return the workload
     */
    private static TrainingWorkload jpegDecodeReduced(int count) {
        var photo = JpegEncoder.encode(SyntheticVideo.natural(2560, 1440, 1).getFirst(),
                JpegEncoderOptions.ofQuality(92));
        var hint = ImageDecoderOptions.builder().minSize(1200, 1200 * 1440 / 2560).build();
        var output = JpegEncoderOptions.ofQuality(80);
        return TrainingWorkload.of("jpeg-decode-reduced", () -> {
            for (var i = 0; i < count; i++) {
                var decoded = JpegDecoder.decode(MediaSource.of(photo), hint).picture();
                var fitted = ImageOps.fitWithin(decoded, 1200, ResizeFilter.LANCZOS3);
                Blackhole.consume(JpegEncoder.encode(fitted, output).length + decoded.width());
            }
        });
    }

    /**
     * Returns a workload that decodes a 1280x720 JPEG at full size (the 8x8 inverse transforms a
     * reduced-size decode skips), makes a bilinear 320-pixel preview of it and re-encodes it.
     *
     * @param count the photos per round
     * @return the workload
     */
    private static TrainingWorkload jpegDecodeFull(int count) {
        var photo = JpegEncoder.encode(SyntheticVideo.natural(1280, 720, 1).getFirst(),
                JpegEncoderOptions.ofQuality(90));
        var output = JpegEncoderOptions.ofQuality(80);
        return TrainingWorkload.of("jpeg-decode-full", () -> {
            for (var i = 0; i < count; i++) {
                var decoded = JpegDecoder.decode(MediaSource.of(photo), ImageDecoderOptions.defaults()).picture();
                var preview = ImageOps.toRgba(ImageOps.fitWithin(decoded, 320, ResizeFilter.BILINEAR));
                Blackhole.consume(JpegEncoder.encode(decoded, output).length + preview.width());
            }
        });
    }

    /**
     * Returns a workload that decodes lossless JPEG files (SOF3) of 256x192 pixels, one per
     * predictor: 16-bit gray, 9-bit YCbCr, 8-bit RGB, 10-bit RGB, 12-bit gray, 8-bit gray and 12-bit
     * YCbCr with restart intervals, each at its own depth and reduced to 8 bits.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegLosslessDecode() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(256, 192, 1).getFirst(), false);
        var gray = new int[]{1};
        var ycc = new int[]{1, 2, 3};
        var rgb = new int[]{'R', 'G', 'B'};
        var files = List.of(
                losslessJpeg(picture, gray, 16, 1, 0),
                losslessJpeg(picture, ycc, 9, 2, 0),
                losslessJpeg(picture, rgb, 8, 3, 0),
                losslessJpeg(picture, rgb, 10, 4, 0),
                losslessJpeg(picture, gray, 12, 5, 0),
                losslessJpeg(picture, gray, 8, 6, 0),
                losslessJpeg(picture, ycc, 12, 7, 16));
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        return TrainingWorkload.of("jpeg-lossless-decode", () -> {
            for (var file : files) {
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), highBitDepth).picture().width());
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .height());
            }
        });
    }

    /**
     * Returns a workload that decodes lossless JPEG files of 256x192 pixels beyond one interleaved
     * Huffman scan of full-resolution components: arithmetic-coded 8-bit Y'CbCr in one scan and
     * 12-bit Y'CbCr in a scan per component, Huffman-coded 16-bit Y'CbCr in a scan per component,
     * Huffman-coded 4:2:0 Y'CbCr, and five components, each at its own depth and reduced to 8 bits,
     * the five components also to a channel picture.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegLosslessVariantsDecode() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(256, 192, 1).getFirst(), false);
        var together = new int[][]{{0, 1, 2}};
        var apart = new int[][]{{0}, {1}, {2}};
        var rgb = SyntheticImages.losslessSamples(picture, 3, 8);
        var gray = SyntheticImages.losslessSamples(picture, 1, 8);
        var five = new int[256 * 192 * 5];
        for (var i = 0; i < 256 * 192; i++) {
            for (var c = 0; c < 5; c++) {
                five[i * 5 + c] = c < 3 ? rgb[i * 3 + c] : gray[i] ^ (c == 3 ? 0x55 : 0xAA);
            }
        }
        var files = List.of(
                SyntheticImages.losslessScansJpeg(rgb, 256, 192, 3, 8, 1, true, together),
                SyntheticImages.losslessScansJpeg(SyntheticImages.losslessSamples(picture, 3, 12), 256, 192, 3, 12,
                        4, true, apart),
                SyntheticImages.losslessScansJpeg(SyntheticImages.losslessSamples(picture, 3, 16), 256, 192, 3, 16,
                        7, false, apart),
                SyntheticImages.subsampledLosslessJpeg(picture),
                SyntheticImages.losslessScansJpeg(five, 256, 192, 5, 8, 6, false, new int[][]{{0, 1, 2, 3}, {4}}));
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        var channels = ImageDecoderOptions.builder().keepChannels(true).build();
        return TrainingWorkload.of("jpeg-lossless-variants-decode", () -> {
            for (var file : files) {
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), highBitDepth).picture().width());
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .height());
            }
            Blackhole.consume(JpegDecoder.decode(MediaSource.of(files.getLast()), channels).picture().width());
        });
    }

    /**
     * Returns a workload that decodes 640x360 12-bit JPEG files, sequential and progressive (the
     * progressive one made by the lossless transcoder), at their depth and reduced to 8 bits, at full
     * size and at 1/2, 1/4 and 1/8 of it, as a Motion-JPEG frame, and as the strip of a 12-bit
     * JPEG-compressed TIFF converted to RGB.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegDecode12() {
        var source = JpegEncoder.encode(SyntheticVideo.natural(640, 360, 1).getFirst(), JpegEncoderOptions.ofQuality(90));
        var sequential = SyntheticImages.twelveBitJpeg(source);
        var tiff = SyntheticImages.jpegTiff(sequential, 640, 360);
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        var shrunk = List.of(highBitDepth.toBuilder().minSize(320, 180).build(),
                highBitDepth.toBuilder().minSize(160, 90).build(), ImageDecoderOptions.builder().minSize(80, 45).build());
        var mjpeg = new MjpegDecoder(VideoDecoderOptions.builder().highBitDepth(true).build());
        return TrainingWorkload.of("jpeg-decode-12bit", () -> {
            var progressive = JpegTransforms.progressive(MediaSource.of(sequential));
            for (var file : List.of(sequential, progressive)) {
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), highBitDepth).picture().width());
                Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .height());
                for (var options : shrunk) {
                    Blackhole.consume(JpegDecoder.decode(MediaSource.of(file), options).picture().width());
                }
            }
            Blackhole.consume(mjpeg.decodeFrame(sequential, 0, sequential.length).width());
            Blackhole.consume(TiffDecoder.decode(MediaSource.of(tiff), highBitDepth).picture().height());
        });
    }

    /**
     * Writes a lossless JPEG of a picture.
     *
     * @param picture the 8-bit source picture
     * @param ids the component identifiers, one or three
     * @param precision the sample precision
     * @param predictor the predictor, from 1 to 7
     * @param restartRows the rows of each restart interval, or 0 for none
     * @return the JPEG file
     */
    private static byte[] losslessJpeg(RgbaPicture picture, int[] ids, int precision, int predictor, int restartRows) {
        return SyntheticImages.losslessJpeg(SyntheticImages.losslessSamples(picture, ids.length, precision),
                picture.width(), picture.height(), ids, precision, predictor, restartRows);
    }

    /**
     * Returns a workload that encodes a 512x288 picture in the coding modes beyond baseline: progressive,
     * arithmetic (sequential and progressive), 12-bit, lossless (Huffman and arithmetic, 8 and 16 bits), RGB,
     * CMYK and YCCK, 4:4:0 and 4:1:1 sampling, custom quantization tables and a 16-bit planar picture coded
     * as is.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegEncodeModes() {
        var yuv = SyntheticVideo.natural(512, 288, 1).getFirst();
        var rgba = SyntheticImages.rgba(yuv, false);
        var rgba16 = ImageOps.toSixteenBit(rgba);
        var cmyk = Channel8Picture.allocate(rgba.width(), rgba.height(), PixelLayout.CMYK);
        for (var i = 0; i < rgba.width() * rgba.height(); i++) {
            for (var c = 0; c < 4; c++) {
                cmyk.pixels()[4 * i + c] = (byte) (255 - rgba.pixels()[4 * i + (c == 3 ? 1 : c)]);
            }
        }
        var deep = ImageOps.toBitDepth(yuv, 12);
        var table = JpegEncoderOptions.QuantizationTable.ANNEX_K_LUMINANCE;
        var options = List.of(
                JpegEncoderOptions.ofProgressive(85),
                JpegEncoderOptions.builder().arithmetic(true).build(),
                JpegEncoderOptions.builder().arithmetic(true).progressive(true).restartInterval(8).build(),
                JpegEncoderOptions.builder().precision(12).chromaFormat(ChromaFormat.YUV444).build(),
                JpegEncoderOptions.builder().precision(12).progressive(true).build(),
                JpegEncoderOptions.builder().colorSpace(JpegEncoderOptions.ColorSpace.RGB).progressive(true).build(),
                JpegEncoderOptions.builder().colorSpace(JpegEncoderOptions.ColorSpace.YCCK).build(),
                JpegEncoderOptions.builder().samplingFactors(JpegEncoderOptions.Sampling.of(1, 2)).build(),
                JpegEncoderOptions.builder().samplingFactors(JpegEncoderOptions.Sampling.of(4, 1)).optimizeHuffman(false)
                        .build(),
                JpegEncoderOptions.builder().quality(40).chromaQuality(20).quantizationTables(table, table, table)
                        .quantizationSlots(0, 1, 2).baselineQuantization(false).build());
        var lossless = List.of(
                JpegEncoderOptions.ofLossless(),
                JpegEncoderOptions.ofLossless().toBuilder().arithmetic(true).predictor(6).build());
        return TrainingWorkload.of("jpeg-encode-modes", () -> {
            for (var option : options) {
                Blackhole.consume(JpegEncoder.encode(rgba, option).length);
            }
            for (var option : lossless) {
                Blackhole.consume(JpegEncoder.encode(rgba, option).length);
                Blackhole.consume(JpegEncoder.encode(rgba16, option).length);
            }
            Blackhole.consume(JpegEncoder.encode(cmyk, JpegEncoderOptions.builder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.CMYK).progressive(true).build()).length);
            Blackhole.consume(JpegEncoder.encode(deep, JpegEncoderOptions.builder().precision(12).build()).length);
            Blackhole.consume(JpegEncoder.encode(yuv, JpegEncoderOptions.ofLossless().toBuilder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.YCBCR).build()).length);
        });
    }

    /**
     * Returns a workload that encodes a 1024x576 RGBA picture with 4:4:4 chroma and optimized
     * Huffman tables at quality 95, with 4:2:2 chroma and restart markers at quality 50, and with
     * the defaults, and the same picture as 4:2:0 Y'CbCr.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegEncode() {
        var yuv = SyntheticVideo.natural(1024, 576, 1).getFirst();
        var rgba = SyntheticImages.rgba(yuv, false);
        var options = List.of(
                JpegEncoderOptions.ofQuality(95).toBuilder().chromaFormat(ChromaFormat.YUV444).optimizeHuffman(true)
                        .build(),
                JpegEncoderOptions.ofQuality(50).toBuilder().chromaFormat(ChromaFormat.YUV422).restartInterval(4)
                        .build(),
                JpegEncoderOptions.defaults());
        return TrainingWorkload.of("jpeg-encode", () -> {
            for (var option : options) {
                Blackhole.consume(JpegEncoder.encode(rgba, option).length);
            }
            Blackhole.consume(JpegEncoder.encode(yuv, JpegEncoderOptions.defaults()).length);
        });
    }

    /**
     * Returns a workload that transforms a 1280x720 JPEG losslessly (a quarter turn, progressive
     * recoding, grayscale, arithmetic recoding with restart rows, a reflecting crop extension, a flat
     * wipe and a drop) and decodes the progressive result.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegTransform() {
        var photo = JpegEncoder.encode(SyntheticVideo.natural(1280, 720, 1).getFirst(),
                JpegEncoderOptions.ofQuality(90));
        var stamp = JpegEncoder.encode(SyntheticVideo.natural(256, 128, 1).getFirst(),
                JpegEncoderOptions.ofQuality(60));
        return TrainingWorkload.of("jpeg-transform", () -> {
            Blackhole.consume(JpegTransforms.rotate(MediaSource.of(photo), 90).length);
            Blackhole.consume(JpegTransforms.grayscale(MediaSource.of(photo)).length);
            Blackhole.consume(JpegTransforms.transform(MediaSource.of(photo), JpegTransformOptions.builder()
                    .arithmetic(true).restartRows(2).crop(new Region(64, 0, 1408, 720)).fill(JpegFill.REFLECT)
                    .build()).length);
            Blackhole.consume(JpegTransforms.transform(MediaSource.of(photo), JpegTransformOptions.builder()
                    .wipe(new Region(300, 200, 400, 200)).fill(JpegFill.FLAT).build()).length);
            Blackhole.consume(JpegTransforms.drop(MediaSource.of(photo), MediaSource.of(stamp), 640, 320).length);
            var progressive = JpegTransforms.progressive(MediaSource.of(photo));
            Blackhole.consume(JpegDecoder.decode(MediaSource.of(progressive), ImageDecoderOptions.defaults())
                    .picture().width());
        });
    }

    /**
     * Returns a workload that decodes eight Motion-JPEG frames of 640x360 Y'CbCr video, then an
     * interlaced frame of two field images, two QuickTime format B frames (one of them interlaced)
     * and a lossless 4:2:0 frame.
     *
     * @return the workload
     */
    private static TrainingWorkload mjpegDecode() {
        var pictures = SyntheticVideo.natural(WIDTH, HEIGHT, 8);
        var frames = new ArrayList<byte[]>();
        for (var picture : pictures) {
            frames.add(JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85)));
        }
        var interlaced = new MjpegEncoder(WIDTH, HEIGHT, MjpegEncoderOptions.builder()
                .fieldOrder(FieldOrder.TOP_FIRST).chromaFormat(ChromaFormat.YUV422).build())
                .encodeFrame(new VideoFrame(pictures.getFirst(), 0, 1, true)).data();
        var field = JpegEncoder.encode(SyntheticVideo.natural(WIDTH, HEIGHT / 2, 1).getFirst(),
                JpegEncoderOptions.ofQuality(85));
        var fieldB = SyntheticImages.formatB(field);
        var formatB = List.of(SyntheticImages.formatB(frames.get(1)), concat(withNextField(fieldB), fieldB));
        var lossless = SyntheticImages.subsampledLosslessJpeg(SyntheticImages.rgba(pictures.get(2), false));
        var decoder = new MjpegDecoder();
        return TrainingWorkload.of("mjpeg-decode", () -> {
            for (var frame : frames) {
                Blackhole.consume(((YuvPicture) decoder.decodeFrame(frame, 0, frame.length)).y()[17]);
            }
            Blackhole.consume(decoder.decodeFrame(interlaced, 0, interlaced.length).height());
            for (var frame : formatB) {
                Blackhole.consume(decoder.decodeFrame(frame, 0, frame.length).height());
            }
            Blackhole.consume(decoder.decodeFrame(lossless, 0, lossless.length).width());
        });
    }

    /**
     * Returns a copy of a format B field whose header points to a field that follows it.
     *
     * @param field the field, not modified
     * @return the copy
     */
    private static byte[] withNextField(byte[] field) {
        var copy = field.clone();
        ByteBuffer.wrap(copy).putInt(16, copy.length);
        return copy;
    }

    /**
     * Returns two arrays one after the other.
     *
     * @param first the first array
     * @param second the second array
     * @return the concatenation
     */
    private static byte[] concat(byte[] first, byte[] second) {
        var out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    /**
     * Returns a workload that encodes four 640x360 frames as Motion-JPEG with each kind of frame:
     * optimized tables on the calling thread, the AVI convention on the shared pool, interlaced
     * 4:2:2 fields, and under rate control.
     *
     * @return the workload
     */
    private static TrainingWorkload mjpegEncode() {
        var pictures = SyntheticVideo.natural(WIDTH, HEIGHT, 4);
        var settings = List.of(MjpegEncoderOptions.builder().threads(1).build(), MjpegEncoderOptions.forAvi(),
                MjpegEncoderOptions.builder().fieldOrder(FieldOrder.BOTTOM_FIRST).chromaFormat(ChromaFormat.YUV422)
                        .build(),
                MjpegEncoderOptions.builder().bitrate(8_000_000).build());
        return TrainingWorkload.of("mjpeg-encode", () -> {
            for (var options : settings) {
                var encoder = new MjpegEncoder(WIDTH, HEIGHT, options);
                for (var picture : pictures) {
                    Blackhole.consume(encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP,
                            Packet.UNKNOWN_DURATION, true)).data().length);
                }
            }
        });
    }

    /**
     * Returns a workload that decodes an opaque 1024x768 picture and a 512x512 picture with alpha,
     * both written with every row filter type, and the opaque picture as Smalt's encoder writes it.
     *
     * @param count the decodes of the three files per round
     * @return the workload
     */
    private static TrainingWorkload pngDecode(int count) {
        var opaque = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        var files = List.of(SyntheticImages.cycledFilterPng(opaque),
                SyntheticImages.cycledFilterPng(SyntheticImages.rgba(SyntheticVideo.natural(512, 512, 1).getFirst(), true)),
                PngEncoder.encode(opaque, PngEncoderOptions.defaults()));
        return TrainingWorkload.of("png-decode", () -> {
            for (var i = 0; i < count; i++) {
                for (var file : files) {
                    Blackhole.consume(PngDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture().width());
                }
            }
        });
    }

    /**
     * Returns a workload that encodes a 640x360 picture with every fixed row filter and with the
     * adaptive choice, a picture with alpha (also interlaced), a gray picture of 16 levels (4-bit
     * grayscale), and a 16-bit picture, which it decodes back at 16 bits.
     *
     * @return the workload
     */
    private static TrainingWorkload pngEncode() {
        var opaque = SyntheticImages.rgba(SyntheticVideo.natural(640, 360, 1).getFirst(), false);
        var alpha = SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), true);
        var deep = SyntheticImages.deep(alpha);
        var gray = RgbaPicture.allocate(alpha.width(), alpha.height(), false);
        for (var y = 0; y < gray.height(); y++) {
            for (var x = 0; x < gray.width(); x++) {
                var i = y * gray.stride() + x * 4;
                var level = (byte) ((alpha.pixels()[y * alpha.stride() + x * 4] & 0xF0) * 17 / 16);
                gray.pixels()[i] = level;
                gray.pixels()[i + 1] = level;
                gray.pixels()[i + 2] = level;
                gray.pixels()[i + 3] = (byte) 255;
            }
        }
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        var interlaced = PngEncoderOptions.builder().interlaced(true).build();
        return TrainingWorkload.of("png-encode", () -> {
            for (var filter : PngEncoderOptions.Filter.values()) {
                Blackhole.consume(PngEncoder.encode(opaque, PngEncoderOptions.builder().filter(filter).build()).length);
            }
            Blackhole.consume(PngEncoder.encode(alpha, PngEncoderOptions.defaults()).length);
            Blackhole.consume(PngEncoder.encode(alpha, interlaced).length);
            Blackhole.consume(PngEncoder.encode(gray, PngEncoderOptions.defaults()).length);
            var file = PngEncoder.encode(deep, PngEncoderOptions.defaults());
            Blackhole.consume(PngDecoder.decode(MediaSource.of(file), highBitDepth).picture().width());
        });
    }

    /**
     * Returns a workload that encodes a 256x256 animation with alpha as APNG and reads every frame
     * back.
     *
     * @return the workload
     */
    private static TrainingWorkload apng() {
        var frames = SyntheticImages.alphaFrames(256, 4);
        return TrainingWorkload.of("apng", () -> {
            var encoder = new ApngEncoder(256, 256);
            for (var frame : frames) {
                encoder.addFrame(frame, 1, 25);
            }
            var file = encoder.finish();
            try (var reader = PngDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    Blackhole.consume(((RgbaPicture) frame.picture()).pixels()[4099]);
                }
            }
        });
    }

    /**
     * Returns a workload that decodes and composites every frame of a 480x270 animated GIF of
     * twelve frames.
     *
     * @param count the decodes of the animation per round
     * @return the workload
     */
    private static TrainingWorkload gifDecode(int count) {
        var gif = SyntheticImages.animatedGif(480, 270, 12);
        return TrainingWorkload.of("gif-decode", () -> {
            for (var i = 0; i < count; i++) {
                try (var reader = GifDecoder.openAnimation(MediaSource.of(gif), ImageDecoderOptions.defaults())) {
                    for (var frame = reader.read(); frame != null; frame = reader.read()) {
                        Blackhole.consume(((RgbaPicture) frame.picture()).pixels()[4099]);
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that encodes a 320x180 picture as a still GIF with every quantizer and
     * every dithering, and a 256x256 animation with alpha.
     *
     * @return the workload
     */
    private static TrainingWorkload gifEncode() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(320, 180, 1).getFirst(), false);
        var frames = SyntheticImages.alphaFrames(256, 4);
        return TrainingWorkload.of("gif-encode", () -> {
            for (var quantizer : QuantizeMethod.values()) {
                for (var dithering : Dithering.values()) {
                    Blackhole.consume(GifEncoder.encode(picture, GifEncoderOptions.builder().method(quantizer)
                            .dithering(dithering).build()).length);
                }
            }
            var encoder = new GifAnimationEncoder(256, 256, GifEncoderOptions.builder().maxColors(128).build());
            for (var frame : frames) {
                encoder.addFrame(frame, 4);
            }
            Blackhole.consume(encoder.finish().length);
        });
    }

    /**
     * Returns a workload that encodes 256x256 pictures with alpha as lossy and lossless WebP still
     * images, in the default modes and with sharp conversion, the photo preset's dithering,
     * automatic filtering, a PSNR target and near-lossless coding, and a 1024x768 picture as lossy
     * WebP, which is large enough for the multi-threaded token loop.
     *
     * @return the workload
     */
    private static TrainingWorkload webpEncode() {
        var frames = SyntheticImages.alphaFrames(256, 2);
        var lossy = WebpEncoderOptions.lossy(75);
        var lossless = WebpEncoderOptions.lossless();
        var modes = List.of(
                lossy.toBuilder().sharpYuv(true).build(),
                WebpEncoderOptions.forPreset(WebpEncoderOptions.Preset.PHOTO),
                lossy.toBuilder().autoFilter(true).method(2).build(),
                lossy.toBuilder().autoFilter(true).simpleFilter(true).build(),
                lossy.toBuilder().targetPsnr(38).build(),
                lossless.toBuilder().nearLossless(60).build());
        var photo = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return TrainingWorkload.of("webp-encode", () -> {
            for (var frame : frames) {
                Blackhole.consume(WebpEncoder.encode(frame, lossy).length + WebpEncoder.encode(frame, lossless).length);
                for (var mode : modes) {
                    Blackhole.consume(WebpEncoder.encode(frame, mode).length);
                }
            }
            Blackhole.consume(WebpEncoder.encode(photo, lossy).length);
        });
    }

    /**
     * Returns a workload that decodes lossy and lossless WebP still images of 256x256 pictures with
     * alpha, and every frame of an animated WebP.
     *
     * @return the workload
     */
    private static TrainingWorkload webpDecode() {
        var images = new ArrayList<byte[]>();
        var frames = SyntheticImages.alphaFrames(256, 2);
        for (var frame : frames) {
            images.add(WebpEncoder.encode(frame, WebpEncoderOptions.lossy(75)));
            images.add(WebpEncoder.encode(frame, WebpEncoderOptions.lossless()));
        }
        var encoder = new WebpAnimationEncoder(256, 256, WebpAnimationEncoderOptions.lossy(60));
        for (var frame : frames) {
            encoder.addFrame(frame, 40);
        }
        var animation = encoder.finish();
        return TrainingWorkload.of("webp-decode", () -> {
            for (var image : images) {
                Blackhole.consume(WebpDecoder.decode(MediaSource.of(image), ImageDecoderOptions.defaults()).hashCode());
            }
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(animation), ImageDecoderOptions.defaults())) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    Blackhole.consume(((RgbaPicture) frame.picture()).pixels()[4099]);
                }
            }
        });
    }

    /**
     * Returns a workload that encodes lossy 512x512 animations with alpha at falling qualities,
     * several at once, each on a virtual thread of its own and each encoder running its own
     * helpers.
     *
     * @param concurrent the animations encoded at once, from 1 to 4
     * @param frames     the moving frames of each animation
     * @return the workload
     */
    private static TrainingWorkload webpAnimation(int concurrent, int frames) {
        var pictures = SyntheticImages.alphaFrames(512, frames);
        return TrainingWorkload.of("webp-animation-c" + concurrent, () -> {
            var results = new ArrayList<Future<byte[]>>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var index = 0; index < concurrent; index++) {
                    var quality = ANIMATION_QUALITIES[index % ANIMATION_QUALITIES.length];
                    results.add(executor.submit(() -> {
                        var encoder = new WebpAnimationEncoder(512, 512, WebpAnimationEncoderOptions.lossy(quality));
                        for (var picture : pictures) {
                            encoder.addFrame(picture, 40);
                        }
                        return encoder.finish();
                    }));
                }
            }
            try {
                for (var result : results) {
                    Blackhole.consume(result.get().length);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        });
    }

    /**
     * Returns a workload that encodes a 640x480 picture as TIFF with LZW, Deflate with the
     * horizontal predictor, PackBits, Zstandard, and Deflate, Zstandard, WebP and JPEG 2000 in tiles, as
     * JPEG in strips and tiles, as CMYK in separate planes, as palette, Y'CbCr 4:2:0 and dithered bilevel
     * pages under the CCITT codings, a 16-bit picture with alpha, and CMYK, floating-point (with the
     * floating-point predictor) and extra-sample channel pictures, and decodes every file back, the channel
     * pictures with their channels kept; then decodes the rarer layouts of
     * {@link SyntheticImages#tiffVariants(RgbaPicture)} and the compressions of
     * {@link SyntheticImages#tiffCompressions(RgbaPicture)} at 8 and 16 bits.
     *
     * @return the workload
     */
    private static TrainingWorkload tiff() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false);
        var variants = new ArrayList<>(SyntheticImages.tiffVariants(
                SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), true)));
        variants.addAll(SyntheticImages.tiffCompressions(
                SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), false)));
        var deep = SyntheticImages.deep(SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), true));
        var options = List.of(
                TiffEncoderOptions.builder().compression(TiffCompression.LZW).build(),
                TiffEncoderOptions.deflate().toBuilder().predictor(TiffPredictor.HORIZONTAL).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.PACKBITS).build(),
                TiffEncoderOptions.zstd(),
                TiffEncoderOptions.deflate().toBuilder().tiles(256, 256).build(),
                TiffEncoderOptions.zstd().toBuilder().compressionLevel(9).tiles(256, 256).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.WEBP).tiles(256, 256).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000).tiles(256, 256).build(),
                TiffEncoderOptions.jpeg(),
                TiffEncoderOptions.jpeg().toBuilder().tiles(256, 256).jpegOptions(JpegEncoderOptions.builder()
                        .optimizeHuffman(false).build()).build(),
                TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.CMYK).planar(true).build(),
                TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.PALETTE).build(),
                TiffEncoderOptions.deflate().toBuilder().layout(TiffEncoderOptions.Layout.YCBCR).build(),
                TiffEncoderOptions.fax().toBuilder().bilevelDithering(Dithering.FLOYD_STEINBERG).build(),
                TiffEncoderOptions.fax().toBuilder().compression(TiffCompression.CCITT_T4).faxTwoDimensional(true)
                        .faxFillBits(true).build(),
                TiffEncoderOptions.fax().toBuilder().compression(TiffCompression.CCITT_RLE)
                        .bilevelDithering(Dithering.ORDERED).build());
        var floatingOptions = TiffEncoderOptions.deflate().toBuilder().predictor(TiffPredictor.FLOATING_POINT).build();
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        var keep = highBitDepth.toBuilder().keepChannels(true).build();
        var cmyk = Channel8Picture.of(640, 480, 640 * 4, picture.pixels().clone(), PixelLayout.CMYK);
        var floating = ImageOps.toChannelFloat(deep);
        var extras = Channel16Picture.allocate(320, 240,
                PixelLayout.RGBA.withChannel(Channel.of(ChannelType.UNSPECIFIED)));
        return TrainingWorkload.of("tiff", () -> {
            for (var option : options) {
                var file = TiffEncoder.encode(picture, option);
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture().width());
            }
            var file = TiffEncoder.encode(deep,
                    TiffEncoderOptions.deflate().toBuilder().predictor(TiffPredictor.HORIZONTAL).build());
            Blackhole.consume(TiffDecoder.decode(MediaSource.of(file), highBitDepth).picture().width());
            for (var channels : List.of(cmyk, floating, extras)) {
                var page = TiffEncoder.encode(channels, channels == floating ? floatingOptions
                        : TiffEncoderOptions.deflate());
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(page), keep).picture().width());
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(page), ImageDecoderOptions.defaults()).picture()
                        .width());
            }
            for (var variant : variants) {
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(variant), ImageDecoderOptions.defaults()).picture()
                        .width());
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(variant), highBitDepth).picture().width());
            }
            for (var format : List.of(ImageFormat.TIFF, ImageFormat.ICO)) {
                var sink = MediaSink.memory();
                try (var writer = Images.openPageWriter(format, sink)) {
                    writer.write(deep);
                    writer.write(cmyk);
                    writer.finish();
                }
                try (var pages = Images.openPages(MediaSource.of(sink.toByteArray()))) {
                    Blackhole.consume(pages.read(pages.pageCount() - 1).picture().width());
                }
            }
        });
    }

    /**
     * Returns a workload that encodes a 384x256 picture as lossless and as layered lossy JPEG 2000,
     * and decodes both, the lossy one also at half resolution and as raw channels, and codes a CMYK
     * channel picture losslessly and decodes it back to its inks.
     *
     * @return the workload
     */
    private static TrainingWorkload jpeg2000() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(384, 256, 1).getFirst(), false);
        var lossy = Jp2EncoderOptions.lossy(20).toBuilder().compressionRatios(80, 40, 20).build();
        var reduced = Jp2DecoderOptions.builder().reduction(1).build();
        var cmyk = Channel8Picture.of(192, 128, 192 * 4, Arrays.copyOf(picture.pixels(), 192 * 128 * 4),
                PixelLayout.CMYK);
        var keep = ImageDecoderOptions.builder().keepChannels(true).build();
        return TrainingWorkload.of("jpeg2000", () -> {
            var lossless = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless());
            var layered = Jp2Encoder.encode(picture, lossy);
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(lossless), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(layered), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(Jp2Decoder.decodeChannels(MediaSource.of(layered), reduced).hashCode());
            var inks = Jp2Encoder.encode(cmyk, Jp2EncoderOptions.lossless());
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(inks), keep).picture().width());
        });
    }

    /**
     * Returns a workload that decodes ISO/IEC 15444-2 codestreams and a JPX file made from 384x256 pictures coded by
     * {@link Jp2Encoder}: a lossy one given an irreversible multiple component transformation, a lossless one whose
     * 5-3 wavelet is signalled as an arbitrary transformation kernel, and both in a JPX file whose composition is
     * rendered as an animation.
     *
     * @return the workload
     */
    private static TrainingWorkload part2Jpeg2000() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(384, 256, 1).getFirst(), false);
        var lossy = Jp2EncoderOptions.lossy(20).toBuilder().colorTransform(false).format(Jp2Format.CODESTREAM)
                .build();
        var lossless = Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM).build();
        return TrainingWorkload.of("jpeg2000-part2", () -> {
            var transformed = part2(Jp2Encoder.encode(picture, lossy), 0x8100, 2, -1, multipleComponentSegments());
            // the 5-3 wavelet as a reversible arbitrary kernel of two lifting steps of 8-bit coefficients
            var atk = segment(0xFF79, new byte[]{0x50, 0x02, 2, 0, 1, 1, 2, -1, -1, -1, 2, 2, 2, 1, 1});
            var kernel = part2(Jp2Encoder.encode(picture, lossless), 0x8040, -1, 2, atk);
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(transformed), ImageDecoderOptions.defaults()).picture()
                    .width());
            Blackhole.consume(Jp2Decoder.decodeChannels(MediaSource.of(kernel)).hashCode());
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(jpx(transformed, kernel)))) {
                for (var frame = animation.read(); frame != null; frame = animation.read()) {
                    Blackhole.consume(frame.picture().width());
                }
            }
        });
    }

    /**
     * Returns the segments of an irreversible YCbCr to RGB decorrelation over three components (ISO/IEC 15444-2
     * A.3.6 to A.3.9).
     *
     * @return the CBD, MCT, MCC and MCO segments
     */
    private static byte[] multipleComponentSegments() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(segment(0xFF78, new byte[]{0, 3, 7, 7, 7}));
        var matrix = new float[]{1, 0, 1.402f, 1, -0.344136f, -0.714136f, 1, 1.772f, 0};
        var array = ByteBuffer.allocate(6 + 4 * matrix.length).putShort((short) 0).putShort((short) 0x0901)
                .putShort((short) 0);
        for (var value : matrix) {
            array.putFloat(value);
        }
        out.writeBytes(segment(0xFF74, array.array()));
        out.writeBytes(segment(0xFF74, ByteBuffer.allocate(12).putShort((short) 0).putShort((short) 0x0202)
                .putShort((short) 0).putShort((short) 0).putShort((short) 128).putShort((short) 128).array()));
        out.writeBytes(segment(0xFF75, new byte[]{0, 0, 1, 0, 0, 0, 1, 1, 0, 3, 0, 1, 2, 0, 3, 0, 1, 2, 0, 2, 1}));
        out.writeBytes(segment(0xFF77, new byte[]{1, 1}));
        return out.toByteArray();
    }

    /**
     * Writes a marker segment.
     *
     * @param marker the marker code
     * @param body the segment's fields
     * @return the segment
     */
    private static byte[] segment(int marker, byte[] body) {
        return ByteBuffer.allocate(4 + body.length).putShort((short) marker).putShort((short) (body.length + 2))
                .put(body).array();
    }

    /**
     * Turns a codestream into an ISO/IEC 15444-2 one: sets {@code Rsiz}, optionally the multiple component
     * transformation and wavelet of the {@code COD} segment, and inserts segments at the end of the main header.
     *
     * @param codestream the Part 1 codestream
     * @param rsiz the new {@code Rsiz}
     * @param transform the new {@code SGcod} transformation, or {@code -1} to keep it
     * @param wavelet the new {@code SPcod} wavelet, or {@code -1} to keep it
     * @param segments the segments to insert
     * @return the new codestream
     */
    private static byte[] part2(byte[] codestream, int rsiz, int transform, int wavelet, byte[] segments) {
        var position = 2;
        var cod = -1;
        while ((codestream[position] & 0xFF) != 0xFF || (codestream[position + 1] & 0xFF) != 0x90) {
            if ((codestream[position + 1] & 0xFF) == 0x52) {
                cod = position;
            }
            position += 2 + ((codestream[position + 2] & 0xFF) << 8 | (codestream[position + 3] & 0xFF));
        }
        var out = new ByteArrayOutputStream();
        out.write(codestream, 0, position);
        out.writeBytes(segments);
        out.write(codestream, position, codestream.length - position);
        var result = out.toByteArray();
        result[6] = (byte) (rsiz >> 8);
        result[7] = (byte) rsiz;
        if (transform >= 0) {
            result[cod + 8] = (byte) transform;
        }
        if (wavelet >= 0) {
            result[cod + 13] = (byte) wavelet;
        }
        return result;
    }

    /**
     * Writes a JPX file of two codestreams, the second in a fragment table, and a composition that shows each.
     *
     * @param first the first codestream
     * @param second the second codestream
     * @return the file
     */
    private static byte[] jpx(byte[] first, byte[] second) {
        var start = new ByteArrayOutputStream();
        start.writeBytes(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A});
        start.writeBytes(box("ftyp", new byte[]{'j', 'p', 'x', ' ', 0, 0, 0, 0, 'j', 'p', 'x', ' '}));
        start.writeBytes(box("jp2h", box("ihdr", ByteBuffer.allocate(14).putInt(256).putInt(384).putShort((short) 3)
                .put((byte) 7).put((byte) 7).array()), box("colr", new byte[]{1, 0, 1, 0, 0, 0, 16})));
        start.writeBytes(box("jp2c", first));
        var instructions = ByteBuffer.allocate(8 + 2 * 16).putShort((short) 9).putShort((short) 0).putInt(40);
        instructions.putInt(0).putInt(0).putInt(0x80000001).putInt(0);
        instructions.putInt(96).putInt(64).putInt(1).putInt(0);
        var composition = box("comp", box("copt", ByteBuffer.allocate(9).putInt(256).putInt(384).put((byte) 0)
                .array()), box("inst", instructions.array()));
        // the fragment list points into the media data box after the fragment table and the composition
        var table = 8 + 8 + 2 + 14;
        var offset = start.size() + table + composition.length + 8;
        var list = ByteBuffer.allocate(16).putShort((short) 1).putLong(offset).putInt(second.length)
                .putShort((short) 0).array();
        start.writeBytes(box("ftbl", box("flst", list)));
        start.writeBytes(composition);
        start.writeBytes(box("mdat", second));
        return start.toByteArray();
    }

    /**
     * Writes a box.
     *
     * @param type the box type
     * @param contents the boxes or bytes of its contents
     * @return the box
     */
    private static byte[] box(String type, byte[]... contents) {
        var body = new ByteArrayOutputStream();
        for (var content : contents) {
            body.writeBytes(content);
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(8 + body.size()).array());
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * Returns a workload that encodes a 384x256 picture with the high-throughput block coder of
     * JPEG 2000 (HTJ2K), losslessly and as layered lossy coding with vertically causal contexts, and
     * decodes both, the lossy one also at half resolution and as raw channels.
     *
     * @return the workload
     */
    private static TrainingWorkload highThroughputJpeg2000() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(384, 256, 1).getFirst(), false);
        var lossless = Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).build();
        var lossy = Jp2EncoderOptions.lossy(20).toBuilder().compressionRatios(60, 20).highThroughput(true)
                .codeBlockStyles(Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).build();
        var reduced = Jp2DecoderOptions.builder().reduction(1).build();
        return TrainingWorkload.of("htj2k", () -> {
            var exact = Jp2Encoder.encode(picture, lossless);
            var layered = Jp2Encoder.encode(picture, lossy);
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(exact), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(Jp2Decoder.decode(MediaSource.of(layered), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(Jp2Decoder.decodeChannels(MediaSource.of(layered), reduced).hashCode());
        });
    }

    /**
     * Returns a workload that encodes a 256x256 picture with alpha as a Windows icon set (bitmap
     * and PNG entries) and decodes every entry, then writes and plays a three-step animated cursor of
     * 48-pixel frames.
     *
     * @return the workload
     */
    private static TrainingWorkload icoBmp() {
        var alpha = SyntheticImages.rgba(SyntheticVideo.natural(256, 256, 1).getFirst(), true);
        var frames = SyntheticVideo.natural(48, 48, 2).stream().map(frame -> SyntheticImages.rgba(frame, true)).toList();
        var steps = List.of(new AniStep(0, 6), new AniStep(1, 6), new AniStep(0, 12));
        return TrainingWorkload.of("ico-bmp", () -> {
            var icon = IcoEncoder.encode(alpha, IcoEncoderOptions.windowsSizes());
            var entries = IcoDecoder.entries(MediaSource.of(icon)).size();
            for (var index = 0; index < entries; index++) {
                Blackhole.consume(IcoDecoder.decode(MediaSource.of(icon), index, ImageDecoderOptions.defaults())
                        .picture().width());
            }
            var cursor = AniEncoder.encode(frames, steps, AniEncoderOptions.defaults());
            try (var reader = AniDecoder.openAnimation(MediaSource.of(cursor))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    Blackhole.consume(frame.timestampMicros());
                }
            }
        });
    }

    /**
     * Returns a workload that encodes a natural 320x180 picture as BMP in every pixel format (the palettes
     * quantized, and run-length encoded at 4 and 8 bits), with alpha and with a color profile, decodes every
     * file at 8 bits, decodes a 64-bit file and a file of 10-bit color masks at 8 and 16 bits, and decodes CMYK
     * pixels to RGBA and as a CMYK picture.
     *
     * @return the workload
     */
    private static TrainingWorkload bmp() {
        var opaque = SyntheticImages.rgba(SyntheticVideo.natural(320, 180, 1).getFirst(), false);
        var alpha = SyntheticImages.rgba(SyntheticVideo.natural(320, 180, 1).getFirst(), true);
        var wide = SyntheticImages.bmp64(alpha);
        var masked = SyntheticImages.bmpMasks1010102(BmpEncoder.encode(alpha, BmpEncoderOptions.builder()
                .pixelFormat(BmpEncoderOptions.PixelFormat.ARGB_8888).build()));
        var cmyk = SyntheticImages.bmpCmyk(BmpEncoder.encode(opaque, BmpEncoderOptions.builder()
                .pixelFormat(BmpEncoderOptions.PixelFormat.XRGB_8888).build()));
        var deep = ImageDecoderOptions.builder().highBitDepth(true).build();
        var channels = ImageDecoderOptions.builder().keepChannels(true).build();
        return TrainingWorkload.of("bmp", () -> {
            for (var format : BmpEncoderOptions.PixelFormat.values()) {
                var source = format == BmpEncoderOptions.PixelFormat.ARGB_8888 ? alpha : opaque;
                var file = BmpEncoder.encode(source, BmpEncoderOptions.builder().pixelFormat(format).build());
                Blackhole.consume(BmpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture().width());
            }
            for (var format : List.of(BmpEncoderOptions.PixelFormat.PALETTE_4, BmpEncoderOptions.PixelFormat.PALETTE_8)) {
                var file = BmpEncoder.encode(opaque, BmpEncoderOptions.builder().pixelFormat(format).runLength(true).build());
                Blackhole.consume(BmpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture().width());
            }
            var profiled = BmpEncoder.encode(opaque, BmpEncoderOptions.builder().colorProfile(ColorProfile.displayP3())
                    .build());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(profiled), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(wide), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(wide), deep).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(masked), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(masked), deep).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(cmyk), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(BmpDecoder.decode(MediaSource.of(cmyk), channels).picture().width());
        });
    }

    /**
     * Returns a workload that reads, edits and probes the metadata of image files: EXIF with an
     * orientation and a GPS position, and comments, written into JPEG, PNG, WebP and TIFF files, read
     * back and stripped of the location; the metadata of GIF, TIFF and BMP files; and the
     * description of JPEG 2000 and ICO files.
     *
     * @return the workload
     */
    private static TrainingWorkload metadata() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), false);
        var exif = ExifData.EMPTY.withOrientation(Orientation.ROTATE_90)
                .with(ExifData.Directory.GPS, 0x0001, new ExifValue.Ascii("N"))
                .with(ExifData.Directory.GPS, 0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL,
                        new long[]{45, 30, 0}, new long[]{1, 1, 1}));
        var edit = ImageMetadataEdit.keepAll().toBuilder().exif(exif).comments(List.of("synthetic")).build();
        var tagged = List.of(
                ImageMetadataEditor.apply(JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85)), edit),
                ImageMetadataEditor.apply(PngEncoder.encode(picture, PngEncoderOptions.defaults()), edit),
                ImageMetadataEditor.apply(WebpEncoder.encode(picture, WebpEncoderOptions.lossy(75)),
                        ImageMetadataEdit.keepAll().toBuilder().exif(exif).build()),
                ImageMetadataEditor.apply(TiffEncoder.encode(picture, TiffEncoderOptions.deflate()),
                        ImageMetadataEdit.keepAll().toBuilder().exif(exif).build()));
        var described = List.of(GifEncoder.encode(picture, GifEncoderOptions.defaults()),
                TiffEncoder.encode(picture, TiffEncoderOptions.deflate()),
                BmpEncoder.encode(picture, BmpEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build()));
        var probed = List.of(Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(20)),
                IcoEncoder.encode(SyntheticImages.rgba(SyntheticVideo.natural(64, 64, 1).getFirst(), true),
                        IcoEncoderOptions.defaults()));
        return TrainingWorkload.of("image-metadata", () -> {
            for (var file : tagged) {
                var metadata = ImageMetadata.read(file);
                Blackhole.consume(metadata.orientation().ordinal() + (metadata.hasLocation() ? 1 : 0));
                Blackhole.consume(ImageMetadataEditor.stripLocation(file).length);
                Blackhole.consume(Images.probe(MediaSource.of(file)).hashCode());
            }
            for (var file : described) {
                Blackhole.consume(ImageMetadata.read(file).isEmpty() ? 1 : 0);
                Blackhole.consume(Images.probe(MediaSource.of(file)).hashCode());
            }
            for (var file : probed) {
                Blackhole.consume(Images.probe(MediaSource.of(file)).hashCode());
            }
        });
    }
}
