package com.github.auties00.smalt.image.webp.bench;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.Fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sticker-shaped benchmark inputs: 512x512 RGBA pictures with a soft circular alpha mask, as a
 * cut-out sticker looks once fitted onto its square canvas.
 *
 * <p>Natural content comes from the Xiph {@code 720p5994_stockholm_ter} clip cached by the
 * conformance kit ({@code Conformance.directory()}); synthetic content from ffmpeg's
 * {@code testsrc2}. Every fixture is generated once by ffmpeg and cached by {@link Fixtures}.
 */
public final class StickerFixtures {
    /** The sticker side. */
    public static final int SIDE = 512;

    /** The alpha mask: opaque inside a 250-pixel circle with a 64-level soft edge. */
    private static final String MASK = "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
            + ":a='clip((250-hypot(X-256,Y-256))*4,0,255)'";

    private StickerFixtures() {
        throw new AssertionError();
    }

    /** Returns the cached Xiph stockholm clip. */
    public static Path stockholm() {
        var path = Path.of(com.github.auties00.smalt.testkit.Conformance.directory().toString(),
                "720p5994_stockholm_ter.y4m");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing " + path + "; fetch it with the conformance kit first");
        }
        return path;
    }

    /**
     * Returns a still sticker.
     *
     * @param kind {@code photo} (stockholm frame), {@code grainy} (the same with film grain, over
     *             100 KB at q90 so the size search runs) or {@code cartoon} (testsrc2)
     */
    public static RgbaPicture still(String kind) {
        var input = switch (kind) {
            case "photo", "grainy" -> List.of("-i", stockholm().toString());
            case "cartoon" -> List.of("-f", "lavfi", "-i", "testsrc2=size=512x512:rate=1");
            default -> throw new IllegalArgumentException(kind);
        };
        var filter = switch (kind) {
            case "photo" -> "crop=720:720:280:0,scale=512:512:flags=lanczos," + MASK;
            case "grainy" -> "crop=720:720:280:0,scale=512:512:flags=lanczos,noise=alls=40:allf=u," + MASK;
            default -> MASK;
        };
        var raw = Fixtures.read(Fixtures.ffmpeg("raw", input, List.of("-frames:v", "1", "-vf", filter,
                "-f", "rawvideo", "-pix_fmt", "rgba")));
        return RgbaPicture.of(SIDE, SIDE, SIDE * 4, raw, true);
    }

    /**
     * Returns the frames of an animated sticker at 10 frames per second.
     *
     * @param kind   {@code video} (a panning stockholm crop: every frame changes everywhere),
     *               {@code cartoon} (testsrc2: moving elements on a static background) or
     *               {@code gif} (the stockholm crop through a 256-color dithered palette, as a
     *               GIF source decodes)
     * @param frames the frame count
     */
    public static List<RgbaPicture> animation(String kind, int frames) {
        var input = switch (kind) {
            case "video", "gif" -> List.of("-i", stockholm().toString());
            case "cartoon" -> List.of("-f", "lavfi", "-i", "testsrc2=size=512x512:rate=10");
            default -> throw new IllegalArgumentException(kind);
        };
        var filter = switch (kind) {
            case "video" -> "select='not(mod(n,6))',crop=720:720:280:0,scale=512:512:flags=lanczos," + MASK;
            case "gif" -> "select='not(mod(n,6))',crop=720:720:280:0,scale=512:512:flags=lanczos,split[a][b];"
                    + "[a]palettegen=max_colors=255:stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=3,"
                    + MASK;
            default -> MASK;
        };
        var raw = Fixtures.read(Fixtures.ffmpeg("raw", input, List.of("-frames:v", Integer.toString(frames),
                "-filter_complex", filter, "-f", "rawvideo", "-pix_fmt", "rgba")));
        var frameBytes = SIDE * SIDE * 4;
        var count = raw.length / frameBytes;
        var result = new ArrayList<RgbaPicture>(count);
        for (var i = 0; i < count; i++) {
            var pixels = new byte[frameBytes];
            System.arraycopy(raw, i * frameBytes, pixels, 0, frameBytes);
            result.add(RgbaPicture.of(SIDE, SIDE, SIDE * 4, pixels, true));
        }
        return result;
    }
}
