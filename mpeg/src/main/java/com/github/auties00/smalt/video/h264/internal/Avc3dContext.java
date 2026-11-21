/* Ports the depth parameter set handling and non-linear depth representation of 3DV-ATM 14.0 (ITU-T H.264.2
   (02/2016), ldecod parset.c and lcommon nonlinear_depth.c). Copyright 2001-2015 International Telecommunication
   Union; ITU disclaimer of warranty. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The state of a 3D-AVC stream (ITU-T H.264 Annex I) that its texture and depth engines share: the depth
 * parameter sets, the camera parameters of the latest 3D-AVC subset sequence parameter set and the slice
 * headers predicted headers copy from.
 *
 * <p>Instances are not thread-safe; the engines of one stream use theirs from the decoding thread.
 */
final class Avc3dContext {
    /**
     * The first slice header of each view component of the current access unit.
     */
    final Avc3dSliceHistory history = new Avc3dSliceHistory();

    /**
     * The depth parameter sets, indexed by {@code depth_parameter_set_id}, with {@code null} for absent ones.
     */
    final H264Avc3dExtension.DepthParameterSet[] depthParameterSets = new H264Avc3dExtension.DepthParameterSet[64];

    /**
     * The camera parameters of the latest multiview depth subset sequence parameter set, the depth parameter
     * set with id 0; empty before one arrives.
     */
    H264Avc3dExtension.Acquisition sequence = new H264Avc3dExtension.Acquisition(1);

    /**
     * The depth representation of the latest depth parameter set parsed, as {@link
     * H264Avc3dExtension.DepthParameterSet#nonlinearPoints()} gives it.
     */
    int[] latestNonlinearPoints = {0, 0};

    /**
     * The 3D-AVC extension of the latest subset sequence parameter set parsed with each id; a set without one
     * leaves its entry.
     */
    private final H264Avc3dExtension[] extensions = new H264Avc3dExtension[32];

    /**
     * The 3D-AVC extension whose slice header prediction, view synthesis, adaptive luminance compensation and
     * run-length skip flags apply to every 3D-AVC slice, or {@code null}.
     *
     * <p>The 3D-AVC conformance bitstreams of ITU-T H.264.1 (02/2016) and 3DV-ATM take these flags from the
     * subset sequence parameter set parsed last, whichever set the slice refers to.
     */
    H264Avc3dExtension tools;

    /**
     * The depth parameter set the last texture slice with a depth-dependent tool selected, or {@code null} for
     * the camera parameters of the sequence.
     */
    H264Avc3dExtension.DepthParameterSet activeDps;

    /**
     * The {@code adaptive_NDR_flag} of the last depth slice parsed, which selects whether the disparity tables
     * undo the non-linear depth representation.
     */
    boolean lastAdaptiveNdr;

    /**
     * The picture order count of the 3D-AVC picture, texture or depth, whose first slice was started last.
     */
    int currentPoc = Integer.MIN_VALUE;

    /**
     * The disparity tables computed last, with their parameters, reused while the camera parameters stay: each
     * entry is {@code {scale, offset, precision}}, the inverse depth representation and the table.
     */
    private final Object[][] tables = new Object[8][];

    /**
     * The entry of {@link #tables} the next new table replaces.
     */
    private int nextTable;

    /**
     * Returns the luma disparity in quarter samples of each depth sample value for a disparity scale and offset,
     * reusing a table computed before with the same parameters; the table is shared and must not be modified.
     *
     * @param scale the disparity scale from the view to the reference view
     * @param offset the disparity offset
     * @param precision the disparity parameter precision
     * @param lut the inverse non-linear depth representation, or {@code null} for none
     * @return the table, 256 entries
     */
    int[] disparityTable(int scale, int offset, int precision, int[] lut) {
        for (var entry : tables) {
            if (entry != null) {
                var key = (int[]) entry[0];
                if (key[0] == scale && key[1] == offset && key[2] == precision && entry[1] == lut) {
                    return (int[]) entry[2];
                }
            }
        }
        var shift = 8 + precision + 1 - 2;
        var table = new int[256];
        for (var j = 0; j < 256; j++) {
            var depthValue = lut == null ? j : lut[j];
            table[j] = (int) (((long) depthValue * scale + ((long) offset << 8) + (1L << (shift - 1))) >> shift);
        }
        tables[nextTable] = new Object[]{new int[]{scale, offset, precision}, lut, table};
        nextTable = (nextTable + 1) % tables.length;
        return table;
    }

    /**
     * Whether a 3D-AVC subset sequence parameter set was seen, from which on the slice headers of every view
     * component are recorded.
     */
    boolean active;

    /**
     * The table {@link #depthOutputLut} returned last, or {@code null}.
     */
    private int[] cachedLut;

    /**
     * The depth representation {@link #cachedLut} was built from.
     */
    private int[] cachedPoints;

    /**
     * The depth representation information table {@link #cachedLut} was composed with, or {@code null}.
     */
    private int[] cachedSei;

    /**
     * Returns the table that maps the luma samples of a depth frame to the samples it is output with, for a
     * depth view component whose slices take the depth representation of the depth parameter sets: the inverse
     * of the piecewise linear representation of the latest depth parameter set parsed (ITU-T H.264
     * I.7.4.2.13), followed by the depth representation information table when one applies.
     *
     * @implNote This implementation follows 3DV-ATM, which inverts the representation of the latest depth
     * parameter set parsed rather than of the active one, and rounds each segment to the nearest integer with
     * ties away from zero.
     * @param sei the table of the depth representation information SEI message in force, or {@code null}
     * @return the table, 256 entries, or {@code sei} when the representation is linear; shared, never modified
     */
    int[] depthOutputLut(int[] sei) {
        var points = latestNonlinearPoints;
        if (points.length <= 2) {
            return sei;
        }
        if (cachedLut != null && cachedPoints == points && cachedSei == sei) {
            return cachedLut;
        }
        var lut = new int[256];
        var segments = points.length - 1;
        for (var k = 0; k < segments; k++) {
            var startX = 255 * k / segments;
            var startD = -points[k];
            var endX = 255 * (k + 1) / segments;
            var endD = -points[k + 1];
            drawLine(lut, startX + startD, startX - startD, endX + endD, endX - endD);
        }
        if (sei != null) {
            for (var i = 0; i < 256; i++) {
                lut[i] = sei[Math.clamp(lut[i], 0, sei.length - 1)];
            }
        }
        cachedLut = lut;
        cachedPoints = points;
        cachedSei = sei;
        return lut;
    }

    /**
     * Writes one segment of the piecewise linear depth representation into a table.
     *
     * @param lut the table, 256 entries
     * @param x1 the abscissa of the first node
     * @param y1 the ordinate of the first node
     * @param x2 the abscissa of the second node
     * @param y2 the ordinate of the second node
     */
    private static void drawLine(int[] lut, int x1, int y1, int x2, int y2) {
        var dy = y2 - y1;
        var dx = x2 - x1;
        if (dx == 0) {
            if (x1 >= 0 && x1 < 256) {
                lut[x1] = Math.clamp(y1, 0, 255);
            }
            return;
        }
        var half = dy > 0 ? dx >> 1 : -(dx >> 1);
        for (var x = x1; x <= x2; x++) {
            if (x >= 0 && x < 256) {
                lut[x] = Math.clamp(((x - x1) * dy + half) / dx + y1, 0, 255);
            }
        }
    }

    /**
     * Takes note of a subset sequence parameter set of a multiview depth profile: its camera parameters
     * become those of the depth parameter set with id 0.
     *
     * @param sps the parsed set
     */
    void noteSubsetSps(H264Sps sps) {
        if (sps.avc3d != null) {
            extensions[sps.id] = sps.avc3d;
        }
        tools = extensions[sps.id];
        if (sps.profileIdc != 138 && sps.profileIdc != 139) {
            return;
        }
        if (sps.avc3d != null) {
            active = true;
        }
        var views = sps.mvc != null ? sps.mvc.views() : 1;
        sequence = sps.avc3d != null && sps.avc3d.acquisition != null ? sps.avc3d.acquisition
                : new H264Avc3dExtension.Acquisition(views);
    }

    /**
     * Parses and stores a depth parameter set.
     *
     * @param rbsp the unescaped payload, starting after the NAL header byte and followed by
     * {@link H264BitReader#PADDING} zero bytes
     * @param size the payload size
     * @throws MediaException.Malformed if the set is malformed
     */
    void parseDepthParameterSet(byte[] rbsp, int size) {
        var reader = new H264BitReader();
        reader.init(rbsp, 0, size);
        var set = H264Avc3dExtension.DepthParameterSet.parse(reader, sequence, depthParameterSets);
        if (set.id() == 0) {
            throw new MediaException.Malformed("depth_parameter_set_id 0");
        }
        depthParameterSets[set.id()] = set;
        latestNonlinearPoints = set.nonlinearPoints();
    }
}
