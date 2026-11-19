package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.dds.DdsDecoder;
import com.github.auties00.smalt.image.exr.ExrDecoder;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.gif.GifVideoDecoder;
import com.github.auties00.smalt.image.hdr.HdrDecoder;
import com.github.auties00.smalt.image.hdr.HdrEncoder;
import com.github.auties00.smalt.image.hdr.HdrEncoderOptions;
import com.github.auties00.smalt.image.icns.IcnsDecoder;
import com.github.auties00.smalt.image.icns.IcnsEncoder;
import com.github.auties00.smalt.image.icns.IcnsEncoderOptions;
import com.github.auties00.smalt.image.jxr.JxrDecoder;
import com.github.auties00.smalt.image.png.PngVideoDecoder;
import com.github.auties00.smalt.image.png.PngVideoEncoder;
import com.github.auties00.smalt.image.pnm.PnmDecoder;
import com.github.auties00.smalt.image.pnm.PnmEncoder;
import com.github.auties00.smalt.image.pnm.PnmEncoderOptions;
import com.github.auties00.smalt.image.pnm.PnmVariant;
import com.github.auties00.smalt.image.psd.PsdDecoder;
import com.github.auties00.smalt.image.qoi.QoiDecoder;
import com.github.auties00.smalt.image.qoi.QoiEncoder;
import com.github.auties00.smalt.image.qoi.QoiEncoderOptions;
import com.github.auties00.smalt.image.tga.TgaDecoder;
import com.github.auties00.smalt.image.tga.TgaEncoder;
import com.github.auties00.smalt.image.tga.TgaEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffVideoDecoder;
import com.github.auties00.smalt.image.tiff.TiffVideoEncoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;

import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the {@link TrainingFamily#IMAGE} workloads of the image formats that are not part of the camera, web or
 * video pipelines: PNM, QOI, TGA, Radiance HDR, ICNS, Photoshop, DDS, OpenEXR and JPEG XR, and the PNG, TIFF and GIF
 * video codecs.
 */
final class ImageFormatWorkloads {
    /**
     * The OpenEXR training files: one per compression method, a tiled file with mipmap levels, a multi-part file, a
     * deep file, a luminance-chroma file and a 32-bit float file, written by the OpenEXR 3.5.2 library.
     */
    static final List<String> EXR_FILES = List.of("scanline-rle", "scanline-zips", "scanline-piz", "scanline-pxr24",
            "scanline-b44a", "scanline-dwab", "scanline-zstd", "scanline-htj2k32", "tiled-mipmap-zip", "multipart",
            "deep-scanline-zips", "luminance-chroma-piz", "float-pxr24");

    /**
     * The JPEG XR training files, written by jxrlib 1.1's JxrEncApp: lossless, tiled in spatial order, overlap
     * level 2 with downsampled chroma, trimmed flexbits, planar and interleaved alpha, hard tile boundaries, 16-bit
     * gray, half float, RGBE, CMYK and 5-6-5 pixels.
     */
    static final List<String> JXR_FILES = List.of("rgb8-q1", "rgb8-q30tilesp", "rgb8-q20d2l2", "rgb8-q30trim",
            "bgra8-q40-a2", "bgra8-q30tile-a3", "bgra8-hardd1l1", "gray16-q40", "rgbh-q40", "rgbe-q40", "cmyk8-ic-q80",
            "bgr565-q40");

    /**
     * Prevents instantiation.
     */
    private ImageFormatWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                file("pnm", ImageFormatWorkloads::pnm),
                file("qoi", ImageFormatWorkloads::qoi),
                file("tga", ImageFormatWorkloads::tga),
                file("radiance-hdr", ImageFormatWorkloads::hdr),
                file("icns", ImageFormatWorkloads::icns),
                file("psd-decode", ImageFormatWorkloads::psd),
                file("dds-decode", ImageFormatWorkloads::dds),
                file("exr-decode", ImageFormatWorkloads::exr),
                file("jxr-decode", ImageFormatWorkloads::jxr),
                file("image-video-codecs", ImageFormatWorkloads::imageVideo));
    }

    /**
     * Returns a workload that encodes a natural 320x180 picture as every PNM variant, binary and plain, at 8 and 16
     * bits, and decodes each file.
     *
     * @return the workload
     */
    private static TrainingWorkload pnm() {
        var picture = picture(320, 180, true);
        var options = new ArrayList<PnmEncoderOptions>();
        for (var variant : PnmVariant.values()) {
            options.add(PnmEncoderOptions.of(variant));
        }
        options.add(PnmEncoderOptions.of(PnmVariant.PPM).toBuilder().plain(true).build());
        options.add(PnmEncoderOptions.of(PnmVariant.PGM).toBuilder().maxval(65535).build());
        options.add(PnmEncoderOptions.of(PnmVariant.PAM).toBuilder().maxval(65535).build());
        options.add(PnmEncoderOptions.of(PnmVariant.PFM).toBuilder().littleEndian(true).build());
        var deep = ImageDecoderOptions.builder().highBitDepth(true).build();
        return TrainingWorkload.of("pnm", () -> {
            for (var option : options) {
                var file = PnmEncoder.encode(picture, option);
                Blackhole.consume(PnmDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .width());
                Blackhole.consume(PnmDecoder.decode(MediaSource.of(file), deep).picture().width());
            }
        });
    }

    /**
     * Returns a workload that encodes a natural 320x180 picture as QOI with three and four channels and decodes
     * each file.
     *
     * @return the workload
     */
    private static TrainingWorkload qoi() {
        var opaque = picture(320, 180, false);
        var alpha = picture(320, 180, true);
        var linear = QoiEncoderOptions.builder().linear(true).build();
        return TrainingWorkload.of("qoi", () -> {
            for (var file : List.of(QoiEncoder.encode(opaque), QoiEncoder.encode(alpha), QoiEncoder.encode(alpha,
                    linear))) {
                Blackhole.consume(QoiDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .width());
            }
        });
    }

    /**
     * Returns a workload that encodes a natural 320x180 picture as TGA, raw and run-length encoded, top-down and
     * bottom-up, and decodes each file.
     *
     * @return the workload
     */
    private static TrainingWorkload tga() {
        var picture = picture(320, 180, true);
        var options = List.of(TgaEncoderOptions.defaults(), TgaEncoderOptions.builder().runLength(true).build(),
                TgaEncoderOptions.builder().runLength(true).topDown(true).build());
        return TrainingWorkload.of("tga", () -> {
            for (var option : options) {
                var file = TgaEncoder.encode(picture, option);
                Blackhole.consume(TgaDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .width());
            }
        });
    }

    /**
     * Returns a workload that encodes a natural 320x180 picture as Radiance HDR, with and without run-length
     * encoding, and decodes each file to packed RGBA and to float channels.
     *
     * @return the workload
     */
    private static TrainingWorkload hdr() {
        var picture = picture(320, 180, false);
        var channels = ImageDecoderOptions.builder().keepChannels(true).build();
        var options = List.of(HdrEncoderOptions.defaults(), HdrEncoderOptions.builder().runLength(false).build());
        return TrainingWorkload.of("radiance-hdr", () -> {
            for (var option : options) {
                var file = HdrEncoder.encode(picture, option);
                Blackhole.consume(HdrDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture()
                        .width());
                Blackhole.consume(HdrDecoder.decode(MediaSource.of(file), channels).picture().width());
            }
        });
    }

    /**
     * Returns a workload that encodes the macOS icon sizes of a natural 256x256 picture as ICNS in each encoding and
     * decodes every entry of each file.
     *
     * @return the workload
     */
    private static TrainingWorkload icns() {
        var picture = picture(256, 256, true);
        var options = new ArrayList<IcnsEncoderOptions>();
        for (var encoding : IcnsEncoderOptions.Encoding.values()) {
            options.add(IcnsEncoderOptions.builder().sizes(16, 32, 48, 128, 256).encoding(encoding).build());
        }
        return TrainingWorkload.of("icns", () -> {
            for (var option : options) {
                var file = IcnsEncoder.encode(picture, option);
                try (var pages = IcnsDecoder.openPages(MediaSource.of(file))) {
                    for (var page = 0; page < pages.pageCount(); page++) {
                        Blackhole.consume(pages.read(page).picture().width());
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that decodes 320x180 Photoshop documents: RGB of 8 bits in PackBits rows, of 16 bits with
     * ZIP and prediction and as a large document, gray with ZIP and raw CMYK, each with a transparency channel, and
     * an RGB document saved without a merged image, whose composite is rendered from separable and non-separable
     * blends, a masked pass-through group and a clipping group; to packed RGBA, to 16 bits and to every channel.
     *
     * @return the workload
     */
    private static TrainingWorkload psd() {
        var picture = picture(320, 180, true);
        var files = List.of(SyntheticImageFiles.psd(picture, SyntheticImageFiles.PSD_RGB, 8, 1, false),
                SyntheticImageFiles.psd(picture, SyntheticImageFiles.PSD_RGB, 16, 3, false),
                SyntheticImageFiles.psd(picture, SyntheticImageFiles.PSD_RGB, 8, 2, true),
                SyntheticImageFiles.psd(picture, SyntheticImageFiles.PSD_GRAY, 8, 2, false),
                SyntheticImageFiles.psd(picture, SyntheticImageFiles.PSD_CMYK, 8, 0, false),
                SyntheticImageFiles.psdLayered(picture));
        var options = List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().highBitDepth(true).build(),
                ImageDecoderOptions.builder().keepChannels(true).build());
        return TrainingWorkload.of("psd-decode", () -> {
            for (var file : files) {
                for (var option : options) {
                    Blackhole.consume(PsdDecoder.decode(MediaSource.of(file), option).picture().width());
                }
            }
        });
    }

    /**
     * Returns a workload that decodes DDS textures: a 256x256 texture of 32-bit texels with its mipmap levels, and
     * 256x256 surfaces of the block-compressed formats BC1 to BC7 (DXT1, DXT5 and ATI2 legacy codes and the DX10
     * formats BC4, BC6H unsigned and signed, and BC7), of half float, 10-bit and 11-bit float texels, of 24-bit depth
     * with stencil, and of the YUV formats AYUV, Y416, P010, YUY2 and Y216, to packed RGBA and the float ones also to
     * float channels.
     *
     * @return the workload
     */
    private static TrainingWorkload dds() {
        var files = List.of(SyntheticImageFiles.ddsMasks(picture(256, 256, true)),
                SyntheticImageFiles.ddsFourCc(256, 256, "DXT1", 8, 1),
                SyntheticImageFiles.ddsFourCc(256, 256, "DXT5", 16, 2),
                SyntheticImageFiles.ddsFourCc(256, 256, "ATI2", 16, 3),
                SyntheticImageFiles.ddsDx10(256, 256, 80, 8, true, 4),
                SyntheticImageFiles.ddsDx10(256, 256, 95, 16, true, 5),
                SyntheticImageFiles.ddsDx10(256, 256, 96, 16, true, 6),
                SyntheticImageFiles.ddsDx10(256, 256, 98, 16, true, 7),
                SyntheticImageFiles.ddsDx10(256, 256, 10, 8, false, 8),
                SyntheticImageFiles.ddsDx10(256, 256, 24, 4, false, 9),
                SyntheticImageFiles.ddsDx10(256, 256, 26, 4, false, 10),
                SyntheticImageFiles.ddsDx10(256, 256, 45, 4, false, 11),
                SyntheticImageFiles.ddsDx10(256, 256, 100, 4, false, 12),
                SyntheticImageFiles.ddsDx10(256, 256, 102, 8, false, 13),
                SyntheticImageFiles.ddsDx10(256, 256, 104, 3, false, 14),
                SyntheticImageFiles.ddsDx10(256, 256, 107, 2, false, 15),
                SyntheticImageFiles.ddsDx10(256, 256, 109, 4, false, 16));
        var channels = ImageDecoderOptions.builder().keepChannels(true).build();
        return TrainingWorkload.of("dds-decode", () -> {
            for (var file : files) {
                try (var pages = DdsDecoder.openPages(MediaSource.of(file))) {
                    for (var page = 0; page < pages.pageCount(); page++) {
                        Blackhole.consume(pages.read(page).picture().width());
                    }
                }
                Blackhole.consume(DdsDecoder.decode(MediaSource.of(file), channels).picture().width());
            }
        });
    }

    /**
     * Returns a workload that decodes the OpenEXR training files: every part and level of each to packed RGBA, and
     * the first part to float channels.
     *
     * @return the workload
     */
    private static TrainingWorkload exr() {
        var files = new ArrayList<byte[]>();
        for (var name : EXR_FILES) {
            files.add(TrainingStreams.bytes("exr/" + name + ".exr"));
        }
        var channels = ImageDecoderOptions.builder().keepChannels(true).build();
        return TrainingWorkload.of("exr-decode", () -> {
            for (var file : files) {
                try (var pages = ExrDecoder.openPages(MediaSource.of(file))) {
                    for (var page = 0; page < pages.pageCount(); page++) {
                        Blackhole.consume(pages.read(page).picture().width());
                    }
                }
                Blackhole.consume(ExrDecoder.decode(MediaSource.of(file), channels).picture().width());
            }
        });
    }

    /**
     * Returns a workload that decodes the JPEG XR training files to packed RGBA, to 16 bits and to every channel.
     *
     * @return the workload
     */
    private static TrainingWorkload jxr() {
        var files = new ArrayList<byte[]>();
        for (var name : JXR_FILES) {
            files.add(TrainingStreams.bytes("jxr/" + name + ".jxr"));
        }
        var options = List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().highBitDepth(true).build(),
                ImageDecoderOptions.builder().keepChannels(true).build());
        return TrainingWorkload.of("jxr-decode", () -> {
            for (var file : files) {
                for (var option : options) {
                    Blackhole.consume(JxrDecoder.decode(MediaSource.of(file), option).picture().width());
                }
            }
            Blackhole.consume(Images.decode(MediaSource.of(files.getFirst())).picture().width());
        });
    }

    /**
     * Returns a workload that encodes and decodes the synthetic natural clip with the PNG and TIFF video codecs and
     * decodes it as GIF pictures with the GIF video decoder.
     *
     * @return the workload
     */
    private static TrainingWorkload imageVideo() {
        var width = VideoStreams.WIDTH;
        var height = VideoStreams.HEIGHT;
        var clip = SyntheticVideo.natural(width, height, 8);
        var gifs = new ArrayList<Packet>();
        for (var index = 0; index < clip.size(); index++) {
            var file = GifEncoder.encode(SyntheticImages.rgba(clip.get(index), false));
            gifs.add(new Packet(1, file, index, index, 1, true));
        }
        var gif = VideoStreams.decodeRound(gifs, new GifVideoDecoder(VideoStreams.track(Codec.GIF, null),
                VideoDecoderOptions.defaults()));
        var png = VideoStreams.encodeAll(new PngVideoEncoder(width, height), clip);
        var tiff = VideoStreams.encodeAll(new TiffVideoEncoder(width, height), clip);
        var pngDecode = VideoStreams.decodeRound(png, new PngVideoDecoder(VideoStreams.track(Codec.PNG, null),
                VideoDecoderOptions.defaults()));
        var tiffDecode = VideoStreams.decodeRound(tiff, new TiffVideoDecoder(VideoStreams.track(Codec.TIFF, null),
                VideoDecoderOptions.defaults()));
        var pngEncode = VideoStreams.encodeRound(new PngVideoEncoder(width, height));
        var tiffEncode = VideoStreams.encodeRound(new TiffVideoEncoder(width, height));
        return TrainingWorkload.of("image-video-codecs", () -> {
            pngEncode.run();
            tiffEncode.run();
            pngDecode.run();
            tiffDecode.run();
            gif.run();
        });
    }

    /**
     * Returns the first frame of the synthetic natural video as a picture.
     *
     * @param width the width, even
     * @param height the height, even
     * @param alpha whether the picture has a varying alpha channel
     * @return the picture
     */
    private static RgbaPicture picture(int width, int height, boolean alpha) {
        return SyntheticImages.rgba(SyntheticVideo.natural(width, height, 1).getFirst(), alpha);
    }
}
