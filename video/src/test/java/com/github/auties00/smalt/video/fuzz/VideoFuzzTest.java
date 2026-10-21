package com.github.auties00.smalt.video.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.Codec;

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
}
