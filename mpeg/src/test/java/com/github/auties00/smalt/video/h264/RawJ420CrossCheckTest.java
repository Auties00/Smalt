package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the plane order of the QuickTime {@code j420} raw frames of the MPEG file format conformance file
 * {@code a10-foreman_QCIF-raw.mp4} against the H.264 coding of the same sequence, {@code a5-foreman-AVC.mp4}: the
 * first raw frame must match one of the first coded frames closely in all three planes, and not with its chroma
 * planes swapped. No reference decoder reads {@code j420}, so the coded sequence stands in for one.
 */
@DisplayName("j420 raw frames against the H.264 coding of the same sequence")
class RawJ420CrossCheckTest {
    private static List<YuvPicture> decode(String name, int frames) throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed(name));
        var pictures = new ArrayList<YuvPicture>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(t -> t.codec().kind()
                    == com.github.auties00.smalt.track.TrackKind.VIDEO).findFirst().orElseThrow();
            try (var decoder = VideoDecoders.create(track)) {
                for (var packet = demuxer.read(); packet != null && pictures.size() < frames; packet = demuxer.read()) {
                    if (packet.trackId() != track.id()) {
                        continue;
                    }
                    decoder.decode(packet, frame -> pictures.add(copy((YuvPicture) frame.picture())));
                }
            }
        }
        return pictures;
    }

    private static YuvPicture copy(YuvPicture picture) {
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false,
                picture.color());
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), copy.y(), y * copy.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), copy.u(), y * copy.uvStride(), picture.chromaWidth());
            System.arraycopy(picture.v(), y * picture.uvStride(), copy.v(), y * copy.uvStride(), picture.chromaWidth());
        }
        return copy;
    }

    private static double psnr(byte[] a, byte[] b) {
        var sum = 0.0;
        for (var i = 0; i < a.length; i++) {
            var d = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += d * d;
        }
        var mse = sum / a.length;
        return mse == 0 ? 99 : 10 * StrictMath.log10(255.0 * 255.0 / mse);
    }

    @Test
    @DisplayName("Y, Cb and Cr planes in that order")
    void planeOrder() throws IOException {
        var raw = decode("mpegff-a10-foreman_QCIF-raw.mp4", 1);
        var coded = decode("mpegff-a5-foreman-AVC.mp4", 30);
        assertEquals(1, raw.size());
        var frame = raw.getFirst();
        assertEquals(176, frame.width());
        assertEquals(144, frame.height());
        var best = coded.stream().max((x, y) -> Double.compare(psnr(frame.y(), x.y()), psnr(frame.y(), y.y())))
                .orElseThrow();
        var luma = psnr(frame.y(), best.y());
        var u = psnr(frame.u(), best.u());
        var v = psnr(frame.v(), best.v());
        var swappedU = psnr(frame.u(), best.v());
        var swappedV = psnr(frame.v(), best.u());
        assertTrue(luma > 30, "luma PSNR " + luma);
        assertTrue(u > 30 && v > 30, "chroma PSNR " + u + ", " + v);
        assertTrue(u > swappedU + 3 && v > swappedV + 3, "swapped chroma PSNR " + swappedU + ", " + swappedV);
    }
}
