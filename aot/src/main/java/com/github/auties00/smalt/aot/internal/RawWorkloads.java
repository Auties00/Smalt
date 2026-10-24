package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawHighlights;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the workloads of {@link TrainingFamily#RAW}, the camera raw decoder of
 * {@code smalt-raw}, on synthetic DNG (Bayer, linear raw, and a staggered mosaic with profile
 * tables), Minolta MRW, Canon CRW and Sigma X3F files.
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
        return List.of(
                file("raw-decode-bayer", RawWorkloads::bayer),
                file("raw-decode-linear", RawWorkloads::linear),
                file("raw-decode-staggered", RawWorkloads::staggered),
                file("raw-decode-mrw", RawWorkloads::mrw),
                file("raw-decode-crw", RawWorkloads::crw),
                file("raw-decode-x3f", RawWorkloads::x3f));
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
