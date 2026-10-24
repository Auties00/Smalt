package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.frame.FrameDecoder;
import com.github.auties00.smalt.image.jxl.internal.frame.Sections;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.Toc;
import com.github.auties00.smalt.image.jxl.internal.render.Blending;
import com.github.auties00.smalt.image.jxl.internal.render.FrameImage;
import com.github.auties00.smalt.image.jxl.internal.render.FrameRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the reduced-size decodes, the preview frame and the frame positions of {@link JxlCodestream} on the
 * vectors of {@code tools/testdata/jxl-features}.
 */
class JxlCodestreamTest {
    static byte[] resource(String name) throws IOException {
        try (var input = JxlCodestreamTest.class.getResourceAsStream("/com/github/auties00/smalt/image/jxl/features/"
                + name)) {
            return input.readAllBytes();
        }
    }

    /** Renders the first frame of a single-frame file from its first passes, at full size. */
    static FrameImage render(byte[] data, int passes) {
        var codestream = JxlCodestream.open(data, MediaLimits.DEFAULT);
        var header = codestream.header();
        var container = codestream.container();
        var offset = codestream.firstFrame();
        var reader = new JxlBitReader(container.codestream(), container.codestreamOffset() + (int) offset,
                container.codestreamLength() - (int) offset);
        var frame = FrameHeader.read(reader, header, false);
        var toc = Toc.read(reader, frame, offset);
        var sections = new Sections(container.codestream(), container.codestreamOffset(),
                container.codestreamLength(), toc, frame);
        var decoded = FrameDecoder.decode(sections, header, frame, null, 0, 0, MediaLimits.DEFAULT, false, passes);
        assertEquals(passes, decoded.passes());
        var rendered = FrameRenderer.render(decoded, header, new FrameRenderer.Context(new FrameImage[4], 1, 0,
                false), MediaLimits.DEFAULT);
        var canvas = Blending.blendFrame(rendered, frame, header, new FrameImage[4]);
        return canvas.colorTransformed ? canvas : FrameRenderer.transformColor(canvas, header, frame);
    }

    @ParameterizedTest(name = "{0} by {1}")
    @CsvSource({"progressive.jxl, 2, 1", "progressive.jxl, 4, 1", "progressive.jxl, 8, 0",
            "progressive-lossless.jxl, 2, 1", "progressive-lossless.jxl, 8, 0", "preview-modular.jxl, 2, -1",
            "preview-modular.jxl, 4, -1", "preview-modular.jxl, 8, 0", "gainmap.jxl, 2, -1", "gainmap.jxl, 8, 0"})
    @DisplayName("reductionPasses follows the progressive passes the frame header describes")
    void reductionPasses(String name, int factor, int passes) throws IOException {
        assertEquals(passes, JxlCodestream.open(resource(name), MediaLimits.DEFAULT).reductionPasses(factor));
    }

    @Test
    @DisplayName("a reduction by 2 is the box average of the frame rendered from its first pass")
    void firstPass() throws IOException {
        var data = resource("progressive.jxl");
        var reduced = JxlCodestream.open(data, MediaLimits.DEFAULT).decodeReduced(2);
        assertEquals(2, reduced.factor());
        var expected = JxlCodestream.reduce(render(data, 1), 2, true);
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(expected.planes[c], reduced.canvas().planes[c], 1e-6f);
        }
    }

    @Test
    @DisplayName("the LF image is close to the box average of the frame rendered without its passes")
    void lfImage() throws IOException {
        var data = resource("progressive.jxl");
        var reduced = JxlCodestream.open(data, MediaLimits.DEFAULT).decodeReduced(8);
        assertEquals(8, reduced.factor());
        assertEquals(20, reduced.canvas().width);
        assertEquals(15, reduced.canvas().height);
        var expected = JxlCodestream.reduce(render(data, 0), 8, true);
        var sum = 0.0;
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < expected.planes[c].length; i++) {
                var d = Math.min(Math.max(reduced.canvas().planes[c][i], 0), 1) - expected.planes[c][i];
                sum += d * d;
            }
        }
        var psnr = 10 * Math.log10(1 / (sum / (3.0 * expected.planes[0].length)));
        assertTrue(psnr > 30, "PSNR " + psnr);
    }

    @Test
    @DisplayName("a frame whose unsqueezed alpha channel arrives in its last pass is not reduced")
    void incomplete() throws IOException {
        var codestream = JxlCodestream.open(resource("alpha-unsqueezed.jxl"), MediaLimits.DEFAULT);
        var reduced = codestream.decodeReduced(8);
        assertEquals(1, reduced.factor());
        assertEquals(320, reduced.canvas().width);
        assertThrows(IllegalArgumentException.class, () -> codestream.decodeReduced(2));
    }

    @Test
    @DisplayName("a modular frame stored whole in its LF global section reduces to the box average of its decode")
    void global() throws IOException {
        var data = resource("preview-modular.jxl");
        var reduced = JxlCodestream.open(data, MediaLimits.DEFAULT).decodeReduced(8);
        assertEquals(8, reduced.factor());
        var expected = JxlCodestream.reduce(JxlCodestream.open(data, MediaLimits.DEFAULT).nextKeyframe().canvas(), 8,
                true);
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(expected.planes[c], reduced.canvas().planes[c], 1e-6f);
        }
    }

    @Test
    @DisplayName("decoding the preview or a reduction leaves the frame sequence where it was")
    void sequenceKept() throws IOException {
        var data = resource("preview-vardct.jxl");
        var codestream = JxlCodestream.open(data, MediaLimits.DEFAULT);
        assertTrue(codestream.hasPreview());
        var before = codestream.position();
        assertEquals(40, codestream.decodePreview().canvas().width);
        codestream.decodeReduced(8);
        assertEquals(before, codestream.position());
        var keyframe = codestream.nextKeyframe();
        assertEquals(160, keyframe.canvas().width);
        assertTrue(codestream.finished());
        var plain = JxlCodestream.open(resource("progressive.jxl"), MediaLimits.DEFAULT);
        assertFalse(plain.hasPreview());
        assertThrows(IllegalStateException.class, plain::decodePreview);
    }

    @Test
    @DisplayName("scanFrames reports where every frame starts and the counts before it, and rewinds")
    void scan() throws IOException {
        var codestream = JxlCodestream.open(resource("indexed.jxl"), MediaLimits.DEFAULT);
        var frames = codestream.scanFrames(100);
        assertEquals(8, frames.size());
        for (var i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).frameIndex());
            assertEquals(i, frames.get(i).keyframes());
            assertEquals(i - 1, frames.get(i).lastKeyframe());
        }
        assertEquals(codestream.firstFrame(), codestream.position().offset());
        codestream.restart(frames.get(5));
        var fromFive = codestream.nextKeyframe();
        codestream.rewind();
        for (var i = 0; i < 5; i++) {
            codestream.nextKeyframe();
        }
        assertArrayEquals(codestream.nextKeyframe().canvas().planes[0], fromFive.canvas().planes[0],
                "a frame that replaces the canvas decodes alike from a restart");
        assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class, () -> codestream.scanFrames(3));
    }
}
