package com.github.auties00.smalt.capture.internal.convert;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.Arrays;

/**
 * Decodes the Motion-JPEG frames of a camera and reduces them to planar 4:2:0 of even size.
 *
 * <p>{@link MjpegDecoder} supplies the example Huffman tables of ITU-T T.81 Annex K to frames that
 * omit their DHT segments, as USB Video Class cameras do. A 4:2:0 picture is passed through, the
 * chroma rows of the common 4:2:2 output are averaged in pairs with a {@link ConversionKernel}, a
 * monochrome picture gets neutral chroma, and 4:4:4 is converted with {@link ImageOps#toYuv}. An
 * odd width or height is cropped by one pixel; the color is the decoded one. A reduced picture
 * may share planes with the decoder and with this reducer, so it is valid until the next call.
 * Instances are not thread-safe.
 */
public final class MjpegReducer {
    private final MjpegDecoder decoder = new MjpegDecoder();
    private final ConversionKernel kernel;
    private byte[] u = new byte[0];
    private byte[] v = new byte[0];

    /**
     * Constructs a reducer.
     *
     * @param kernel the kernel averaging 4:2:2 chroma
     */
    public MjpegReducer(ConversionKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Decodes one frame and reduces it to 4:2:0.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the reduced picture, valid until the next call
     * @throws MediaException.Malformed if the frame is not a decodable JPEG image or is smaller than
     * 2x2
     * @throws MediaException.Unsupported if the frame uses a JPEG feature the decoder lacks
     * @throws MediaException.LimitExceeded if the frame exceeds the default limits
     */
    public YuvPicture decode(byte[] data, int offset, int length) {
        var decoded = (YuvPicture) decoder.decodeFrame(data, offset, length);
        var width = decoded.width() & ~1;
        var height = decoded.height() & ~1;
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("a Motion-JPEG frame of " + decoded.width() + "x" + decoded.height()
                    + " has no even part");
        }
        return switch (decoded.chromaFormat()) {
            case YUV420 -> wrap(decoded, width, height, decoded.u(), decoded.v(), decoded.uvStride());
            case YUV422 -> {
                var chromaWidth = width / 2;
                ensureChroma(chromaWidth * (height / 2));
                var stride = decoded.uvStride();
                for (var row = 0; row < height / 2; row++) {
                    var upper = 2 * row * stride;
                    kernel.averageRows(decoded.u(), upper, decoded.u(), upper + stride, chromaWidth, u, row * chromaWidth);
                    kernel.averageRows(decoded.v(), upper, decoded.v(), upper + stride, chromaWidth, v, row * chromaWidth);
                }
                yield wrap(decoded, width, height, u, v, chromaWidth);
            }
            case MONOCHROME -> {
                var chromaWidth = width / 2;
                var samples = chromaWidth * (height / 2);
                ensureChroma(samples);
                Arrays.fill(u, 0, samples, (byte) 128);
                Arrays.fill(v, 0, samples, (byte) 128);
                yield wrap(decoded, width, height, u, v, chromaWidth);
            }
            case YUV444 -> {
                var reduced = ImageOps.toYuv(decoded, ChromaFormat.YUV420, decoded.color());
                yield wrap(reduced, width, height, reduced.u(), reduced.v(), reduced.uvStride());
            }
        };
    }

    private void ensureChroma(int samples) {
        if (u.length < samples) {
            u = new byte[samples];
            v = new byte[samples];
        }
    }

    /**
     * Returns a 4:2:0 picture of a size over the luma of {@code source} and the given chroma planes.
     */
    private static YuvPicture wrap(YuvPicture source, int width, int height, byte[] u, byte[] v, int uvStride) {
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(source.color())
                .luma(source.y(), source.yStride())
                .chroma(u, v, uvStride)
                .build();
    }
}
