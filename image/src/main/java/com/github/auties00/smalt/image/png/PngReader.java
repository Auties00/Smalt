package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.internal.zlib.Zlib;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Parses the chunk structure of a PNG datastream and serves the compressed image data of the
 * static image and of APNG frames.
 *
 * <p>{@link #readPrelude()} reads the signature, {@code IHDR} and every chunk before the first
 * {@code IDAT}, applying these rules:
 * <ul>
 *   <li>Critical chunks: a missing or misplaced {@code IHDR}, a second {@code IHDR} or
 *       {@code PLTE}, an invalid palette for an indexed-color image, a missing palette, a
 *       critical chunk CRC mismatch, or an {@code IEND} before image data raise
 *       {@link MediaException.Malformed}; an unknown critical chunk raises
 *       {@link MediaException.Unsupported}. A {@code PLTE} in a grayscale image is ignored, and one
 *       with more entries than the bit depth can index is truncated, as libpng does.</li>
 *   <li>Ancillary chunks with a CRC mismatch, duplicates, misplaced ones and invalid ones are
 *       skipped. Unknown ancillary chunks are skipped without reading their data.</li>
 *   <li>{@code tRNS} is honored for grayscale, truecolor and indexed-color images; a
 *       {@code tRNS} with more entries than the palette, the wrong length for its color type, or
 *       a grayscale key outside the bit depth is ignored, as libpng does.</li>
 *   <li>{@code iCCP} is inflated with its output bounded; {@code eXIf} (only before {@code IDAT},
 *       per PNG 3rd edition Table 7) supplies the Exif block and orientation.</li>
 *   <li>{@code cICP}, {@code sRGB}, {@code gAMA} and {@code cHRM} (each the first valid one before
 *       {@code PLTE} and {@code IDAT}) describe the color space with the iCCP profile, as
 *       {@link #colorProfile()} combines them; pixels are returned as stored. The {@code mDCV}
 *       and {@code cLLI} chunks (also spelled {@code mDCv} and {@code cLLi}), under the same
 *       placement rule, add their metadata to a {@code cICP} description.</li>
 *   <li>{@code pHYs} (the first one before {@code IDAT}) supplies the {@link #density()}; one of another
 *       length than 9 bytes, with a bad CRC, a zero value, a value above {@code 2^31 - 1} or an unknown unit
 *       gives none.</li>
 *   <li>{@code sBIT}, {@code bKGD} and the textual chunks other than the XMP packet are ignored.</li>
 * </ul>
 *
 * <p>Image data is served by {@link #readImageData(byte[], int, int)} from the run of consecutive
 * {@code IDAT} chunks, or from the {@code fdAT} chunks of one APNG frame, verifying each chunk's
 * CRC when its last byte has been read and checking APNG sequence numbers.
 */
final class PngReader {
    /**
     * The largest decompressed ICC profile kept, in bytes.
     */
    private static final int MAX_ICC_PROFILE = 32 << 20;

    /**
     * The largest {@code eXIf} or compressed {@code iCCP} chunk read, in bytes.
     */
    private static final int MAX_METADATA_CHUNK = 32 << 20;

    /**
     * The keyword of the {@code iTXt} chunk that carries the XMP packet, with its terminator.
     */
    private static final byte[] XMP_KEYWORD = "XML:com.adobe.xmp\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The mode in which no image data stream is open.
     */
    private static final int DATA_NONE = 0;

    /**
     * The mode serving the consecutive {@code IDAT} chunks.
     */
    private static final int DATA_IDAT = 1;

    /**
     * The mode serving the {@code fdAT} chunks of one frame.
     */
    private static final int DATA_FDAT = 2;

    /**
     * The buffered cursor over the input.
     */
    private final SourceReader in;

    /**
     * The limits bounding metadata allocations.
     */
    private final MediaLimits limits;

    /**
     * Whether the ICC profile is extracted.
     */
    private final boolean extractProfile;

    /**
     * The running CRC of the current chunk.
     */
    private final CRC32 crc;

    /**
     * Scratch space for chunk headers and CRCs.
     */
    private final byte[] scratch;

    /**
     * The buffer through which unread chunk data is fed to the CRC or discarded, allocated on
     * first use.
     */
    private byte[] drain;

    /**
     * The type of the current chunk.
     */
    private int chunkType;

    /**
     * The declared data length of the current chunk.
     */
    private long chunkLength;

    /**
     * The number of data bytes of the current chunk not read yet.
     */
    private long chunkRemaining;

    /**
     * Whether a chunk header has been read and its data and CRC are not fully consumed.
     */
    private boolean chunkOpen;

    /**
     * Whether the current chunk header has been read by a data stream that stopped in front of
     * it, so that the next {@link #nextChunk()} returns it again.
     */
    private boolean chunkPending;

    /**
     * The data stream mode, one of {@link #DATA_NONE}, {@link #DATA_IDAT}, {@link #DATA_FDAT}.
     */
    private int dataMode;

    /**
     * The next expected APNG sequence number.
     */
    private long nextSequence;

    /**
     * The validated image header.
     */
    private PngHeader header;

    /**
     * The palette as packed little-endian RGBA pixels, 256 entries, or {@code null}.
     */
    private int[] palette;

    /**
     * The number of valid palette entries.
     */
    private int paletteSize;

    /**
     * Whether a {@code PLTE} chunk was seen.
     */
    private boolean paletteSeen;

    /**
     * The grayscale or truecolor transparency key, or {@code null}.
     */
    private int[] transparencyKey;

    /**
     * Whether a valid {@code tRNS} chunk was applied.
     */
    private boolean transparency;

    /**
     * Whether an {@code iCCP} chunk was seen.
     */
    private boolean profileSeen;

    /**
     * The decompressed ICC profile, or {@code null}.
     */
    private byte[] iccProfile;

    /**
     * The Exif block, or {@code null}.
     */
    private byte[] exif;

    /**
     * The XMP packet of the first {@code iTXt} chunk with the XMP keyword, or {@code null}.
     */
    private byte[] xmp;

    /**
     * The {@code gAMA} value (the encoding gamma times 100000), or {@code 0} when absent.
     */
    private long gamma;

    /**
     * The eight {@code cHRM} values (white, red, green and blue x and y, times 100000), or
     * {@code null} when absent.
     */
    private long[] chromaticities;

    /**
     * Whether a valid {@code sRGB} chunk was seen.
     */
    private boolean srgb;

    /**
     * The four {@code cICP} bytes (primaries, transfer, matrix, full range), or {@code null} when
     * absent.
     */
    private int[] cicp;

    /**
     * The mastering display of a valid {@code mDCV} chunk, or {@code null} when absent.
     */
    private MasteringDisplay masteringDisplay;

    /**
     * The content light level of a valid {@code cLLI} chunk, or {@code null} when absent.
     */
    private ContentLightLevel contentLightLevel;

    /**
     * Whether an {@code mDCV} chunk was read.
     */
    private boolean masteringSeen;

    /**
     * Whether a {@code cLLI} chunk was read.
     */
    private boolean lightLevelSeen;

    /**
     * The orientation read from the Exif block.
     */
    private Orientation orientation;

    /**
     * The density of the {@code pHYs} chunk, or {@code null} when absent or invalid.
     */
    private PixelDensity density;

    /**
     * Whether a {@code pHYs} chunk was read.
     */
    private boolean densitySeen;

    /**
     * Whether an {@code acTL} chunk was seen.
     */
    private boolean animationControlSeen;

    /**
     * Whether a valid {@code acTL} chunk declared an animation.
     */
    private boolean animated;

    /**
     * The number of frames declared by {@code acTL}.
     */
    private int animationFrames;

    /**
     * The number of plays declared by {@code acTL}, {@code 0} for forever.
     */
    private int animationPlays;

    /**
     * The frame control preceding {@code IDAT}, or {@code null} when the static image is not a
     * frame.
     */
    private ApngFrameControl defaultFrameControl;

    /**
     * The first APNG structure violation found before {@code IDAT}, or {@code null}.
     */
    private String animationError;

    /**
     * Whether the image data read is released from a source that reads its input once, set by
     * {@link #releaseWhileReading()}.
     */
    private boolean releasing;

    /**
     * Constructs a reader positioned at the start of the datastream.
     *
     * @param source the input
     * @param limits the limits bounding metadata allocations
     * @param extractProfile whether the ICC profile is decompressed
     */
    PngReader(MediaSource source, MediaLimits limits, boolean extractProfile) {
        this.in = new SourceReader(source);
        this.limits = limits;
        this.extractProfile = extractProfile;
        this.crc = new CRC32();
        this.scratch = new byte[26];
        this.orientation = Orientation.NORMAL;
    }

    /**
     * Returns the validated image header.
     *
     * @return the header, after {@link #readPrelude()}
     */
    PngHeader header() {
        return header;
    }

    /**
     * Returns the palette as packed little-endian RGBA pixels.
     *
     * @return the 256-entry palette, or {@code null} for images without one
     */
    int[] palette() {
        return palette;
    }

    /**
     * Returns the number of valid palette entries.
     *
     * @return the entry count
     */
    int paletteSize() {
        return paletteSize;
    }

    /**
     * Returns the grayscale or truecolor transparency key.
     *
     * @return one gray sample or three truecolor samples at the image's bit depth, or
     * {@code null}
     */
    int[] transparencyKey() {
        return transparencyKey;
    }

    /**
     * Returns whether the image carries transparency: an alpha channel or a valid {@code tRNS}.
     *
     * @return whether the decoded picture has meaningful alpha
     */
    boolean hasAlpha() {
        return header.hasAlphaChannel() || transparency;
    }

    /**
     * Returns the decompressed ICC profile.
     *
     * @return the profile, or {@code null}
     */
    byte[] iccProfile() {
        return iccProfile;
    }

    /**
     * Returns the Exif block.
     *
     * @return the TIFF structure, or {@code null}
     */
    byte[] exif() {
        return exif;
    }

    /**
     * {@return the XMP packet, the UTF-8 text of the first {@code iTXt} chunk with the keyword
     * {@code XML:com.adobe.xmp}, or {@code null} when none was read}
     */
    byte[] xmp() {
        return xmp;
    }

    /**
     * {@return the density of the {@code pHYs} chunk, in {@link PixelDensity.Unit#DOTS_PER_METER} or as an
     * aspect ratio, or {@code null} when absent or invalid}
     */
    PixelDensity density() {
        return density;
    }

    /**
     * Returns the orientation from the Exif block.
     *
     * @return the orientation, {@link Orientation#NORMAL} when absent
     */
    Orientation orientation() {
        return orientation;
    }

    /**
     * Returns whether a valid {@code acTL} declared an animation.
     *
     * @return whether the datastream is an APNG
     */
    boolean animated() {
        return animated;
    }

    /**
     * Returns the number of frames declared by {@code acTL}.
     *
     * @return the frame count, positive when {@link #animated()}
     */
    int animationFrames() {
        return animationFrames;
    }

    /**
     * Returns the number of plays declared by {@code acTL}.
     *
     * @return the play count, {@code 0} for forever
     */
    int animationPlays() {
        return animationPlays;
    }

    /**
     * Returns the frame control that precedes {@code IDAT}.
     *
     * @return the control of the static image when it is the first frame, or {@code null}
     */
    ApngFrameControl defaultFrameControl() {
        return defaultFrameControl;
    }

    /**
     * Returns the first APNG structure violation found before {@code IDAT}.
     *
     * @return the description, or {@code null} when none was found
     */
    String animationError() {
        return animationError;
    }

    /**
     * Reads the signature, {@code IHDR} and every chunk up to the first {@code IDAT}, leaving
     * the reader at the start of the image data.
     *
     * @throws MediaException.Malformed if the datastream violates a rule described on the class
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     */
    void readPrelude() {
        var signature = new byte[PngChunks.SIGNATURE.length];
        if (in.remaining() < signature.length) {
            throw new MediaException.Malformed("input is too short to be a PNG datastream");
        }
        in.readFully(signature, 0, signature.length);
        if (!Arrays.equals(signature, PngChunks.SIGNATURE)) {
            throw new MediaException.Malformed("missing PNG signature");
        }
        if (!nextChunk() || chunkType != PngChunks.IHDR) {
            throw new MediaException.Malformed("PNG datastream does not start with IHDR");
        }
        if (chunkLength != 13) {
            throw new MediaException.Malformed("IHDR has invalid length " + chunkLength);
        }
        var data = readChunk(true);
        header = PngHeader.of(ByteArrays.u32be(data, 0), ByteArrays.u32be(data, 4),
                data[8] & 0xFF, data[9] & 0xFF, data[10] & 0xFF, data[11] & 0xFF, data[12] & 0xFF);
        while (true) {
            if (!nextChunk()) {
                throw new MediaException.Malformed("PNG datastream ends before its image data");
            }
            switch (chunkType) {
                case PngChunks.IDAT -> {
                    if (header.colorType() == PngHeader.PALETTE && palette == null) {
                        throw new MediaException.Malformed("indexed-color PNG has no PLTE chunk");
                    }
                    dataMode = DATA_IDAT;
                    return;
                }
                case PngChunks.IHDR -> throw new MediaException.Malformed("PNG datastream has a second IHDR");
                case PngChunks.IEND -> throw new MediaException.Malformed("PNG datastream has no IDAT chunk");
                case PngChunks.PLTE -> readPalette();
                case PngChunks.TRNS -> readTransparency();
                case PngChunks.ICCP -> readProfile();
                case PngChunks.GAMA, PngChunks.CHRM, PngChunks.SRGB, PngChunks.CICP -> readColorChunk();
                case PngChunks.MDCV, PngChunks.MDCV_DRAFT, PngChunks.CLLI, PngChunks.CLLI_DRAFT -> readHdrChunk();
                case PngChunks.EXIF -> readExif();
                case PngChunks.PHYS -> readPhysicalDimensions();
                case PngChunks.ITXT -> readText();
                case PngChunks.ACTL -> readAnimationControl();
                case PngChunks.FCTL -> readDefaultFrameControl();
                case PngChunks.FDAT -> {
                    animationError = "fdAT precedes IDAT";
                    skipChunk();
                }
                default -> skipUnknown();
            }
        }
    }

    /**
     * Reads the next chunk header, or returns the header a data stream stopped in front of.
     *
     * <p>Any chunk still open is finished first, verifying its CRC under the rules of its type.
     *
     * @return {@code true} when a chunk header was read, {@code false} at the end of the input
     * @throws MediaException.Malformed if the header is truncated or invalid
     */
    boolean nextChunk() {
        if (chunkPending) {
            chunkPending = false;
            return true;
        }
        if (chunkOpen) {
            finishChunk();
        }
        if (in.atEnd()) {
            return false;
        }
        chunkLength = in.u32be();
        in.readFully(scratch, 0, 4);
        chunkType = ByteArrays.i32be(scratch, 0);
        if (chunkLength > PngChunks.MAX_CHUNK_LENGTH) {
            throw new MediaException.Malformed("PNG chunk " + PngChunks.name(chunkType) + " has invalid length " + chunkLength);
        }
        if (!PngChunks.isValidType(chunkType)) {
            throw new MediaException.Malformed("invalid PNG chunk type 0x" + Integer.toHexString(chunkType));
        }
        chunkRemaining = chunkLength;
        chunkOpen = true;
        crc.reset();
        crc.update(scratch, 0, 4);
        return true;
    }

    /**
     * Makes this reader tell a source that reads its input once that the image data it has read
     * will not be read again, for a decode that reads the datastream once, front to back.
     */
    void releaseWhileReading() {
        releasing = true;
    }

    /**
     * Returns the type of the current chunk.
     *
     * @return the packed chunk type
     */
    int chunkType() {
        return chunkType;
    }

    /**
     * Returns the declared data length of the current chunk.
     *
     * @return the length in bytes
     */
    long chunkLength() {
        return chunkLength;
    }

    /**
     * Reads the whole data of the current (small) chunk and verifies its CRC.
     *
     * @param critical whether a CRC mismatch is fatal
     * @return the data, or {@code null} when the CRC does not match and {@code critical} is
     * {@code false}
     * @throws MediaException.Malformed if the chunk is truncated, or its CRC does not match and
     * {@code critical} is {@code true}
     */
    byte[] readChunk(boolean critical) {
        var data = in.bytes(chunkRemaining, Integer.MAX_VALUE);
        crc.update(data, 0, data.length);
        chunkRemaining = 0;
        chunkOpen = false;
        if (!crcMatches()) {
            if (critical) {
                throw new MediaException.Malformed("CRC mismatch in PNG chunk " + PngChunks.name(chunkType));
            }
            return null;
        }
        return data;
    }

    /**
     * Skips the data and CRC of the current chunk without verifying the CRC.
     */
    void skipChunk() {
        in.skip(chunkRemaining + 4);
        chunkRemaining = 0;
        chunkOpen = false;
    }

    /**
     * Skips an ancillary chunk this reader does not interpret, or rejects a critical one.
     *
     * @throws MediaException.Unsupported if the current chunk is critical
     */
    void skipUnknown() {
        if (PngChunks.isCritical(chunkType)) {
            throw new MediaException.Unsupported("png " + PngChunks.name(chunkType),
                    "unknown critical PNG chunk " + PngChunks.name(chunkType));
        }
        skipChunk();
    }

    /**
     * Reads the remaining data of the current chunk into the CRC and verifies it.
     *
     * @throws MediaException.Malformed if the chunk is truncated, or its CRC does not match and
     * the chunk is critical or carries APNG frame data
     */
    private void finishChunk() {
        if (chunkRemaining > 0 && drain == null) {
            drain = new byte[8192];
        }
        var buffer = drain;
        while (chunkRemaining > 0) {
            var count = (int) Math.min(chunkRemaining, buffer.length);
            in.readFully(buffer, 0, count);
            crc.update(buffer, 0, count);
            chunkRemaining -= count;
        }
        chunkOpen = false;
        if (!crcMatches() && (PngChunks.isCritical(chunkType) || chunkType == PngChunks.FDAT)) {
            throw new MediaException.Malformed("CRC mismatch in PNG chunk " + PngChunks.name(chunkType));
        }
    }

    /**
     * Reads the stored CRC of the current chunk and compares it with the computed one.
     *
     * @return whether they match
     * @throws MediaException.Malformed if the CRC field is truncated
     */
    private boolean crcMatches() {
        return in.u32be() == crc.getValue();
    }

    /**
     * Opens the image data stream of one APNG frame, served from the {@code fdAT} chunks that
     * follow the current position.
     */
    void beginFrameData() {
        dataMode = DATA_FDAT;
    }

    /**
     * Checks an APNG sequence number and advances the expected one.
     *
     * @param sequence the sequence number read from an {@code fcTL} or {@code fdAT} chunk
     * @throws MediaException.Malformed if it is not the next number in sequence
     */
    void checkSequence(long sequence) {
        if (sequence != nextSequence) {
            throw new MediaException.Malformed("APNG sequence number " + sequence + " where " + nextSequence + " was expected");
        }
        nextSequence++;
    }

    /**
     * Reads compressed image data from the open data stream.
     *
     * <p>In {@code IDAT} mode the stream ends at the first chunk that is not {@code IDAT}. In
     * {@code fdAT} mode the stream skips unknown ancillary chunks and ends at the next
     * {@code fcTL} or {@code IEND}; an {@code IDAT} there is malformed. The chunk that ends the
     * stream is left for the next {@link #nextChunk()}.
     *
     * @param buffer the destination
     * @param offset the index of the first byte written
     * @param length the maximum number of bytes to read, positive
     * @return the number of bytes read, or {@code -1} at the end of the stream
     * @throws MediaException.Malformed if a chunk is truncated, a CRC or sequence check fails,
     * or the chunk layout is invalid
     * @throws MediaException.Unsupported if an unknown critical chunk interrupts the stream
     */
    int readImageData(byte[] buffer, int offset, int length) {
        while (true) {
            if (dataMode == DATA_NONE) {
                return -1;
            }
            if (chunkOpen && chunkRemaining > 0) {
                var count = (int) Math.min(length, chunkRemaining);
                in.readFully(buffer, offset, count);
                crc.update(buffer, offset, count);
                chunkRemaining -= count;
                if (releasing) {
                    ForwardOnlySource.release(in.source(), in.position());
                }
                return count;
            }
            if (!nextChunk()) {
                dataMode = DATA_NONE;
                return -1;
            }
            if (dataMode == DATA_IDAT) {
                if (chunkType != PngChunks.IDAT) {
                    stopBefore();
                    return -1;
                }
                continue;
            }
            switch (chunkType) {
                case PngChunks.FDAT -> {
                    if (chunkLength < 4) {
                        throw new MediaException.Malformed("fdAT chunk of " + chunkLength + " bytes has no sequence number");
                    }
                    in.readFully(scratch, 0, 4);
                    crc.update(scratch, 0, 4);
                    chunkRemaining -= 4;
                    checkSequence(ByteArrays.u32be(scratch, 0));
                }
                case PngChunks.FCTL, PngChunks.IEND -> {
                    stopBefore();
                    return -1;
                }
                case PngChunks.IDAT -> throw new MediaException.Malformed("IDAT chunk follows APNG frame data");
                default -> skipUnknown();
            }
        }
    }

    /**
     * Ends the data stream in front of the current chunk header, which the next
     * {@link #nextChunk()} returns.
     */
    private void stopBefore() {
        chunkPending = true;
        dataMode = DATA_NONE;
    }

    /**
     * Finishes the chunk in which the image data ended, verifying its CRC, and closes the data
     * stream without reading further chunks.
     *
     * @throws MediaException.Malformed if the chunk is truncated or its CRC does not match
     */
    void finishImageData() {
        if (chunkOpen && !chunkPending) {
            finishChunk();
        }
        dataMode = DATA_NONE;
    }

    /**
     * Reads and discards the rest of the open data stream, verifying the CRCs and sequence
     * numbers of its chunks, and leaves the reader in front of the chunk that ends it.
     *
     * @throws MediaException.Malformed if a chunk is truncated or a check fails
     * @throws MediaException.Unsupported if an unknown critical chunk interrupts the stream
     */
    void drainImageData() {
        if (drain == null) {
            drain = new byte[8192];
        }
        var buffer = drain;
        while (readImageData(buffer, 0, buffer.length) >= 0) {
            // discard
        }
    }

    /**
     * Validates the whole APNG chunk structure after the prelude, without decoding any frame.
     *
     * <p>The chunks are walked by their headers only (image data is skipped, not read): every
     * {@code fcTL} must be intact, lie inside the canvas and use defined operations, sequence
     * numbers must run from 0 without gaps across {@code fcTL} and {@code fdAT}, every frame after
     * the static image must have at least one {@code fdAT}, no {@code fdAT} may precede the first
     * {@code fcTL} after {@code IDAT}, the {@code IDAT} chunks must be consecutive, and the number
     * of frames must equal the {@code acTL} count. A missing {@code IEND} is tolerated.
     *
     * @throws MediaException.Malformed if any rule is violated or a chunk is truncated
     * @throws MediaException.Unsupported if an unknown critical chunk follows the image data
     */
    void validateAnimation() {
        if (animationError != null) {
            throw new MediaException.Malformed("invalid APNG: " + animationError);
        }
        var width = header.width();
        var height = header.height();
        var frames = 0;
        var frameHasData = true;
        var acceptsFrameData = false;
        var afterImageData = false;
        if (defaultFrameControl != null) {
            checkSequence(defaultFrameControl.sequence());
            if (defaultFrameControl.x() != 0 || defaultFrameControl.y() != 0
                    || defaultFrameControl.width() != width || defaultFrameControl.height() != height) {
                throw new MediaException.Malformed("APNG default image frame does not cover the canvas");
            }
            defaultFrameControl.validate(width, height);
            frames = 1;
        }
        skipChunk();
        dataMode = DATA_NONE;
        while (nextChunk()) {
            switch (chunkType) {
                case PngChunks.IDAT -> {
                    if (afterImageData) {
                        throw new MediaException.Malformed("IDAT chunks are not consecutive");
                    }
                    skipChunk();
                    continue;
                }
                case PngChunks.FCTL -> {
                    if (chunkLength != ApngFrameControl.LENGTH) {
                        throw new MediaException.Malformed("fcTL has invalid length " + chunkLength);
                    }
                    var data = readChunk(false);
                    if (data == null) {
                        throw new MediaException.Malformed("CRC mismatch in fcTL");
                    }
                    var control = ApngFrameControl.parse(data);
                    checkSequence(control.sequence());
                    control.validate(width, height);
                    if (!frameHasData) {
                        throw new MediaException.Malformed("APNG frame " + (frames - 1) + " has no fdAT chunk");
                    }
                    frames++;
                    frameHasData = false;
                    acceptsFrameData = true;
                }
                case PngChunks.FDAT -> {
                    if (!acceptsFrameData) {
                        throw new MediaException.Malformed("fdAT chunk without a preceding fcTL");
                    }
                    if (chunkLength < 4) {
                        throw new MediaException.Malformed("fdAT chunk of " + chunkLength + " bytes has no sequence number");
                    }
                    checkSequence(in.u32be());
                    chunkRemaining -= 4;
                    skipChunk();
                    frameHasData = true;
                }
                case PngChunks.IEND -> {
                    skipChunk();
                    finishValidation(frames, frameHasData);
                    return;
                }
                default -> skipUnknown();
            }
            afterImageData = true;
        }
        finishValidation(frames, frameHasData);
    }

    /**
     * Checks the frame count found by {@link #validateAnimation()}.
     *
     * @param frames the number of frames found
     * @param frameHasData whether the last frame has data
     * @throws MediaException.Malformed if the last frame has no data or the count differs from
     * {@code acTL}
     */
    private void finishValidation(int frames, boolean frameHasData) {
        if (!frameHasData) {
            throw new MediaException.Malformed("APNG frame " + (frames - 1) + " has no fdAT chunk");
        }
        if (frames != animationFrames) {
            throw new MediaException.Malformed("acTL declares " + animationFrames + " frames but the datastream holds "
                    + frames);
        }
    }

    /**
     * Reads and validates {@code PLTE}.
     *
     * @throws MediaException.Malformed if the chunk is a duplicate, or invalid for an
     * indexed-color image
     */
    private void readPalette() {
        if (paletteSeen) {
            throw new MediaException.Malformed("PNG datastream has a second PLTE");
        }
        paletteSeen = true;
        var colorType = header.colorType();
        if (colorType == PngHeader.GRAY || colorType == PngHeader.GRAY_ALPHA) {
            skipChunk();
            return;
        }
        if (chunkLength == 0 || chunkLength % 3 != 0 || chunkLength > 768) {
            if (colorType == PngHeader.PALETTE) {
                throw new MediaException.Malformed("PLTE has invalid length " + chunkLength);
            }
            skipChunk();
            return;
        }
        var data = readChunk(true);
        if (colorType != PngHeader.PALETTE) {
            return;
        }
        var count = Math.min(data.length / 3, 1 << header.bitDepth());
        palette = new int[256];
        for (var i = 0; i < count; i++) {
            palette[i] = PngRowConverter.pack(data[i * 3], data[i * 3 + 1], data[i * 3 + 2], 0xFF);
        }
        paletteSize = count;
    }

    /**
     * Reads {@code tRNS} and applies it when valid for the color type.
     */
    private void readTransparency() {
        if (transparency || chunkLength > 256) {
            skipChunk();
            return;
        }
        var colorType = header.colorType();
        if (colorType == PngHeader.PALETTE && palette == null) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        var depth = header.bitDepth();
        var maximum = (1 << depth) - 1;
        switch (colorType) {
            case PngHeader.GRAY -> {
                if (data.length == 2) {
                    var gray = ByteArrays.u16be(data, 0);
                    if (gray <= maximum) {
                        transparencyKey = new int[]{gray};
                        transparency = true;
                    }
                }
            }
            case PngHeader.RGB -> {
                if (data.length == 6) {
                    var red = ByteArrays.u16be(data, 0);
                    var green = ByteArrays.u16be(data, 2);
                    var blue = ByteArrays.u16be(data, 4);
                    if (red <= maximum && green <= maximum && blue <= maximum) {
                        transparencyKey = new int[]{red, green, blue};
                        transparency = true;
                    }
                }
            }
            case PngHeader.PALETTE -> {
                if (data.length > 0 && data.length <= paletteSize) {
                    for (var i = 0; i < data.length; i++) {
                        palette[i] = (palette[i] & 0x00FF_FFFF) | (data[i] & 0xFF) << 24;
                    }
                    transparency = true;
                }
            }
            default -> {
            }
        }
    }

    /**
     * Reads a {@code gAMA}, {@code cHRM}, {@code sRGB} or {@code cICP} chunk, keeping the first
     * valid one of each type that precedes {@code PLTE}.
     */
    private void readColorChunk() {
        var expected = switch (chunkType) {
            case PngChunks.GAMA -> 4;
            case PngChunks.CHRM -> 32;
            case PngChunks.SRGB -> 1;
            default -> 4;
        };
        var seen = switch (chunkType) {
            case PngChunks.GAMA -> gamma != 0;
            case PngChunks.CHRM -> chromaticities != null;
            case PngChunks.SRGB -> srgb;
            default -> cicp != null;
        };
        if (seen || paletteSeen || chunkLength != expected) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        switch (chunkType) {
            case PngChunks.GAMA -> gamma = ByteArrays.u32be(data, 0);
            case PngChunks.CHRM -> {
                var values = new long[8];
                for (var i = 0; i < 8; i++) {
                    values[i] = ByteArrays.u32be(data, i * 4);
                }
                chromaticities = values;
            }
            case PngChunks.SRGB -> srgb = (data[0] & 0xFF) <= 3;
            default -> cicp = new int[]{data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF};
        }
    }

    /**
     * Reads an {@code mDCV} or {@code cLLI} chunk, keeping the first valid one of each kind that
     * precedes {@code PLTE}.
     */
    private void readHdrChunk() {
        var mastering = chunkType == PngChunks.MDCV || chunkType == PngChunks.MDCV_DRAFT;
        if ((mastering ? masteringSeen : lightLevelSeen) || paletteSeen || chunkLength != (mastering ? 24 : 8)) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        if (mastering) {
            masteringSeen = true;
            var points = new Chromaticity[4];
            for (var i = 0; i < 4; i++) {
                points[i] = new Chromaticity(ByteArrays.u16be(data, i * 4) / 50000.0,
                        ByteArrays.u16be(data, i * 4 + 2) / 50000.0);
            }
            masteringDisplay = new MasteringDisplay(points[0], points[1], points[2], points[3],
                    ByteArrays.u32be(data, 16) / 10000.0, ByteArrays.u32be(data, 20) / 10000.0);
        } else {
            lightLevelSeen = true;
            contentLightLevel = new ContentLightLevel(candelas(ByteArrays.u32be(data, 0)),
                    candelas(ByteArrays.u32be(data, 4)));
        }
    }

    /**
     * Converts a light level in units of 0.0001 candelas per square meter to whole candelas.
     *
     * @param value the stored value
     * @return the level rounded to the nearest candela, saturating at 65535
     */
    private static int candelas(long value) {
        return (int) Math.min(0xFFFF, (value + 5000) / 10000);
    }

    /**
     * Returns the color space the image declares.
     *
     * <p>The chunks are combined as the PNG specification (third edition) ranks them: a
     * {@code cICP} with RGB code points and supported primaries and transfer comes first, then a
     * valid {@code iCCP} profile, then {@code sRGB}, then {@code gAMA} and {@code cHRM}. A
     * {@code cICP} description carries the {@code mDCV} and {@code cLLI} metadata. A
     * {@code gAMA} alone uses the BT.709 primaries and a D65 white, a {@code cHRM} alone a gamma of
     * 2.2; a pair within rounding of sRGB ({@code gAMA} {@code 45455 +- 500} and the BT.709
     * primaries within {@code 0.001}) declares sRGB itself, as almost every PNG writer means it.
     * Invalid values are ignored.
     *
     * @return the profile, or {@code null} when the image declares nothing but sRGB
     */
    ColorProfile colorProfile() {
        if (cicp != null && cicp[2] == 0) {
            try {
                var color = new ColorInfo(MatrixCoefficients.IDENTITY, cicp[3] == 1 ? ColorRange.FULL : ColorRange.LIMITED,
                        ColorPrimaries.of(cicp[0]), TransferCharacteristics.of(cicp[1]))
                        .withMasteringDisplay(masteringDisplay).withContentLightLevel(contentLightLevel);
                if (ColorPrimaries.of(cicp[0]).code() == cicp[0] && TransferCharacteristics.of(cicp[1]).code() == cicp[1]) {
                    var profile = ColorProfile.of(color);
                    return profile.isSrgb() ? null : profile;
                }
            } catch (MediaException exception) {
                // an unsupported cICP falls back to the other chunks
            }
        }
        if (iccProfile != null) {
            try {
                return ColorProfile.parse(iccProfile);
            } catch (MediaException exception) {
                // a damaged iCCP falls back to the other chunks
            }
        }
        if (srgb) {
            return null;
        }
        var decoding = gamma > 0 && gamma <= 10_000_000 ? 100000.0 / gamma : 0;
        var nearSrgbGamma = gamma == 0 || Math.abs(gamma - 45455) <= 500;
        double[] chroma = null;
        if (chromaticities != null) {
            chroma = new double[8];
            for (var i = 0; i < 8; i++) {
                chroma[i] = chromaticities[i] / 100000.0;
            }
        }
        var nearSrgbPrimaries = chroma == null || isNear(chroma, new double[]{0.3127, 0.3290, 0.64, 0.33, 0.30, 0.60, 0.15, 0.06});
        if (nearSrgbGamma && nearSrgbPrimaries) {
            return null;
        }
        try {
            if (chroma == null) {
                chroma = new double[]{0.3127, 0.3290, 0.64, 0.33, 0.30, 0.60, 0.15, 0.06};
            }
            var white = new Chromaticity(chroma[0], chroma[1]);
            var red = new Chromaticity(chroma[2], chroma[3]);
            var green = new Chromaticity(chroma[4], chroma[5]);
            var blue = new Chromaticity(chroma[6], chroma[7]);
            return ColorProfile.rgb(red, green, blue, white, decoding > 0 ? decoding : 2.2);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Returns whether two lists of chromaticity coordinates agree within {@code 0.001}.
     *
     * @param actual the coordinates
     * @param expected the reference coordinates
     * @return {@code true} if every pair agrees
     */
    private static boolean isNear(double[] actual, double[] expected) {
        for (var i = 0; i < expected.length; i++) {
            if (Math.abs(actual[i] - expected[i]) > 0.001) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads {@code iCCP} and decompresses its profile when it is valid and in place.
     */
    private void readProfile() {
        if (profileSeen || paletteSeen || !extractProfile || chunkLength > MAX_METADATA_CHUNK) {
            skipChunk();
            return;
        }
        profileSeen = true;
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        var nameEnd = -1;
        for (var i = 0; i < Math.min(data.length, 80); i++) {
            if (data[i] == 0) {
                nameEnd = i;
                break;
            }
        }
        if (nameEnd < 1 || nameEnd + 2 > data.length || data[nameEnd + 1] != 0) {
            return;
        }
        iccProfile = Zlib.inflate(data, nameEnd + 2, data.length - nameEnd - 2,
                (int) Math.min(MAX_ICC_PROFILE, limits.maxBytes()));
    }

    /**
     * Reads {@code pHYs}: two 31-bit pixel counts per unit and the unit, 0 for none and 1 for the meter.
     */
    private void readPhysicalDimensions() {
        if (densitySeen || chunkLength != 9) {
            densitySeen = true;
            skipChunk();
            return;
        }
        densitySeen = true;
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        var x = ByteArrays.u32be(data, 0);
        var y = ByteArrays.u32be(data, 4);
        var unit = data[8] & 0xFF;
        if (x == 0 || y == 0 || x > Integer.MAX_VALUE || y > Integer.MAX_VALUE || unit > 1) {
            return;
        }
        density = unit == 1 ? PixelDensity.ofDotsPerMeter(x, y) : PixelDensity.ofAspectRatio(x, y);
    }

    /**
     * Reads {@code eXIf} and extracts the orientation.
     */
    private void readExif() {
        if (exif != null || chunkLength > Math.min(MAX_METADATA_CHUNK, limits.maxBytes())) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        var tiff = TiffExif.stripExifPrefix(data);
        var little = tiff.length >= TiffHeader.SIZE && tiff[0] == 'I' && tiff[1] == 'I';
        var bigEndian = tiff.length >= TiffHeader.SIZE && tiff[0] == 'M' && tiff[1] == 'M';
        if ((little || bigEndian) && ByteArrays.u16(tiff, 2, little) == 42) {
            exif = tiff;
            orientation = ExifReader.orientation(tiff);
        }
    }

    /**
     * Reads an {@code iTXt} chunk and keeps the text of the first one whose keyword is
     * {@code XML:com.adobe.xmp} as the XMP packet; the text of a compressed chunk is inflated.
     * A chunk that is corrupt or larger than the metadata bound is skipped.
     */
    private void readText() {
        if (xmp != null || chunkLength > Math.min(MAX_METADATA_CHUNK, limits.maxBytes())) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null || data.length < XMP_KEYWORD.length + 2) {
            return;
        }
        for (var i = 0; i < XMP_KEYWORD.length; i++) {
            if (data[i] != XMP_KEYWORD[i]) {
                return;
            }
        }
        var at = XMP_KEYWORD.length;
        var compressed = data[at] == 1;
        if (data[at] > 1 || data[at + 1] != 0) {
            return;
        }
        at += 2;
        for (var field = 0; field < 2; field++) {
            while (at < data.length && data[at] != 0) {
                at++;
            }
            if (at >= data.length) {
                return;
            }
            at++;
        }
        var bound = (int) Math.min(MAX_METADATA_CHUNK, limits.maxBytes());
        xmp = compressed ? Zlib.inflate(data, at, data.length - at, bound) : Arrays.copyOfRange(data, at, data.length);
    }

    /**
     * Reads the chunks after the image data of a datastream without animation frames, up to
     * {@code IEND}, for the metadata a writer placed there; a datastream that ends early or
     * holds a corrupt chunk there ends the scan.
     */
    void readTrailingMetadata() {
        if (xmp != null || animated()) {
            return;
        }
        try {
            while (nextChunk() && chunkType != PngChunks.IEND) {
                if (chunkType == PngChunks.ITXT) {
                    readText();
                    if (xmp != null) {
                        return;
                    }
                } else {
                    skipChunk();
                }
            }
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            // metadata after the image data is not needed to show the image
        }
    }

    /**
     * Reads {@code acTL}.
     */
    private void readAnimationControl() {
        if (animationControlSeen) {
            animationError = "more than one acTL";
            skipChunk();
            return;
        }
        animationControlSeen = true;
        if (chunkLength != 8) {
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            return;
        }
        var frames = ByteArrays.u32be(data, 0);
        var plays = ByteArrays.u32be(data, 4);
        if (frames == 0 || frames > Integer.MAX_VALUE) {
            return;
        }
        animated = true;
        animationFrames = (int) frames;
        animationPlays = (int) Math.min(plays, Integer.MAX_VALUE);
    }

    /**
     * Reads an {@code fcTL} that precedes {@code IDAT}.
     */
    private void readDefaultFrameControl() {
        if (defaultFrameControl != null) {
            animationError = "more than one fcTL precedes IDAT";
            skipChunk();
            return;
        }
        if (chunkLength != ApngFrameControl.LENGTH) {
            animationError = "fcTL has invalid length " + chunkLength;
            skipChunk();
            return;
        }
        var data = readChunk(false);
        if (data == null) {
            animationError = "CRC mismatch in fcTL";
            return;
        }
        defaultFrameControl = ApngFrameControl.parse(data);
    }
}
