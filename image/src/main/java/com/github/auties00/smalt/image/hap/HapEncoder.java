package com.github.auties00.smalt.image.hap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.hap.internal.BcnEncoder;
import com.github.auties00.smalt.image.hap.internal.HapSections;
import com.github.auties00.smalt.image.internal.snappy.Snappy;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into Vidvox HAP video ({@link Codec#HAP}) in the {@link HapEncoderOptions#format()}, every frame
 * an independent key frame.
 *
 * <p>Pictures are converted to 8-bit RGBA as {@link ImageOps#toRgba(Picture)} does, and their 4 by 4 blocks are
 * compressed to the format's textures: {@link HapFormat#HAP} drops alpha, {@link HapFormat#HAP_Q} codes scaled YCoCg
 * and drops alpha, {@link HapFormat#HAP_ALPHA_ONLY} codes the alpha of a picture that has it and the luma (BT.601
 * weights) of one that has not. {@link HapFormat#HAP_HDR} codes the samples of a {@link ChannelFloatPicture} of
 * {@link PixelLayout#RGB} or {@link PixelLayout#RGBA} layout as half-precision values, negative values clamped to 0,
 * and the 8-bit RGB of any other picture divided by 255. The compression is lossy, as the texture formats are; the
 * output does not depend on the thread count. Each texture is split into {@link HapEncoderOptions#chunks()} chunks of
 * whole blocks, each compressed with Snappy when {@link HapEncoderOptions#snappy()} is set and Snappy makes it smaller.
 *
 * <p>Every {@link #encode(VideoFrame, Consumer)} emits one key-frame packet carrying the frame's timestamp (or, without
 * one, the timestamp following the previous frame at the nominal frame rate) and duration; {@link #flush(Consumer)}
 * has nothing to drain. {@link #codecConfig()} holds the format's sample entry type in ASCII, which the containers that
 * carry {@link Codec#HAP} (QuickTime, AVI and Matroska) write as the sample entry type or FourCC.
 *
 * <p>Instances are not thread-safe.
 */
public final class HapEncoder implements VideoEncoder {
    /**
     * The largest frame dimension.
     */
    private static final int MAX_DIMENSION = 65535;

    private final int width;

    private final int height;

    private final HapEncoderOptions options;

    /**
     * The block columns and rows of a texture.
     */
    private final int columns;

    private final int rows;

    /**
     * The block encoders of the bands, grown as needed.
     */
    private BcnEncoder[] encoders = new BcnEncoder[0];

    /**
     * The texels of a block, one array per band.
     */
    private int[][] texels = new int[0][];

    /**
     * The half-precision texels of a block, one array per band.
     */
    private short[][] halves = new short[0][];

    /**
     * The two textures of a frame.
     */
    private final byte[][] textures = new byte[2][];

    /**
     * The compressed chunks of a texture, one array per chunk, grown as needed.
     */
    private byte[][] compressed = new byte[0][];

    /**
     * The compressed lengths of the chunks, or -1 for a chunk stored uncompressed.
     */
    private int[] compressedLengths = new int[0];

    /**
     * The Snappy compressors of the chunks.
     */
    private Snappy[] compressors = new Snappy[0];

    /**
     * The packet being built.
     */
    private byte[] out = new byte[1024];

    /**
     * The floating-point RGB samples of a HAP HDR frame converted from another layout, or {@code null} before the
     * first one.
     */
    private float[] floatSamples;

    /**
     * The number of valid bytes of {@link #out}.
     */
    private int size;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * The operation the calls of this encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs an encoder for a picture size under the {@link HapEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public HapEncoder(int width, int height) {
        this(width, height, HapEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public HapEncoder(int width, int height, HapEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.columns = (width + 3) / 4;
        this.rows = (height + 3) / 4;
        LimitChecks.checkAllocation(options.limits(), 4L * (16L * columns * rows));
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
    }

    /**
     * {@return the encoding options}
     */
    public HapEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#HAP}
     */
    @Override
    public Codec codec() {
        return Codec.HAP;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return the format's sample entry type, four ASCII bytes
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(options.format().typeBytes());
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the key-frame packet of track 0, whose presentation and decoding times are the frame's timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
        var timing = clock.resolve(frame);
        try (var _ = ParallelBands.operation(operation)) {
            var format = options.format();
            size = 0;
            switch (format) {
                case HAP -> texture(picture, HapSections.RGB_DXT1, 0);
                case HAP_ALPHA -> texture(picture, HapSections.RGBA_DXT5, 0);
                case HAP_Q -> texture(picture, HapSections.YCOCG_DXT5, 0);
                case HAP_ALPHA_ONLY -> texture(picture, HapSections.ALPHA_RGTC1, 0);
                case HAP_R -> texture(picture, HapSections.RGBA_BPTC, 0);
                case HAP_HDR -> texture(picture, HapSections.RGB_BPTC_UNSIGNED, 0);
                case HAP_Q_ALPHA -> {
                    var header = size;
                    header(0, HapSections.MULTIPLE_IMAGES);
                    var start = size;
                    texture(picture, HapSections.YCOCG_DXT5, 0);
                    texture(picture, HapSections.ALPHA_RGTC1, 1);
                    patchHeader(header, size - start);
                }
            }
        }
        clock.commit(timing.pts());
        return new Packet(0, Arrays.copyOf(out, size), timing.pts(), timing.pts(), timing.duration(), true);
    }

    /**
     * Compresses one texture of a picture and appends its section.
     *
     * @param picture the picture being encoded
     * @param format the texture format code
     * @param index the texture's index in the frame, 0 or 1
     */
    private void texture(Picture picture, int format, int index) {
        var blockBytes = format == HapSections.RGB_DXT1 || format == HapSections.ALPHA_RGTC1 ? 8 : 16;
        var length = columns * rows * blockBytes;
        if (textures[index] == null || textures[index].length < length) {
            textures[index] = new byte[length];
        }
        var texture = textures[index];
        var bands = ParallelBands.bands(rows, (long) width * height);
        ensureBands(bands);
        if (format == HapSections.RGB_BPTC_UNSIGNED) {
            var source = floatSource(picture);
            ParallelBands.run(bands, band -> {
                var encoder = encoders[band];
                var block = halves[band];
                for (var by = ParallelBands.start(band, bands, rows); by < ParallelBands.start(band + 1, bands, rows);
                     by++) {
                    for (var bx = 0; bx < columns; bx++) {
                        loadHalves(source, bx, by, block);
                        encoder.bc6h(block, texture, (by * columns + bx) * 16);
                    }
                }
            });
        } else {
            var rgba = ImageOps.toRgba(picture);
            var pixels = rgba.pixels();
            var stride = rgba.stride();
            var hasAlpha = rgba.hasAlpha();
            var luma = options.format() == HapFormat.HAP_ALPHA_ONLY && !hasAlpha;
            ParallelBands.run(bands, band -> {
                var encoder = encoders[band];
                var block = texels[band];
                for (var by = ParallelBands.start(band, bands, rows); by < ParallelBands.start(band + 1, bands, rows);
                     by++) {
                    for (var bx = 0; bx < columns; bx++) {
                        load(pixels, stride, bx, by, block);
                        var at = (by * columns + bx) * blockBytes;
                        switch (format) {
                            case HapSections.RGB_DXT1 -> encoder.color(block, texture, at);
                            case HapSections.RGBA_DXT5 -> encoder.rgba(block, texture, at);
                            case HapSections.YCOCG_DXT5 -> encoder.ycocg(block, texture, at);
                            case HapSections.RGBA_BPTC -> encoder.bc7(block, texture, at);
                            default -> {
                                for (var texel = 0; texel < 16; texel++) {
                                    var value = block[texel];
                                    block[texel] = !luma ? value >>> 24 : (77 * (value & 0xFF)
                                            + 150 * (value >>> 8 & 0xFF) + 29 * (value >>> 16 & 0xFF) + 128) >>> 8;
                                }
                                encoder.single(block, true, texture, at);
                            }
                        }
                    }
                }
            });
        }
        section(texture, length, format, blockBytes);
    }

    /**
     * Returns the floating-point RGB samples a HAP HDR frame codes.
     *
     * @param picture the picture being encoded
     * @return the samples, three per pixel with a stride of {@code width * 3}, or the picture's own samples when its
     * layout is RGB
     */
    private float[] floatSource(Picture picture) {
        if (picture instanceof ChannelFloatPicture floats && floats.layout().equals(PixelLayout.RGB)
                && floats.stride() == width * 3) {
            return floats.pixels();
        }
        if (floatSamples == null) {
            floatSamples = new float[width * height * 3];
        }
        var samples = floatSamples;
        if (picture instanceof ChannelFloatPicture floats && floats.layout().equals(PixelLayout.RGBA)) {
            var source = floats.pixels();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var from = y * floats.stride() + x * 4;
                    var to = (y * width + x) * 3;
                    samples[to] = source[from];
                    samples[to + 1] = source[from + 1];
                    samples[to + 2] = source[from + 2];
                }
            }
            return samples;
        }
        var rgba = ImageOps.toRgba(picture);
        var pixels = rgba.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var from = y * rgba.stride() + x * 4;
                var to = (y * width + x) * 3;
                for (var c = 0; c < 3; c++) {
                    samples[to + c] = (pixels[from + c] & 0xFF) / 255f;
                }
            }
        }
        return samples;
    }

    /**
     * Loads a block of RGBA pixels, repeating the last row and column past the picture's edges.
     *
     * @param pixels the RGBA samples
     * @param stride the distance in bytes between row starts
     * @param bx the block column
     * @param by the block row
     * @param block receives the 16 texels, packed as {@code 0xAABBGGRR}
     */
    private void load(byte[] pixels, int stride, int bx, int by, int[] block) {
        for (var y = 0; y < 4; y++) {
            var row = Math.min(by * 4 + y, height - 1) * stride;
            for (var x = 0; x < 4; x++) {
                var at = row + Math.min(bx * 4 + x, width - 1) * 4;
                block[y * 4 + x] = (pixels[at] & 0xFF) | (pixels[at + 1] & 0xFF) << 8 | (pixels[at + 2] & 0xFF) << 16
                        | (pixels[at + 3] & 0xFF) << 24;
            }
        }
    }

    /**
     * Loads a block of half-precision RGB texels, repeating the last row and column past the picture's edges.
     *
     * @param samples the RGB samples, three per pixel with a stride of {@code width * 3}
     * @param bx the block column
     * @param by the block row
     * @param block receives the 48 values
     */
    private void loadHalves(float[] samples, int bx, int by, short[] block) {
        for (var y = 0; y < 4; y++) {
            var row = Math.min(by * 4 + y, height - 1) * width;
            for (var x = 0; x < 4; x++) {
                var at = (row + Math.min(bx * 4 + x, width - 1)) * 3;
                for (var c = 0; c < 3; c++) {
                    var value = samples[at + c];
                    block[(y * 4 + x) * 3 + c] = Float.floatToFloat16(value > 0 ? value : 0f);
                }
            }
        }
    }

    /**
     * Appends the section of one compressed texture: a simple section for one chunk, or a section with decode
     * instructions for several.
     *
     * @param texture the compressed blocks
     * @param length the number of bytes of the texture
     * @param format the texture format code
     * @param blockBytes the bytes of a block
     */
    private void section(byte[] texture, int length, int format, int blockBytes) {
        var blocks = length / blockBytes;
        var chunks = Math.min(options.chunks(), blocks);
        ensureChunks(chunks);
        var bands = ParallelBands.bands(chunks, length);
        ParallelBands.run(bands, band -> {
            for (var chunk = ParallelBands.start(band, bands, chunks);
                 chunk < ParallelBands.start(band + 1, bands, chunks); chunk++) {
                var start = chunkStart(chunk, chunks, blocks) * blockBytes;
                var end = chunkStart(chunk + 1, chunks, blocks) * blockBytes;
                compressedLengths[chunk] = -1;
                if (options.snappy()) {
                    var bound = (int) Snappy.maxCompressedLength(end - start);
                    if (compressed[chunk] == null || compressed[chunk].length < bound) {
                        compressed[chunk] = new byte[bound];
                    }
                    var written = compressors[chunk].compress(texture, start, end - start, compressed[chunk], 0);
                    if (written < end - start) {
                        compressedLengths[chunk] = written;
                    }
                }
            }
        });
        if (chunks == 1) {
            var packed = compressedLengths[0] >= 0;
            var dataLength = packed ? compressedLengths[0] : length;
            header(dataLength, (packed ? HapSections.SNAPPY : HapSections.NONE) << 4 | format);
            append(packed ? compressed[0] : texture, 0, dataLength);
            return;
        }
        var data = 0L;
        for (var chunk = 0; chunk < chunks; chunk++) {
            data += chunkLength(chunk, chunks, blocks, blockBytes);
        }
        var tables = headerLength(chunks) + chunks + headerLength(4L * chunks) + 4L * chunks;
        var instructions = headerLength(tables) + tables;
        var total = instructions + data;
        if (total > Integer.MAX_VALUE - 64) {
            throw new MediaException.LimitExceeded("a HAP frame of " + total + " bytes");
        }
        header((int) total, HapSections.COMPLEX << 4 | format);
        header((int) tables, 0x01);
        header(chunks, 0x02);
        for (var chunk = 0; chunk < chunks; chunk++) {
            append(compressedLengths[chunk] >= 0 ? HapSections.SNAPPY : HapSections.NONE);
        }
        header(4 * chunks, 0x03);
        for (var chunk = 0; chunk < chunks; chunk++) {
            var chunkLength = chunkLength(chunk, chunks, blocks, blockBytes);
            for (var i = 0; i < 4; i++) {
                append(chunkLength >>> (8 * i));
            }
        }
        for (var chunk = 0; chunk < chunks; chunk++) {
            if (compressedLengths[chunk] >= 0) {
                append(compressed[chunk], 0, compressedLengths[chunk]);
            } else {
                var start = chunkStart(chunk, chunks, blocks) * blockBytes;
                append(texture, start, chunkStart(chunk + 1, chunks, blocks) * blockBytes - start);
            }
        }
    }

    /**
     * {@return the first block of a chunk}
     *
     * @param chunk the chunk, from 0 to {@code chunks}
     * @param chunks the number of chunks
     * @param blocks the number of blocks
     */
    private static int chunkStart(int chunk, int chunks, int blocks) {
        return (int) ((long) blocks * chunk / chunks);
    }

    /**
     * {@return the stored length of a chunk}
     *
     * @param chunk the chunk index
     * @param chunks the number of chunks
     * @param blocks the number of blocks
     * @param blockBytes the bytes of a block
     */
    private int chunkLength(int chunk, int chunks, int blocks, int blockBytes) {
        return compressedLengths[chunk] >= 0 ? compressedLengths[chunk]
                : (chunkStart(chunk + 1, chunks, blocks) - chunkStart(chunk, chunks, blocks)) * blockBytes;
    }

    /**
     * {@return the length of the header of a section of a given size}
     *
     * @param length the section size
     */
    private static int headerLength(long length) {
        return length == 0 || length > 0xFFFFFF ? 8 : 4;
    }

    /**
     * Appends a section header.
     *
     * @param length the section size, excluding the header
     * @param type the section type
     */
    private void header(int length, int type) {
        if (length == 0 || length > 0xFFFFFF) {
            append(0);
            append(0);
            append(0);
            append(type);
            for (var i = 0; i < 4; i++) {
                append(length >>> (8 * i));
            }
        } else {
            append(length);
            append(length >>> 8);
            append(length >>> 16);
            append(type);
        }
    }

    /**
     * Rewrites the 8-byte header written by {@code header(0, type)} with the section's size.
     *
     * @param at the index of the header
     * @param length the section size
     */
    private void patchHeader(int at, int length) {
        for (var i = 0; i < 4; i++) {
            out[at + 4 + i] = (byte) (length >>> (8 * i));
        }
    }

    private void append(int value) {
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) value;
    }

    private void append(byte[] data, int offset, int length) {
        if (out.length - size < length) {
            out = Arrays.copyOf(out, Math.max(out.length * 2, size + length));
        }
        System.arraycopy(data, offset, out, size, length);
        size += length;
    }

    /**
     * Makes sure every band has its block encoder and texel arrays.
     *
     * @param bands the number of bands
     */
    private void ensureBands(int bands) {
        if (encoders.length < bands) {
            var grown = Arrays.copyOf(encoders, bands);
            var grownTexels = Arrays.copyOf(texels, bands);
            var grownHalves = Arrays.copyOf(halves, bands);
            for (var band = encoders.length; band < bands; band++) {
                grown[band] = new BcnEncoder();
                grownTexels[band] = new int[16];
                grownHalves[band] = new short[48];
            }
            encoders = grown;
            texels = grownTexels;
            halves = grownHalves;
        }
    }

    /**
     * Makes sure every chunk has its compressor and length slot.
     *
     * @param chunks the number of chunks
     */
    private void ensureChunks(int chunks) {
        if (compressors.length < chunks) {
            var grown = Arrays.copyOf(compressors, chunks);
            for (var chunk = compressors.length; chunk < chunks; chunk++) {
                grown[chunk] = new Snappy();
            }
            compressors = grown;
            compressed = Arrays.copyOf(compressed, chunks);
            compressedLengths = Arrays.copyOf(compressedLengths, chunks);
        }
    }

    /**
     * Does nothing: every HAP frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * Does nothing: HAP frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
