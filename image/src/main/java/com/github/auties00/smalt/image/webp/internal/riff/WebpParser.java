/* Ports libwebp 1.6.0 (src/dec/webp_dec.c, src/demux/demux.c). Copyright 2010 Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.riff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Parses the RIFF structure of a WebP file from a {@link MediaSource} without decoding pixels.
 *
 * <p>The parser reads only chunk headers and the few bytes of bitstream headers it needs to learn
 * the image dimensions, so probing never reads pixel data. It accepts the simple format (a single
 * {@code VP8 } or {@code VP8L} chunk) and the extended format ({@code VP8X} first, followed by
 * optional {@code ICCP}, {@code ANIM}, the image or the {@code ANMF} frames, {@code EXIF},
 * {@code XMP } and unknown chunks). Bytes after the end declared by the RIFF header are ignored;
 * a RIFF header declaring more bytes than the input holds is rejected as truncated.
 *
 * @implNote This implementation follows the acceptance rules of libwebp:
 * {@code ParseHeadersInternal} of {@code src/dec/webp_dec.c} for still images (the last
 * {@code ALPH} chunk before the bitstream is used, the {@code VP8X} chunk must be exactly 10
 * bytes, and the canvas must match the bitstream) and {@code WebPDemux} of
 * {@code src/demux/demux.c} for animations (reserved flag bits rejected, {@code ANIM} before any
 * {@code ANMF}, frame sub-chunks parsed until the first unexpected one, with the remainder of the
 * frame payload parsed as top-level chunks, and frames without image data skipped). A frame's
 * size is that of its bitstream, as in libwebp; the size fields of the {@code ANMF} header only
 * bound its area.
 */
public final class WebpParser {
    /**
     * The {@code VP8X} flag of animations.
     */
    public static final int FLAG_ANIMATION = 0x02;

    /**
     * The {@code VP8X} flag of an {@code XMP } chunk.
     */
    public static final int FLAG_XMP = 0x04;

    /**
     * The {@code VP8X} flag of an {@code EXIF} chunk.
     */
    public static final int FLAG_EXIF = 0x08;

    /**
     * The {@code VP8X} flag of transparency.
     */
    public static final int FLAG_ALPHA = 0x10;

    /**
     * The {@code VP8X} flag of an {@code ICCP} chunk.
     */
    public static final int FLAG_ICCP = 0x20;

    /**
     * The union of the defined {@code VP8X} flags.
     */
    public static final int VALID_FLAGS = 0x3E;

    /**
     * The largest chunk payload size the format allows.
     */
    public static final long MAX_CHUNK_PAYLOAD = 0xFFFFFFFFL - 8 - 1;

    /**
     * The payload size of a {@code VP8X} chunk.
     */
    public static final int VP8X_SIZE = 10;

    /**
     * The payload size of an {@code ANIM} chunk.
     */
    public static final int ANIM_SIZE = 6;

    /**
     * The size of the header fields of an {@code ANMF} payload.
     */
    public static final int ANMF_HEADER_SIZE = 16;

    /**
     * The number of bytes of a VP8 key frame header.
     */
    private static final int VP8_HEADER_SIZE = 10;

    /**
     * The largest canvas or frame area libwebp accepts, exclusive.
     */
    private static final long MAX_IMAGE_AREA = 1L << 32;

    /**
     * The buffer size used to read headers.
     */
    private static final int READ_BUFFER = 4096;

    /**
     * Prevents instantiation.
     */
    private WebpParser() {
        throw new AssertionError();
    }

    /**
     * Parses the structure of a WebP file, validating every frame of an animation.
     *
     * @param source the file
     * @param limits the resource bounds checked against the canvas and the frame count
     * @return the layout
     * @throws MediaException.Malformed if the structure is invalid or truncated
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds
     * {@code limits}
     */
    public static Layout parse(MediaSource source, MediaLimits limits) {
        var in = new SourceReader(source, READ_BUFFER);
        var size = source.size();
        if (size >= 0 && size < 12) {
            throw new MediaException.Malformed("WebP file of " + size + " bytes is shorter than its RIFF header");
        }
        if (in.fourCc() != FourCc.RIFF) {
            throw new MediaException.Malformed("not a RIFF file");
        }
        var riffSize = in.u32le();
        if (in.fourCc() != FourCc.WEBP) {
            throw new MediaException.Malformed("RIFF form type is not WEBP");
        }
        if (riffSize < 12) {
            throw new MediaException.Malformed("RIFF size " + riffSize + " holds no chunk");
        }
        if (riffSize > MAX_CHUNK_PAYLOAD) {
            throw new MediaException.Malformed("RIFF size " + riffSize + " is too large");
        }
        if (size >= 0 && riffSize > size - 8) {
            throw new MediaException.Malformed("WebP file truncated: RIFF declares " + (riffSize + 8)
                    + " bytes, the input holds " + size);
        }
        var layout = new Layout();
        layout.riffEnd = 8 + riffSize;
        layout.fileSize = size;
        var tag = in.fourCc();
        var chunkSize = in.u32le();
        if (tag == FourCc.VP8X) {
            if (chunkSize != VP8X_SIZE) {
                throw new MediaException.Malformed("VP8X chunk of " + chunkSize + " bytes, expected 10");
            }
            layout.extended = true;
            layout.flags = (int) in.u32le();
            layout.canvasWidth = 1 + in.u24le();
            layout.canvasHeight = 1 + in.u24le();
            if ((long) layout.canvasWidth * layout.canvasHeight >= MAX_IMAGE_AREA) {
                throw new MediaException.Malformed("canvas " + layout.canvasWidth + "x" + layout.canvasHeight + " is too large");
            }
            LimitChecks.checkPicture(limits, layout.canvasWidth, layout.canvasHeight);
            if ((layout.flags & FLAG_ANIMATION) != 0) {
                parseAnimation(in, layout, limits);
            } else {
                parseExtendedStill(in, layout, riffSize);
            }
        } else if (tag == FourCc.VP8 || tag == FourCc.VP8L) {
            parseImageChunk(in, layout, 12, tag, chunkSize, riffSize);
            layout.canvasWidth = layout.imageWidth;
            layout.canvasHeight = layout.imageHeight;
            LimitChecks.checkPicture(limits, layout.canvasWidth, layout.canvasHeight);
            scanTrailingMetadata(in, layout);
        } else {
            throw new MediaException.Malformed("WebP file starts with an unexpected " + FourCc.toString(tag) + " chunk");
        }
        return layout;
    }

    /**
     * Parses the chunks of an extended still image up to and including the bitstream chunk,
     * then scans the rest for metadata.
     *
     * @param in the reader, positioned after the {@code VP8X} chunk
     * @param layout the layout being filled
     * @param riffSize the RIFF payload size
     */
    private static void parseExtendedStill(SourceReader in, Layout layout, long riffSize) {
        var pos = 12L + 8 + VP8X_SIZE;
        var totalSize = 4L + 8 + VP8X_SIZE;
        while (true) {
            in.seek(pos);
            var tag = in.fourCc();
            var chunkSize = in.u32le();
            if (chunkSize > MAX_CHUNK_PAYLOAD) {
                throw new MediaException.Malformed("chunk size " + chunkSize + " is too large");
            }
            var diskSize = (8 + chunkSize + 1) & ~1L;
            totalSize += diskSize;
            if (totalSize > riffSize) {
                throw new MediaException.Malformed(FourCc.toString(tag) + " chunk overruns the RIFF payload");
            }
            if (tag == FourCc.VP8 || tag == FourCc.VP8L) {
                parseImageChunk(in, layout, pos, tag, chunkSize, riffSize);
                if (layout.imageWidth != layout.canvasWidth || layout.imageHeight != layout.canvasHeight) {
                    throw new MediaException.Malformed("canvas " + layout.canvasWidth + "x" + layout.canvasHeight
                            + " does not match the image " + layout.imageWidth + "x" + layout.imageHeight);
                }
                scanTrailingMetadata(in, layout);
                return;
            }
            if (layout.fileSize >= 0 && pos + diskSize > layout.fileSize) {
                throw new MediaException.Malformed(FourCc.toString(tag) + " chunk truncated");
            }
            if (tag == FourCc.ALPH) {
                layout.alpha = new ChunkRef(pos + 8, chunkSize);
            } else {
                recordMetadata(layout, tag, pos, chunkSize);
            }
            pos += diskSize;
        }
    }

    /**
     * Parses the bitstream chunk of a still image and reads the dimensions from its header.
     *
     * @param in the reader
     * @param layout the layout being filled
     * @param pos the offset of the chunk header
     * @param tag the chunk tag, {@code VP8 } or {@code VP8L}
     * @param chunkSize the chunk payload size
     * @param riffSize the RIFF payload size
     */
    private static void parseImageChunk(SourceReader in, Layout layout, long pos, int tag, long chunkSize, long riffSize) {
        if (chunkSize > riffSize - 12) {
            throw new MediaException.Malformed(FourCc.toString(tag) + " chunk of " + chunkSize + " bytes exceeds the RIFF payload");
        }
        if (layout.fileSize >= 0 && pos + 8 + chunkSize > layout.fileSize) {
            throw new MediaException.Malformed(FourCc.toString(tag) + " chunk truncated");
        }
        layout.image = new ChunkRef(pos + 8, chunkSize);
        layout.lossless = tag == FourCc.VP8L;
        var header = readBitstreamHeader(in, pos + 8, chunkSize, layout.lossless);
        layout.imageWidth = header[0];
        layout.imageHeight = header[1];
        layout.alphaHint = header[2] != 0;
        layout.nextChunk = pos + 8 + chunkSize + (chunkSize & 1);
    }

    /**
     * Reads and validates the header of a bitstream chunk payload.
     *
     * @param in the reader
     * @param payload the offset of the payload
     * @param chunkSize the payload size
     * @param lossless whether the payload is VP8L
     * @return {@code {width, height, alphaHint}}
     */
    private static int[] readBitstreamHeader(SourceReader in, long payload, long chunkSize, boolean lossless) {
        in.seek(payload);
        if (lossless) {
            if (chunkSize < Vp8lDecoder.HEADER_SIZE) {
                throw new MediaException.Malformed("VP8L chunk of " + chunkSize + " bytes is shorter than its header");
            }
            var bytes = new byte[Vp8lDecoder.HEADER_SIZE];
            in.readFully(bytes, 0, bytes.length);
            return Vp8lDecoder.readHeader(bytes, 0, bytes.length);
        }
        if (chunkSize < VP8_HEADER_SIZE) {
            throw new MediaException.Malformed("VP8 chunk of " + chunkSize + " bytes is shorter than its header");
        }
        var bytes = new byte[VP8_HEADER_SIZE];
        in.readFully(bytes, 0, bytes.length);
        var dims = vp8Dimensions(bytes, chunkSize);
        return new int[]{dims[0], dims[1], 0};
    }

    /**
     * Validates a VP8 key frame header and returns its dimensions.
     *
     * @param header the first 10 bytes of the frame
     * @param chunkSize the frame size, which the first partition must not reach
     * @return {@code {width, height}}
     * @throws MediaException.Malformed if the header is not a shown key frame of a known version
     */
    public static int[] vp8Dimensions(byte[] header, long chunkSize) {
        var bits = (header[0] & 0xFF) | (header[1] & 0xFF) << 8 | (header[2] & 0xFF) << 16;
        if ((header[3] & 0xFF) != 0x9D || (header[4] & 0xFF) != 0x01 || (header[5] & 0xFF) != 0x2A) {
            throw new MediaException.Malformed("VP8 start code missing");
        }
        if ((bits & 1) != 0) {
            throw new MediaException.Malformed("VP8 frame is not a key frame");
        }
        if (((bits >> 1) & 7) > 3) {
            throw new MediaException.Malformed("VP8 frame has an unknown version");
        }
        if (((bits >> 4) & 1) == 0) {
            throw new MediaException.Malformed("VP8 key frame is not shown");
        }
        if ((bits >>> 5) >= chunkSize) {
            throw new MediaException.Malformed("VP8 first partition exceeds the chunk");
        }
        var width = ((header[7] & 0xFF) << 8 | (header[6] & 0xFF)) & 0x3FFF;
        var height = ((header[9] & 0xFF) << 8 | (header[8] & 0xFF)) & 0x3FFF;
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("VP8 frame has a zero dimension");
        }
        return new int[]{width, height};
    }

    /**
     * Scans the chunks after the image for metadata, stopping silently at the first inconsistent
     * chunk header.
     *
     * @param in the reader
     * @param layout the layout being filled
     */
    private static void scanTrailingMetadata(SourceReader in, Layout layout) {
        var pos = layout.nextChunk;
        while (pos + 8 <= layout.riffEnd && (layout.fileSize < 0 || pos + 8 <= layout.fileSize)) {
            in.seek(pos);
            var tag = in.fourCc();
            var chunkSize = in.u32le();
            var diskSize = (8 + chunkSize + 1) & ~1L;
            if (chunkSize > MAX_CHUNK_PAYLOAD || pos + 8 + chunkSize > layout.riffEnd
                    || (layout.fileSize >= 0 && pos + 8 + chunkSize > layout.fileSize)) {
                return;
            }
            recordMetadata(layout, tag, pos, chunkSize);
            pos += diskSize;
        }
    }

    /**
     * Records the first {@code ICCP}, {@code EXIF} and {@code XMP } chunks.
     *
     * @param layout the layout being filled
     * @param tag the chunk tag
     * @param pos the offset of the chunk header
     * @param chunkSize the chunk payload size
     */
    private static void recordMetadata(Layout layout, int tag, long pos, long chunkSize) {
        if (tag == FourCc.ICCP && layout.iccp == null) {
            layout.iccp = new ChunkRef(pos + 8, chunkSize);
        } else if (tag == FourCc.EXIF && layout.exif == null) {
            layout.exif = new ChunkRef(pos + 8, chunkSize);
        } else if (tag == FourCc.XMP && layout.xmp == null) {
            layout.xmp = new ChunkRef(pos + 8, chunkSize);
        }
    }

    /**
     * Parses and validates the chunks of an animation, counting its frames.
     *
     * @param in the reader, positioned after the {@code VP8X} chunk
     * @param layout the layout being filled
     * @param limits the resource bounds checked against the frame count
     */
    private static void parseAnimation(SourceReader in, Layout layout, MediaLimits limits) {
        if ((layout.flags & 0xFF & ~VALID_FLAGS) != 0) {
            throw new MediaException.Malformed("VP8X has reserved flag bits set: 0x" + Integer.toHexString(layout.flags & 0xFF));
        }
        layout.animated = true;
        layout.loopCount = 1;
        layout.backgroundColor = 0xFFFFFFFF;
        layout.framesStart = 12L + 8 + VP8X_SIZE;
        var cursor = new AnimationCursor(layout);
        var frames = 0;
        var coversCanvas = true;
        Frame frame;
        while ((frame = cursor.next(in)) != null) {
            ++frames;
            LimitChecks.checkFrames(limits, frames);
            if (frame.xOffset() != 0 || frame.yOffset() != 0 || frame.width() != layout.canvasWidth
                    || frame.height() != layout.canvasHeight) {
                coversCanvas = false;
            }
        }
        if (frames == 0) {
            throw new MediaException.Malformed("animation holds no frame");
        }
        if (!cursor.sawAnim) {
            throw new MediaException.Malformed("animation has no ANIM chunk");
        }
        layout.frameCount = frames;
        layout.framesCoverCanvas = coversCanvas;
    }

    /**
     * Walks the chunks of an animation and yields its frames in order.
     *
     * <p>The same walk validates the file when it is opened and yields the frames while it is
     * played, so both passes accept exactly the same input.
     */
    public static final class AnimationCursor {
        /**
         * The layout of the file.
         */
        private final Layout layout;

        /**
         * The offset of the next top-level chunk.
         */
        private long pos;

        /**
         * Whether an {@code ANIM} chunk was read.
         */
        private boolean sawAnim;

        /**
         * Constructs a cursor at the first chunk after {@code VP8X}.
         *
         * @param layout the layout of the file
         */
        public AnimationCursor(Layout layout) {
            this.layout = layout;
            this.pos = layout.framesStart;
        }

        /**
         * Reads chunks up to and including the next frame.
         *
         * @param in the reader
         * @return the next frame, or {@code null} at the end of the animation
         * @throws MediaException.Malformed if a chunk is invalid
         */
        public Frame next(SourceReader in) {
            var riffEnd = layout.riffEnd;
            while (pos < riffEnd) {
                if (riffEnd - pos < 8) {
                    throw new MediaException.Malformed("truncated chunk header at offset " + pos);
                }
                in.seek(pos);
                var tag = in.fourCc();
                var chunkSize = in.u32le();
                if (chunkSize > MAX_CHUNK_PAYLOAD) {
                    throw new MediaException.Malformed("chunk size " + chunkSize + " is too large");
                }
                var padded = chunkSize + (chunkSize & 1);
                if (padded > riffEnd - pos - 8) {
                    throw new MediaException.Malformed(FourCc.toString(tag) + " chunk overruns the RIFF payload");
                }
                if (layout.fileSize >= 0 && pos + 8 + padded > layout.fileSize) {
                    throw new MediaException.Malformed(FourCc.toString(tag) + " chunk truncated");
                }
                if (tag == FourCc.VP8X) {
                    throw new MediaException.Malformed("second VP8X chunk");
                } else if (tag == FourCc.ALPH || tag == FourCc.VP8 || tag == FourCc.VP8L) {
                    throw new MediaException.Malformed("image chunk outside of a frame in an animation");
                } else if (tag == FourCc.ANIM) {
                    if (padded < ANIM_SIZE) {
                        throw new MediaException.Malformed("ANIM chunk of " + chunkSize + " bytes is too short");
                    }
                    if (!sawAnim) {
                        sawAnim = true;
                        layout.backgroundColor = (int) in.u32le();
                        layout.loopCount = in.u16le();
                    }
                    pos += 8 + padded;
                } else if (tag == FourCc.ANMF) {
                    if (!sawAnim) {
                        throw new MediaException.Malformed("ANMF chunk before the ANIM chunk");
                    }
                    var frame = readFrame(in, pos, padded);
                    if (frame != null) {
                        return frame;
                    }
                } else {
                    recordMetadata(layout, tag, pos, chunkSize);
                    pos += 8 + padded;
                }
            }
            return null;
        }

        /**
         * Parses one {@code ANMF} chunk and its image sub-chunks.
         *
         * @param in the reader
         * @param chunkStart the offset of the {@code ANMF} header
         * @param padded the padded payload size
         * @return the frame, or {@code null} when the chunk holds no image data
         */
        private Frame readFrame(SourceReader in, long chunkStart, long padded) {
            if (padded < ANMF_HEADER_SIZE) {
                throw new MediaException.Malformed("ANMF chunk of " + padded + " bytes is too short");
            }
            var xOffset = 2 * in.u24le();
            var yOffset = 2 * in.u24le();
            var declaredWidth = 1 + in.u24le();
            var declaredHeight = 1 + in.u24le();
            var duration = in.u24le();
            var bits = in.u8();
            if ((long) declaredWidth * declaredHeight >= MAX_IMAGE_AREA) {
                throw new MediaException.Malformed("frame " + declaredWidth + "x" + declaredHeight + " is too large");
            }
            var payloadSize = padded - ANMF_HEADER_SIZE;
            var start = chunkStart + 8 + ANMF_HEADER_SIZE;
            var sub = start;
            ChunkRef alpha = null;
            ChunkRef image = null;
            var lossless = false;
            var alphaHint = false;
            var width = 0;
            var height = 0;
            var riffEnd = layout.riffEnd;
            while (sub < riffEnd) {
                if (riffEnd - sub < 8) {
                    throw new MediaException.Malformed("truncated frame chunk header at offset " + sub);
                }
                in.seek(sub);
                var tag = in.fourCc();
                var size = in.u32le();
                if (size > MAX_CHUNK_PAYLOAD) {
                    throw new MediaException.Malformed("chunk size " + size + " is too large");
                }
                var subPadded = size + (size & 1);
                if (subPadded > riffEnd - sub - 8) {
                    throw new MediaException.Malformed(FourCc.toString(tag) + " chunk overruns the RIFF payload");
                }
                if (tag == FourCc.ALPH && alpha == null) {
                    alpha = new ChunkRef(sub + 8, size);
                    if (image != null) {
                        throw new MediaException.Malformed("ALPH chunk after the frame bitstream");
                    }
                } else if ((tag == FourCc.VP8 || tag == FourCc.VP8L) && image == null) {
                    if (tag == FourCc.VP8L && alpha != null) {
                        throw new MediaException.Malformed("ALPH chunk before a VP8L bitstream");
                    }
                    lossless = tag == FourCc.VP8L;
                    var header = readBitstreamHeader(in, sub + 8, size, lossless);
                    width = header[0];
                    height = header[1];
                    alphaHint = header[2] != 0;
                    image = new ChunkRef(sub + 8, size);
                } else {
                    break;
                }
                sub += 8 + subPadded;
            }
            if (sub - start > payloadSize) {
                throw new MediaException.Malformed("frame sub-chunks overrun the ANMF chunk");
            }
            pos = sub;
            if (alpha == null && image == null) {
                return null;
            }
            if (image == null) {
                throw new MediaException.Malformed("frame has alpha but no bitstream");
            }
            if ((long) xOffset + width > layout.canvasWidth || (long) yOffset + height > layout.canvasHeight) {
                throw new MediaException.Malformed("frame " + width + "x" + height + " at " + xOffset + "," + yOffset
                        + " exceeds the canvas " + layout.canvasWidth + "x" + layout.canvasHeight);
            }
            return new Frame(xOffset, yOffset, width, height, duration, (bits & 1) != 0, (bits & 2) == 0,
                    alpha, image, lossless, alphaHint);
        }
    }

    /**
     * Locates a chunk payload.
     *
     * @param offset the offset of the first payload byte
     * @param size the payload size, without padding
     */
    public record ChunkRef(long offset, long size) {
    }

    /**
     * Describes one animation frame.
     *
     * @param xOffset the left edge on the canvas, even
     * @param yOffset the top edge on the canvas, even
     * @param width the frame width from its bitstream
     * @param height the frame height from its bitstream
     * @param duration the display time in milliseconds
     * @param disposeBackground whether the frame area is cleared to transparent after display
     * @param blend whether the frame is alpha-blended onto the canvas
     * @param alpha the {@code ALPH} payload, or {@code null}
     * @param image the bitstream payload
     * @param lossless whether the bitstream is VP8L
     * @param alphaHint the alpha hint of a VP8L header
     */
    public record Frame(int xOffset, int yOffset, int width, int height, int duration, boolean disposeBackground,
                        boolean blend, ChunkRef alpha, ChunkRef image, boolean lossless, boolean alphaHint) {
        /**
         * Returns whether the frame declares transparency: an {@code ALPH} chunk or the VP8L
         * alpha hint.
         *
         * @return whether the frame may hold non-opaque pixels
         */
        public boolean hasAlpha() {
            return alpha != null || (lossless && alphaHint);
        }
    }

    /**
     * Holds the parsed structure of a WebP file.
     */
    public static final class Layout {
        /**
         * The input size, or {@code -1} when unknown.
         */
        public long fileSize;

        /**
         * The offset one past the RIFF payload.
         */
        public long riffEnd;

        /**
         * Whether the file uses the extended format.
         */
        public boolean extended;

        /**
         * The {@code VP8X} flags, or {@code 0} for the simple format.
         */
        public int flags;

        /**
         * The canvas width.
         */
        public int canvasWidth;

        /**
         * The canvas height.
         */
        public int canvasHeight;

        /**
         * Whether the file is an animation.
         */
        public boolean animated;

        /**
         * The bitstream payload of a still image.
         */
        public ChunkRef image;

        /**
         * The {@code ALPH} payload of a still lossy image, or {@code null}.
         */
        public ChunkRef alpha;

        /**
         * Whether the still image is VP8L.
         */
        public boolean lossless;

        /**
         * The alpha hint of a VP8L header.
         */
        public boolean alphaHint;

        /**
         * The width of the still image bitstream.
         */
        public int imageWidth;

        /**
         * The height of the still image bitstream.
         */
        public int imageHeight;

        /**
         * The offset of the chunk after the still image.
         */
        public long nextChunk;

        /**
         * The offset of the first chunk after {@code VP8X} in an animation.
         */
        public long framesStart;

        /**
         * The number of frames of an animation.
         */
        public int frameCount;

        /**
         * Whether every frame of an animation covers the whole canvas.
         */
        public boolean framesCoverCanvas;

        /**
         * The loop count of an animation as stored in {@code ANIM}: the total number of plays,
         * {@code 0} meaning forever.
         */
        public int loopCount;

        /**
         * The background color of an animation, in {@code ANIM} byte order (B, G, R, A).
         */
        public int backgroundColor;

        /**
         * The first {@code ICCP} payload, or {@code null}.
         */
        public ChunkRef iccp;

        /**
         * The first {@code EXIF} payload, or {@code null}.
         */
        public ChunkRef exif;

        /**
         * The first {@code XMP } payload, or {@code null}.
         */
        public ChunkRef xmp;

        /**
         * Returns the number of frames: {@link #frameCount} for animations, 1 otherwise.
         *
         * @return the frame count
         */
        public int frames() {
            return animated ? frameCount : 1;
        }

        /**
         * Returns whether the image declares transparency, following libwebp's feature rules.
         *
         * @return for animations the {@code VP8X} alpha flag; for VP8L the header's alpha hint or
         * an {@code ALPH} chunk; for VP8 the {@code VP8X} alpha flag or an {@code ALPH}
         * chunk
         */
        public boolean declaresAlpha() {
            if (animated) {
                return (flags & FLAG_ALPHA) != 0;
            }
            if (lossless) {
                return alphaHint || alpha != null;
            }
            return (flags & FLAG_ALPHA) != 0 || alpha != null;
        }
    }
}
