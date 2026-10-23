package com.github.auties00.smalt.video.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzGainMaps;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.Codec;

import java.util.List;
import java.util.Optional;

/**
 * Fuzz targets of the video decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#video}), and of the
 * AVIF image decoder (see {@link FuzzMedia#image}).
 */
class VideoFuzzTest {
    @FuzzTest
    void vp9(byte[] data) {
        FuzzMedia.video(Codec.VP9, data);
    }

    @FuzzTest
    void av1(byte[] data) {
        FuzzMedia.video(Codec.AV1, data);
    }

    @FuzzTest
    void proRes(byte[] data) {
        FuzzMedia.video(Codec.PRORES, data);
    }

    @FuzzTest
    void dnxhd(byte[] data) {
        FuzzMedia.video(Codec.DNXHD, data);
    }

    @FuzzTest
    void avif(byte[] data) {
        FuzzMedia.image(ImageFormat.AVIF, data);
    }

    @FuzzTest
    void avifGainMap(byte[] data) {
        FuzzGainMaps.run("avifGainMap", data, new FuzzGainMaps.Decoder() {
            @Override
            public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
                return AvifDecoder.decode(source, options);
            }

            @Override
            public Optional<GainMap> gainMap(MediaSource source, ImageDecoderOptions options) {
                return AvifDecoder.decodeGainMap(source, options);
            }

            @Override
            public List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
                return AvifDecoder.auxiliaryImages(source, limits);
            }

            @Override
            public DecodedImage auxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
                return AvifDecoder.decodeAuxiliaryImage(source, index, options);
            }
        });
    }
}
