package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * The first slice header of each texture and depth view component decoded so far in the current access unit of
 * a 3D-AVC stream, which predicted slice headers copy fields from (ITU-T H.264 I.7.4.3.4).
 *
 * <p>Instances are not thread-safe; the engines of one stream share one.
 */
final class Avc3dSliceHistory {
    /**
     * The recorded headers, indexed {@code [voIdx * 2 + depth]}, or {@code null} where none is recorded.
     */
    private H264SliceHeader[] headers = new H264SliceHeader[16];

    /**
     * Whether each entry of {@link #headers} belongs to the current access unit.
     */
    private boolean[] present = new boolean[16];

    /**
     * Returns the first slice header of a view component of the current access unit.
     *
     * @param voIdx the view order index, at least 0
     * @param depth whether the depth view component is wanted
     * @return the header, or {@code null} when that view component was not decoded
     */
    H264SliceHeader first(int voIdx, boolean depth) {
        var index = voIdx * 2 + (depth ? 1 : 0);
        return index < present.length && present[index] ? headers[index] : null;
    }

    /**
     * Records the first slice header of a view component; the base texture view component starts a new access
     * unit, which forgets the others.
     *
     * @param slice the header, with its {@code voIdx} and {@code depthFlag} set
     */
    void record(H264SliceHeader slice) {
        if (slice.voIdx == 0 && !slice.depthFlag) {
            Arrays.fill(present, false);
        }
        var index = slice.voIdx * 2 + (slice.depthFlag ? 1 : 0);
        if (index >= headers.length) {
            headers = Arrays.copyOf(headers, index + 2);
            present = Arrays.copyOf(present, index + 2);
        }
        if (headers[index] == null) {
            headers[index] = new H264SliceHeader();
        }
        headers[index].copyFrom(slice);
        present[index] = true;
    }
}
