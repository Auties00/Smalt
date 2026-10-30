package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

/**
 * One essence track of a file package, as the header metadata describes it, with the reading state the demuxer
 * keeps for it.
 */
final class MxfStream {
    /**
     * The element key bytes 12 to 15 that carry the track's essence.
     */
    int trackNumber;

    /**
     * The track id of the file package track.
     */
    int fileTrackId;

    /**
     * The track id exposed by the demuxer.
     */
    int id;

    /**
     * The kind of track: 'p' picture, 's' sound, 'd' data.
     */
    char kind;

    /**
     * The codec.
     */
    Codec codec = Codec.DATA;

    /**
     * The edit rate of the track.
     */
    Rational editRate;

    /**
     * The duration of the track in edit units, or {@code -1}.
     */
    long duration = -1;

    /**
     * Whether the essence is clip wrapped: one element holds every edit unit.
     */
    boolean clipWrapped;

    /**
     * The body stream id of the essence container, or 0 when unknown.
     */
    long bodySid;

    /**
     * The essence container label, or {@code null}.
     */
    byte[] container;

    /**
     * The picture or sound coding label, or {@code null}.
     */
    byte[] coding;

    /**
     * The picture width, or 0.
     */
    int width;

    /**
     * The picture height (of a frame), or 0.
     */
    int height;

    /**
     * The frame layout of the picture descriptor.
     */
    int frameLayout;

    /**
     * The audio sampling rate, rounded, or 0.
     */
    int sampleRate;

    /**
     * The channel count, or 0.
     */
    int channels;

    /**
     * The bits of a sample, or 0.
     */
    int bits;

    /**
     * The bytes of one sample of every channel, or 0.
     */
    int blockAlign;

    /**
     * The track built for the demuxer.
     */
    Track track;

    /**
     * The track name, or {@code null}.
     */
    String name;

    /**
     * The H.264 parameter sets to decode a headerless AVC-Intra stream with, or {@code null}.
     */
    byte[][] headerless;

    /**
     * Whether H.264 elements are Annex B byte streams.
     */
    boolean annexB = true;

    /**
     * The edit unit of the next element (frame wrapped) or of the next slice (clip wrapped).
     */
    long nextUnit;

    /**
     * The first edit unit to return after a seek; elements before it are skipped.
     */
    long startUnit;

    /**
     * The audio sample of the next frame-wrapped element.
     */
    long nextSample;

    /**
     * The file offsets of the elements read in order from the start, learned for seeking without an index.
     */
    long[] learned = new long[0];

    /**
     * The number of valid entries of {@link #learned}.
     */
    int learnedCount;

    /**
     * Whether each learned element is a random access point.
     */
    boolean[] learnedKey = new boolean[0];

    /**
     * The first audio sample of each learned element.
     */
    long[] learnedSample = new long[0];

    /**
     * The display position of each stored edit unit from the index, or {@code null} without reordering.
     */
    long[] display;

    /**
     * The reordering delay in edit units, subtracted from the stored position to give the decoding time.
     */
    long delay;

    /**
     * The parameters that put the pictures of an AVC stream without temporal offsets in display order, or
     * {@code null} when the stream needs none or uses an order count type other than 0.
     */
    byte[] orderSps;

    /**
     * The first stored edit unit of the pictures whose presentation times are in {@link #windowPts}.
     */
    long windowStart = -1;

    /**
     * The presentation times of the pictures from {@link #windowStart}, from their order counts.
     */
    long[] windowPts = new long[0];

    /**
     * The decoding delay of an AVC sub-descriptor, in access units, or {@code 0xFF} when unknown.
     */
    int decodingDelay = 0xFF;

    /**
     * Whether {@link #delay} was set from the first pictures put in display order.
     */
    boolean delayKnown;

    /**
     * Whether the index gives the stream no reliable presentation order, so that an AVC stream takes it from its
     * order counts.
     */
    boolean orderCandidate;

    /**
     * The stored edit unit of the start of the current MPEG group of pictures, for timestamps without an index.
     */
    long groupStart;

    /**
     * The file offset of the clip element's value, or {@code -1} until found.
     */
    long clipStart = -1;

    /**
     * The length of the clip element's value.
     */
    long clipLength;

    /**
     * Records the file offset of the element of the next edit unit, when reading has not skipped any.
     *
     * @param offset the offset of the element key
     * @param key whether the element is a random access point
     * @param sample the first audio sample of the element
     */
    void learn(long offset, boolean key, long sample) {
        if (nextUnit != learnedCount) {
            return;
        }
        if (learnedCount == learned.length) {
            var capacity = Math.max(16, learned.length * 2);
            learned = java.util.Arrays.copyOf(learned, capacity);
            learnedKey = java.util.Arrays.copyOf(learnedKey, capacity);
            learnedSample = java.util.Arrays.copyOf(learnedSample, capacity);
        }
        learned[learnedCount] = offset;
        learnedKey[learnedCount] = key;
        learnedSample[learnedCount] = sample;
        learnedCount++;
    }
}
