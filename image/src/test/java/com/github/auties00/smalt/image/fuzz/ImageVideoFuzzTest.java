package com.github.auties00.smalt.image.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.Codec;

/**
 * Fuzz targets of the video decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#video}).
 */
class ImageVideoFuzzTest {
    @FuzzTest
    void mjpeg(byte[] data) {
        FuzzMedia.video(Codec.MJPEG, data);
    }

    @FuzzTest
    void jpeg2000(byte[] data) {
        FuzzMedia.video(Codec.JPEG2000, data);
    }

    @FuzzTest
    void vp8(byte[] data) {
        FuzzMedia.video(Codec.VP8, data);
    }
}
