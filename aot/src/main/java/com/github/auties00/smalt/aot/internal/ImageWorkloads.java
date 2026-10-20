package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.gif.GifAnimationEncoder;
import com.github.auties00.smalt.image.gif.GifDecoder;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.gif.GifEncoderOptions;
import com.github.auties00.smalt.image.ico.IcoDecoder;
import com.github.auties00.smalt.image.ico.IcoEncoder;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
                file("jpeg-decode-12bit", ImageWorkloads::jpegDecode12),
                file("jpeg-encode", ImageWorkloads::jpegEncode),
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
                file("ico-bmp", ImageWorkloads::icoBmp),
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
     * recoding and grayscale) and decodes the progressive result.
     *
     * @return the workload
     */
    private static TrainingWorkload jpegTransform() {
        var photo = JpegEncoder.encode(SyntheticVideo.natural(1280, 720, 1).getFirst(),
                JpegEncoderOptions.ofQuality(90));
        return TrainingWorkload.of("jpeg-transform", () -> {
            Blackhole.consume(JpegTransforms.rotate(MediaSource.of(photo), 90).length);
            Blackhole.consume(JpegTransforms.grayscale(MediaSource.of(photo)).length);
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
     * images, and a 1024x768 picture as lossy WebP, which is large enough for the multi-threaded
     * token loop.
     *
     * @return the workload
     */
    private static TrainingWorkload webpEncode() {
        var frames = SyntheticImages.alphaFrames(256, 2);
        var lossy = WebpEncoderOptions.lossy(75);
        var lossless = WebpEncoderOptions.lossless();
        var photo = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return TrainingWorkload.of("webp-encode", () -> {
            for (var frame : frames) {
                Blackhole.consume(WebpEncoder.encode(frame, lossy).length + WebpEncoder.encode(frame, lossless).length);
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
     * horizontal predictor, PackBits, and Deflate, WebP and JPEG 2000 in tiles, a 16-bit picture
     * with alpha, and CMYK, floating-point and extra-sample channel pictures, and decodes every file
     * back, the channel pictures with their channels kept.
     *
     * @return the workload
     */
    private static TrainingWorkload tiff() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false);
        var deep = SyntheticImages.deep(SyntheticImages.rgba(SyntheticVideo.natural(320, 240, 1).getFirst(), true));
        var options = List.of(
                TiffEncoderOptions.builder().compression(TiffCompression.LZW).build(),
                TiffEncoderOptions.deflate().toBuilder().predictor(TiffPredictor.HORIZONTAL).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.PACKBITS).build(),
                TiffEncoderOptions.deflate().toBuilder().tiles(256, 256).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.WEBP).tiles(256, 256).build(),
                TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000).tiles(256, 256).build());
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
                var page = TiffEncoder.encode(channels, TiffEncoderOptions.deflate());
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(page), keep).picture().width());
                Blackhole.consume(TiffDecoder.decode(MediaSource.of(page), ImageDecoderOptions.defaults()).picture()
                        .width());
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
     * Returns a workload that encodes a 256x256 picture with alpha as a Windows icon set (bitmap
     * and PNG entries) and decodes every entry, and decodes 24-bit and 32-bit BMP files.
     *
     * @return the workload
     */
    private static TrainingWorkload icoBmp() {
        var alpha = SyntheticImages.rgba(SyntheticVideo.natural(256, 256, 1).getFirst(), true);
        var opaque = SyntheticImages.rgba(SyntheticVideo.natural(640, 360, 1).getFirst(), false);
        var bitmaps = List.of(SyntheticImages.bmp(opaque), SyntheticImages.bmp(alpha));
        return TrainingWorkload.of("ico-bmp", () -> {
            var icon = IcoEncoder.encode(alpha, IcoEncoderOptions.windowsSizes());
            var entries = IcoDecoder.entries(MediaSource.of(icon)).size();
            for (var index = 0; index < entries; index++) {
                Blackhole.consume(IcoDecoder.decode(MediaSource.of(icon), index, ImageDecoderOptions.defaults())
                        .picture().width());
            }
            for (var bitmap : bitmaps) {
                Blackhole.consume(BmpDecoder.decode(MediaSource.of(bitmap), ImageDecoderOptions.defaults()).picture().width());
            }
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
                SyntheticImages.bmp(picture));
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
