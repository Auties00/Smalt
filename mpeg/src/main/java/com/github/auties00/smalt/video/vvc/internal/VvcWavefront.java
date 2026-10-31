package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.RowProgress;

/**
 * Coordinates the substreams of a slice decoded concurrently (H.266 clause 9.3.1): tiles, which are independent, and,
 * with wavefront parallel processing ({@code sps_entropy_coding_sync_enabled_flag}), CTU rows of a tile, each of
 * which waits for the row above it to decode the CTU above each of its CTUs (the CTU above and to the right is not
 * available for prediction under wavefront parallel processing, H.266 clause 6.4.4) and starts from the CABAC contexts
 * the row above stored after its first CTU.
 *
 * <p>Substreams are decoded in batches of consecutive substreams; the substreams before a batch are complete when it
 * starts. When a substream fails, the substreams waiting for it fail too, so no thread waits forever.
 */
final class VvcWavefront {
    /**
     * The number of decoded CTUs of each substream of the batch, indexed from the first substream of the batch.
     */
    private final RowProgress progress = new RowProgress();

    /**
     * The CABAC contexts each substream stored after its first CTU, indexed by substream.
     */
    private VvcCabac[] stores = new VvcCabac[0];

    /**
     * The substream of the CTU row above each substream that this one depends on, or -1.
     */
    private int[] above = new int[0];

    /**
     * The first substream of the batch.
     */
    private int first;

    /**
     * Constructs a coordinator with no substreams.
     */
    VvcWavefront() {
    }

    /**
     * Prepares the substreams of a slice: which substream each one waits for, and a context store for each.
     *
     * @param header the slice header
     * @param starts the substreams, from {@link VvcSliceDecoder#substreams(VvcSliceHeader)}
     */
    void beginSlice(VvcSliceHeader header, int[] starts) {
        var count = starts.length - 1;
        if (stores.length < count) {
            var grown = new VvcCabac[Math.max(count, 2 * stores.length)];
            System.arraycopy(stores, 0, grown, 0, stores.length);
            for (var i = stores.length; i < grown.length; i++) {
                grown[i] = new VvcCabac();
            }
            stores = grown;
            above = new int[grown.length];
        }
        var pps = header.pps;
        var w = pps.widthInCtus;
        var wpp = header.sps.entropyCodingSync;
        for (var k = 0; k < count; k++) {
            above[k] = -1;
            if (!wpp || k == 0) {
                continue;
            }
            var ctu = header.ctus[starts[k]];
            var previous = header.ctus[starts[k - 1]];
            var x = ctu % w;
            var y = ctu / w;
            if (previous / w == y - 1 && previous % w == x && pps.ctuToTileRow[y] == pps.ctuToTileRow[y - 1]) {
                above[k] = k - 1;
            }
        }
    }

    /**
     * Starts a batch of substreams, none of which has decoded anything yet.
     *
     * @param first the first substream of the batch
     * @param count the number of substreams of the batch
     */
    void beginBatch(int first, int count) {
        this.first = first;
        progress.begin(count);
    }

    /**
     * Waits until the substream above has decoded the CTU above a CTU of a substream.
     *
     * @param k the substream
     * @param index the index of the CTU in the substream
     * @throws MediaException.Malformed if the substream above failed
     */
    void awaitAbove(int k, int index) {
        var a = above[k];
        if (a >= first && !progress.await(a - first, index + 1)) {
            throw new MediaException.Malformed("an earlier wavefront row of the vvc slice is corrupt");
        }
    }

    /**
     * Publishes the number of decoded CTUs of a substream, waking the substream that waits for it.
     *
     * @param k the substream, of the batch
     * @param decoded the number of its decoded CTUs
     */
    void advance(int k, int decoded) {
        progress.advance(k - first, decoded);
    }

    /**
     * {@return the store of the CABAC contexts of a substream after its first CTU}
     *
     * @param k the substream
     */
    VvcCabac store(int k) {
        return stores[k];
    }

    /**
     * Marks the batch as failed, releasing the substreams that wait.
     */
    void fail() {
        progress.fail();
    }
}
