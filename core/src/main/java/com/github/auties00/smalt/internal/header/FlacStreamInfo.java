package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Holds the fields of a FLAC {@code STREAMINFO} metadata block.
 *
 * <p>The block body is 34 bytes: 16-bit minimum and maximum block sizes, 24-bit minimum and
 * maximum frame sizes (zero when unknown), a 20-bit sample rate, a 3-bit channel count minus one,
 * a 5-bit sample size minus one, a 36-bit total sample count (zero when unknown) and the 16-byte
 * MD5 of the decoded samples (all zero when unknown).
 *
 * <p>Instances compare their MD5 signatures by content.
 *
 * @param minBlockSize the smallest block size of the stream, in samples
 * @param maxBlockSize the largest block size of the stream, in samples
 * @param minFrameSize the smallest frame size in bytes, or {@code 0} when unknown
 * @param maxFrameSize the largest frame size in bytes, or {@code 0} when unknown
 * @param sampleRate the sampling rate in hertz, positive once {@link #parse} accepted it
 * @param channels the channel count, from 1 to 8
 * @param bitsPerSample the sample size in bits, from 4 to 32 once {@link #parse} accepted it
 * @param totalSamples the number of samples per channel, or {@code 0} when unknown
 * @param md5 the MD5 of the decoded samples, all zero when unknown
 */
public record FlacStreamInfo(int minBlockSize, int maxBlockSize, int minFrameSize, int maxFrameSize,
                             int sampleRate, int channels, int bitsPerSample, long totalSamples, byte[] md5) {
    /**
     * The size of a {@code STREAMINFO} block body in bytes.
     */
    public static final int SIZE = 34;

    /**
     * Parses a {@code STREAMINFO} block body and checks the fields a decoder relies on.
     *
     * @param data the bytes
     * @param offset the index of the first body byte; 34 bytes must be available
     * @return the parsed fields
     * @throws MediaException.Malformed if the sample rate is zero, the sample size is below four bits or the maximum
     * block size is below 16
     */
    public static FlacStreamInfo parse(byte[] data, int offset) {
        var info = read(data, offset);
        if (info.sampleRate == 0) {
            throw new MediaException.Malformed("FLAC STREAMINFO declares a sample rate of zero");
        }
        if (info.bitsPerSample < 4) {
            throw new MediaException.Malformed("FLAC STREAMINFO declares " + info.bitsPerSample + " bits per sample");
        }
        if (info.maxBlockSize < 16) {
            throw new MediaException.Malformed("FLAC STREAMINFO declares a maximum block size of " + info.maxBlockSize);
        }
        return info;
    }

    /**
     * Reads the fields of a {@code STREAMINFO} block body without checking them.
     *
     * @param data the bytes
     * @param offset the index of the first body byte; 34 bytes must be available
     * @return the fields as stored, which may be out of their documented ranges
     * @throws IndexOutOfBoundsException if fewer than 34 bytes follow {@code offset}
     */
    public static FlacStreamInfo read(byte[] data, int offset) {
        Objects.checkFromIndexSize(offset, SIZE, data.length);
        var packed = ByteArrays.i64be(data, offset + 10);
        var md5 = Arrays.copyOfRange(data, offset + 18, offset + 34);
        return new FlacStreamInfo(ByteArrays.u16be(data, offset), ByteArrays.u16be(data, offset + 2),
                ByteArrays.u24be(data, offset + 4), ByteArrays.u24be(data, offset + 7), (int) (packed >>> 44),
                (int) (packed >>> 41 & 0x7) + 1, (int) (packed >>> 36 & 0x1F) + 1, packed & 0xF_FFFF_FFFFL, md5);
    }

    /**
     * {@return whether the MD5 signature is set, {@code false} when every signature byte is zero}
     */
    public boolean hasMd5() {
        for (var value : md5) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two blocks are equal when every field is, the MD5 signatures compared by content.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof FlacStreamInfo that && minBlockSize == that.minBlockSize
                && maxBlockSize == that.maxBlockSize && minFrameSize == that.minFrameSize
                && maxFrameSize == that.maxFrameSize && sampleRate == that.sampleRate && channels == that.channels
                && bitsPerSample == that.bitsPerSample && totalSamples == that.totalSamples
                && Arrays.equals(md5, that.md5);
    }

    @Override
    public int hashCode() {
        var hash = 31 * minBlockSize + maxBlockSize;
        hash = 31 * hash + minFrameSize;
        hash = 31 * hash + maxFrameSize;
        hash = 31 * hash + sampleRate;
        hash = 31 * hash + channels;
        hash = 31 * hash + bitsPerSample;
        hash = 31 * hash + Long.hashCode(totalSamples);
        return 31 * hash + Arrays.hashCode(md5);
    }

    @Override
    public String toString() {
        return "FlacStreamInfo[minBlockSize=" + minBlockSize + ", maxBlockSize=" + maxBlockSize + ", minFrameSize="
                + minFrameSize + ", maxFrameSize=" + maxFrameSize + ", sampleRate=" + sampleRate + ", channels="
                + channels + ", bitsPerSample=" + bitsPerSample + ", totalSamples=" + totalSamples + ", md5="
                + HexFormat.of().formatHex(md5) + "]";
    }
}
