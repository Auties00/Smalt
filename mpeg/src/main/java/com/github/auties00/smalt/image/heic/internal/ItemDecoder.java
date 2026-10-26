package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Packet;

import java.util.List;
import java.util.function.Consumer;

/**
 * Decodes the NAL unit payloads of coded image items and sequence samples with a video decoding
 * engine of this module: HEVC for {@code hvc1} items and tracks, AVC for {@code avc1} items and
 * {@code avc1} or {@code avc3} tracks.
 *
 * <p>An item is decoded on a fresh engine: the parameter sets of its configuration record first,
 * then the items it is predicted from ({@code pred} references), then the item itself, each
 * payload a sequence of NAL units prefixed by their length. A sequence keeps one engine for all
 * its samples, because each sample predicts from the ones before it. Instances are not
 * thread-safe: a grid decoded in parallel uses one instance per thread.
 */
abstract sealed class ItemDecoder permits HevcItemDecoder, AvcItemDecoder {
    /**
     * The resource bounds of every picture.
     */
    final MediaLimits limits;

    /**
     * The most pictures the engine decodes concurrently; 1 decodes on the calling thread.
     */
    final int threads;

    /**
     * The size of the NAL unit length prefixes of the current configuration.
     */
    int lengthSize = 4;

    /**
     * Whether an item rather than the samples of a sequence is being decoded: the first picture of
     * an item may be an intra picture that is not a random access point.
     */
    boolean decodingItem;

    /**
     * The last picture the item decode produced.
     */
    private CodedFrame last;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds checked against every picture
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     */
    ItemDecoder(MediaLimits limits, int threads) {
        this.limits = limits;
        this.threads = threads;
    }

    /**
     * Returns a decoder for the items or samples of a codec.
     *
     * @param avc whether the payloads are AVC rather than HEVC
     * @param limits the bounds checked against every picture
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     * @return the decoder
     */
    static ItemDecoder of(boolean avc, MediaLimits limits, int threads) {
        return avc ? new AvcItemDecoder(limits, threads) : new HevcItemDecoder(limits, threads);
    }

    /**
     * Decodes a coded item, after the items it is predicted from.
     *
     * <p>The returned picture is the last one the engine outputs, cropped to its visible area. It
     * stays valid until the next call on this decoder. The first payload may hold a picture that is
     * not a random access point, such as an intra-coded trailing picture of an HEVC stream.
     *
     * @param configurations the configuration record of each payload
     * @param payloads the payloads in decoding order, the item's own payload last
     * @return the decoded picture
     * @throws MediaException.Malformed if a payload is corrupt or no intact picture is output
     * @throws MediaException.Unsupported if the item uses a coding tool the engine lacks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    CodedFrame decodeItem(List<byte[]> configurations, List<byte[]> payloads) {
        last = null;
        decodingItem = true;
        open(frame -> last = frame);
        try {
            for (var i = 0; i < payloads.size(); i++) {
                configure(configurations.get(i));
                feed(payloads.get(i), Packet.NO_TIMESTAMP, 0);
            }
            flush();
        } finally {
            close();
            decodingItem = false;
        }
        if (last == null) {
            throw new MediaException.Malformed("heic: the item shows no picture");
        }
        if (last instanceof AvcTile avc && avc.damaged()) {
            throw new MediaException.Malformed("heic: the item decodes to a damaged picture");
        }
        return last;
    }

    /**
     * Starts a new decoding state.
     *
     * @param sink receives the pictures in output order; their samples may be reused once it
     * returns
     */
    abstract void open(Consumer<CodedFrame> sink);

    /**
     * Feeds the parameter sets of a configuration record, unless they were fed last.
     *
     * @param record the configuration record, or {@code null} when the payloads carry their
     * parameter sets
     * @throws MediaException.Malformed if the record is corrupt
     */
    abstract void configure(byte[] record);

    /**
     * Decodes one payload of length-prefixed NAL units as one access unit.
     *
     * @param data the payload
     * @param pts the presentation timestamp the pictures of the payload carry
     * @param duration the duration the pictures of the payload carry
     * @throws MediaException.Malformed if a length prefix runs past the payload or a NAL unit is
     * corrupt
     */
    abstract void feed(byte[] data, long pts, long duration);

    /**
     * Outputs every picture still waiting in the engine.
     */
    abstract void flush();

    /**
     * Discards the decoding state, waiting for the pictures in flight.
     */
    abstract void close();

    /**
     * Passes the length-prefixed NAL units of a payload to a consumer.
     *
     * @param data the payload
     * @param consumer receives each NAL unit as an array, an offset and a length
     * @throws MediaException.Malformed if a length prefix runs past the payload
     */
    final void forEachNal(byte[] data, NalUnits.Sink consumer) {
        var broken = NalUnits.forEachLengthPrefixed(data, lengthSize, consumer);
        if (broken < data.length) {
            if (broken + lengthSize > data.length) {
                throw new MediaException.Malformed("heic: truncated NAL unit length prefix");
            }
            throw new MediaException.Malformed("heic: NAL unit length " + NalUnits.readLength(data, broken, lengthSize)
                    + " exceeds the payload");
        }
    }
}
