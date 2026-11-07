package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.raw.DngCompression;
import com.github.auties00.smalt.image.raw.DngEncoder;
import com.github.auties00.smalt.image.raw.DngEncoderOptions;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawColorSpace;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawHighlights;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the workloads of {@link TrainingFamily#RAW}, the camera raw decoder and the DNG encoder of
 * {@code smalt-raw}, on synthetic DNG (Bayer, linear raw, a staggered mosaic with profile
 * tables, and VC-5 sensor data as GoPro's GPR files store it), Nikon NEF, Canon CR2, Pentax PEF,
 * Sony ARW, Olympus ORF, Panasonic RW2, Epson ERF, Mamiya MEF, Samsung SRW, Kodak DCS, Leaf MOS,
 * Hasselblad 3FR, Minolta MRW, Canon CRW, Canon CR3, Sigma X3F and Fujifilm RAF files.
 */
final class RawWorkloads {
    /**
     * Prevents instantiation.
     */
    private RawWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        // TODO: train Phase One IIQ once the raw decoder reads its sensor data, which it rejects for want of a spec
        return List.of(
                file("raw-decode-bayer", RawWorkloads::bayer),
                file("raw-decode-linear", RawWorkloads::linear),
                file("raw-decode-hdr-profile", RawWorkloads::highDynamicRange),
                file("raw-decode-staggered", RawWorkloads::staggered),
                file("raw-decode-gpr", RawWorkloads::gpr),
                file("raw-decode-nef", RawWorkloads::nef),
                file("raw-decode-cr2", RawWorkloads::cr2),
                file("raw-decode-pef", RawWorkloads::pef),
                file("raw-decode-arw", RawWorkloads::arw),
                file("raw-decode-orf", RawWorkloads::orf),
                file("raw-decode-rw2", RawWorkloads::rw2),
                file("raw-decode-erf-mef-srw", RawWorkloads::packed),
                file("raw-decode-dcr", RawWorkloads::kodak),
                file("raw-decode-mos", RawWorkloads::leaf),
                file("raw-decode-3fr", RawWorkloads::hasselblad),
                file("raw-decode-mrw", RawWorkloads::mrw),
                file("raw-decode-crw", RawWorkloads::crw),
                file("raw-decode-cr3", RawWorkloads::cr3),
                file("raw-decode-x3f", RawWorkloads::x3f),
                file("raw-decode-raf", RawWorkloads::raf),
                file("raw-encode-dng", RawWorkloads::dng));
    }

    /**
     * Returns a workload that describes and decodes a 1024x768 Bayer DNG: with Menon demosaicing to
     * 8-bit sRGB, with bilinear demosaicing, and to 16-bit linear Rec. 2020.
     *
     * @return the workload
     */
    private static TrainingWorkload bayer() {
        var file = SyntheticRaw.bayer(SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false));
        var options = List.of(RawDecoderOptions.defaults(), RawDecoderOptions.fast(), RawDecoderOptions.linear());
        return TrainingWorkload.of("raw-decode-bayer", () -> {
            Blackhole.consume(RawDecoder.info(MediaSource.of(file)).hashCode());
            Blackhole.consume(RawDecoder.probe(MediaSource.of(file)).hashCode());
            for (var option : options) {
                Blackhole.consume(RawDecoder.decode(MediaSource.of(file), option).picture().width());
            }
        });
    }

    /**
     * Returns a workload that decodes a 640x480 Deflate-compressed linear raw DNG to 8-bit sRGB
     * and to 16-bit linear Rec. 2020.
     *
     * @return the workload
     */
    private static TrainingWorkload linear() {
        var file = SyntheticRaw.linearRaw(SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false));
        var options = List.of(RawDecoderOptions.defaults(), RawDecoderOptions.linear());
        return TrainingWorkload.of("raw-decode-linear", () -> {
            for (var option : options) {
                Blackhole.consume(RawDecoder.decode(MediaSource.of(file), option).picture().width());
            }
        });
    }

    /**
     * Returns a workload that decodes a 640x480 linear raw DNG with a high-dynamic-range camera profile to
     * floating-point ProPhoto RGB, which keeps the values above white, and to 8-bit sRGB.
     *
     * @return the workload
     */
    private static TrainingWorkload highDynamicRange() {
        var file = SyntheticRaw.highDynamicRange(SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(),
                false));
        var options = List.of(RawDecoderOptions.builder().colorSpace(RawColorSpace.PROPHOTO_RGB).floatingPoint(true)
                .build(), RawDecoderOptions.defaults());
        return TrainingWorkload.of("raw-decode-hdr-profile", () -> {
            for (var option : options) {
                Blackhole.consume(RawDecoder.decode(MediaSource.of(file), option).picture().width());
            }
        });
    }

    /**
     * Returns a workload that decodes a 640x480 staggered DNG with a gain table map and an RGB
     * table: with color-difference and bilinear demosaicing to 8-bit sRGB, and with the automatic
     * black subtraction and blended highlights.
     *
     * @return the workload
     */
    private static TrainingWorkload staggered() {
        var file = SyntheticRaw.staggered(SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false));
        var options = List.of(RawDecoderOptions.defaults(), RawDecoderOptions.fast(), RawDecoderOptions.builder()
                .autoBlack(true).highlights(RawHighlights.BLEND).build());
        return TrainingWorkload.of("raw-decode-staggered", () -> {
            Blackhole.consume(RawDecoder.decodeRaw(MediaSource.of(file)).samples().length);
            for (var option : options) {
                Blackhole.consume(RawDecoder.decode(MediaSource.of(file), option).picture().width());
            }
        });
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing a 1024x768 GPR file, a DNG whose sensor data is a VC-5 bitstream.
     *
     * @return the workload
     */
    private static TrainingWorkload gpr() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return vendor("raw-decode-gpr", List.of(SyntheticRaw.gpr(picture)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing four NEF files: 14-bit lossless of 1024x768, and 12-bit lossless, 12-bit lossy with
     * a linearization curve and a split row, and uncompressed 14-bit of 640x480.
     *
     * @return the workload
     */
    private static TrainingWorkload nef() {
        var large = picture(1024, 768);
        var small = picture(640, 480);
        return vendor("raw-decode-nef", List.of(
                SyntheticVendorTiff.nef(large, SyntheticVendorTiff.Nikon.LOSSLESS_14).file(),
                SyntheticVendorTiff.nef(small, SyntheticVendorTiff.Nikon.LOSSLESS_12).file(),
                SyntheticVendorTiff.nef(small, SyntheticVendorTiff.Nikon.LOSSY_12).file(),
                SyntheticVendorTiff.nef(small, SyntheticVendorTiff.Nikon.UNCOMPRESSED).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing a 1024x768 CR2 file, a lossless JPEG stream in three slices.
     *
     * @return the workload
     */
    private static TrainingWorkload cr2() {
        return vendor("raw-decode-cr2", List.of(SyntheticVendorTiff.cr2(picture(1024, 768)).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing two Huffman-coded PEF files: 14-bit of 1024x768 and 12-bit of 640x480.
     *
     * @return the workload
     */
    private static TrainingWorkload pef() {
        return vendor("raw-decode-pef", List.of(SyntheticVendorTiff.pef(picture(1024, 768), 14).file(),
                SyntheticVendorTiff.pef(picture(640, 480), 12).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing two ARW files: cRAW of 1024x768 and uncompressed of 640x480.
     *
     * @return the workload
     */
    private static TrainingWorkload arw() {
        return vendor("raw-decode-arw", List.of(SyntheticVendorTiff.arw(picture(1024, 768), true).file(),
                SyntheticVendorTiff.arw(picture(640, 480), false).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing two ORF files: Olympus-compressed of 1024x768 and uncompressed of 640x480.
     *
     * @return the workload
     */
    private static TrainingWorkload orf() {
        return vendor("raw-decode-orf", List.of(SyntheticVendorTiff.orf(picture(1024, 768), true).file(),
                SyntheticVendorTiff.orf(picture(640, 480), false).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing four RW2 files, one of each raw format version the decoder reads: 4 (1008x768), 5
     * (630x480), 6 (660x480) and 8 (1024x768, two tiles).
     *
     * @return the workload
     */
    private static TrainingWorkload rw2() {
        return vendor("raw-decode-rw2", List.of(
                SyntheticVendorTiff.rw2(picture(1008, 768), SyntheticVendorTiff.Panasonic.V4).file(),
                SyntheticVendorTiff.rw2(picture(630, 480), SyntheticVendorTiff.Panasonic.V5).file(),
                SyntheticVendorTiff.rw2(picture(660, 480), SyntheticVendorTiff.Panasonic.V6).file(),
                SyntheticVendorTiff.rw2(picture(1024, 768), SyntheticVendorTiff.Panasonic.V8).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing the packed 12-bit files of four formats: a 1000x750 ERF, whose white balance the
     * decoder estimates from its preview, a 1024x768 MEF, and two 1024x768 SRW files, of packed
     * samples and of 16-bit words.
     *
     * @return the workload
     */
    private static TrainingWorkload packed() {
        var picture = picture(1024, 768);
        return vendor("raw-decode-erf-mef-srw", List.of(SyntheticVendorTiff.erf(picture(1000, 750)).file(),
                SyntheticVendorTiff.mef(picture).file(), SyntheticVendorTiff.srw(picture, true).file(),
                SyntheticVendorTiff.srw(picture, false).file()));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing a 1024x768 Kodak DCS file of Kodak's compression, with a linearization curve and
     * column black offsets.
     *
     * @return the workload
     */
    private static TrainingWorkload kodak() {
        return vendor("raw-decode-dcr", List.of(SyntheticVendorTiff.kodak(picture(1024, 768))));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing two 1024x768 Leaf MOS files, lossless JPEG and uncompressed.
     *
     * @return the workload
     */
    private static TrainingWorkload leaf() {
        var picture = picture(1024, 768);
        return vendor("raw-decode-mos", List.of(SyntheticVendorTiff.leaf(picture, true),
                SyntheticVendorTiff.leaf(picture, false)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing a 1024x768 Hasselblad 3FR file, Hasselblad's predictor-8 lossless JPEG.
     *
     * @return the workload
     */
    private static TrainingWorkload hasselblad() {
        return vendor("raw-decode-3fr", List.of(SyntheticVendorTiff.hasselblad(picture(1024, 768))));
    }

    /**
     * Returns the first frame of the synthetic natural video as a picture.
     *
     * @param width the width, even
     * @param height the height, even
     * @return the picture
     */
    private static RgbaPicture picture(int width, int height) {
        return SyntheticImages.rgba(SyntheticVideo.natural(width, height, 1).getFirst(), false);
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing two 1024x768 MRW files, one of packed 12-bit samples and one of 16-bit words.
     *
     * @return the workload
     */
    private static TrainingWorkload mrw() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return vendor("raw-decode-mrw", List.of(SyntheticVendorRaw.mrw(picture, true),
                SyntheticVendorRaw.mrw(picture, false)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing a 1024x768 CRW file, whose white balance the decoder estimates from its preview.
     *
     * @return the workload
     */
    private static TrainingWorkload crw() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return vendor("raw-decode-crw", List.of(SyntheticVendorRaw.crw(picture)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing three 1024x768 CR3 files: lossless CRX, and C-RAW of codec versions 1 and 2.
     *
     * @return the workload
     */
    private static TrainingWorkload cr3() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        return vendor("raw-decode-cr3", List.of(SyntheticCr3.cr3(picture, 0, 0x100), SyntheticCr3.cr3(picture, 3, 0x100),
                SyntheticCr3.cr3(picture, 3, 0x200)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes two 640x480 X3F
     * files, one in the TRUE coding and one in the older Huffman coding.
     *
     * @return the workload
     */
    private static TrainingWorkload x3f() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false);
        return vendor("raw-decode-x3f", List.of(SyntheticVendorRaw.x3f(picture, false),
                SyntheticVendorRaw.x3f(picture, true)));
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes with Menon
     * demosaicing five RAF files: lossless and lossy compressed Bayer and X-Trans files of 1024x768,
     * and a 640x480 transposed SuperCCD SR file of bare sensor words with the second image of its
     * low-sensitivity photodiodes, which the rendering merges into the first.
     *
     * @return the workload
     */
    private static TrainingWorkload raf() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        var small = SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false);
        return vendor("raw-decode-raf", List.of(SyntheticRaf.compressed(picture, false, false),
                SyntheticRaf.compressed(picture, true, false), SyntheticRaf.compressed(picture, false, true),
                SyntheticRaf.compressed(picture, true, true), SyntheticRaf.superCcd(small)));
    }

    /**
     * Returns a workload that writes DNG files: the sensor image of a 1024x768 Bayer DNG with lossless JPEG tiles
     * and a preview, and uncompressed and with JPEG XL tiles without one, the linear raw image of a 640x480 DNG,
     * and the conversion of a 1024x768 MRW file.
     *
     * @return the workload
     */
    private static TrainingWorkload dng() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1024, 768, 1).getFirst(), false);
        var bayer = RawDecoder.decodeRaw(MediaSource.of(SyntheticRaw.bayer(picture)));
        var linear = RawDecoder.decodeRaw(MediaSource.of(SyntheticRaw.linearRaw(
                SyntheticImages.rgba(SyntheticVideo.natural(640, 480, 1).getFirst(), false))));
        var mrw = SyntheticVendorRaw.mrw(picture, true);
        var uncompressed = DngEncoderOptions.builder().compression(DngCompression.UNCOMPRESSED).preview(false).build();
        var jpegXl = DngEncoderOptions.builder().compression(DngCompression.JPEG_XL).preview(false).build();
        return TrainingWorkload.of("raw-encode-dng", () -> {
            Blackhole.consume(DngEncoder.encode(bayer).length);
            Blackhole.consume(DngEncoder.encode(bayer, uncompressed).length);
            Blackhole.consume(DngEncoder.encode(bayer, jpegXl).length);
            Blackhole.consume(DngEncoder.encode(linear).length);
            Blackhole.consume(DngEncoder.convert(MediaSource.of(mrw)).length);
        });
    }

    /**
     * Returns a workload that describes, extracts the sensor values of and decodes to 8-bit sRGB
     * each of a list of raw files.
     *
     * @param name the workload name
     * @param files the raw files
     * @return the workload
     */
    private static TrainingWorkload vendor(String name, List<byte[]> files) {
        return TrainingWorkload.of(name, () -> {
            for (var file : files) {
                Blackhole.consume(RawDecoder.info(MediaSource.of(file)).hashCode());
                Blackhole.consume(RawDecoder.decodeRaw(MediaSource.of(file)).samples().length);
                Blackhole.consume(RawDecoder.decode(MediaSource.of(file), RawDecoderOptions.defaults())
                        .picture().width());
            }
        });
    }
}
