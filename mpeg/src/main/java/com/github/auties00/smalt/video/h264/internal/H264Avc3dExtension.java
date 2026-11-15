/* Ports the 3D-AVC parameter set syntax of 3DV-ATM 14.0 (ITU-T H.264.2 (02/2016), ldecod parset.c and
   lcommon 3dv_acquisition.c). Copyright 2001-2015 International Telecommunication Union; ITU disclaimer of
   warranty. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds a parsed sequence parameter set 3D-AVC extension (ITU-T H.264 I.7.3.2.1.5) of a subset sequence
 * parameter set with {@code profile_idc} 139: the camera parameters of the views, the resolution and sampling
 * grid of the depth views, and the coding tools the slices may enable.
 *
 * <p>Instances are immutable after parsing.
 *
 * @implNote This implementation reads the layout the 3D-AVC conformance bitstreams of ITU-T H.264.1 (02/2016)
 * and the 3DV-ATM reference decoder (ITU-T H.264.2) share, which differs from the syntax table of
 * ITU-T H.264 (08/2024): the camera order and the disparity parameter precision precede the depth ranges,
 * the disparity parameters carry a presence flag, and the sampling grid holds one entry per listed view.
 */
public final class H264Avc3dExtension {
    /**
     * The {@code 3dv_acquisition_idc}: 0 without camera parameters, 1 with parameters valid for the whole
     * sequence, 2 with parameters that depth parameter sets may replace.
     */
    final int acquisitionIdc;

    /**
     * The {@code view_id} of each entry of the camera parameters, in their order, or {@code null} without
     * camera parameters.
     */
    final int[] cameraOrder;

    /**
     * The precision of the disparity scale and offset, in fractional bits.
     */
    final int disparityPrecision;

    /**
     * The camera parameters of the sequence, the depth parameter set with id 0, or {@code null} without them.
     */
    final Acquisition acquisition;

    /**
     * Whether the depth views have the resolution given by {@link #depthWidthInMbs} and
     * {@link #depthHeightInMapUnits} rather than the texture views' one.
     */
    final boolean reducedResolution;

    /**
     * The width of the depth views in macroblocks, when {@link #reducedResolution} is set.
     */
    final int depthWidthInMbs;

    /**
     * The height of the depth views in map units, when {@link #reducedResolution} is set.
     */
    final int depthHeightInMapUnits;

    /**
     * The {@code depth_hor_mult_minus1 + 1} of the depth-based disparity derivation, 1 when the extension omits it
     * (as 3DV-ATM infers it, where ITU-T H.264 I.7.4.2.1.5 infers 2).
     */
    final int depthHorMult;

    /**
     * The {@code depth_ver_mult_minus1 + 1} of the depth-based disparity derivation, 1 when the extension omits it.
     */
    final int depthVerMult;

    /**
     * The {@code depth_hor_rsh} of the depth-based disparity derivation.
     */
    final int depthHorRsh;

    /**
     * The {@code depth_ver_rsh} of the depth-based disparity derivation.
     */
    final int depthVerRsh;

    /**
     * The frame cropping of the depth views in crop units, left, right, top and bottom, or {@code null} when
     * {@code depth_frame_cropping_flag} is clear.
     */
    final int[] depthCrop;

    /**
     * The horizontal offset of the depth sampling grid of each texture view, indexed by {@code view_id}.
     */
    final int[] gridPosX;

    /**
     * The vertical offset of the depth sampling grid of each texture view, indexed by {@code view_id}.
     */
    final int[] gridPosY;

    /**
     * The {@code slice_header_prediction_flag}.
     */
    final boolean sliceHeaderPrediction;

    /**
     * The {@code seq_view_synthesis_flag}.
     */
    final boolean viewSynthesis;

    /**
     * The {@code alc_sps_enable_flag}.
     */
    final boolean adaptiveLuminance;

    /**
     * The {@code enable_rle_skip_flag}.
     */
    final boolean rleSkip;

    /**
     * Constructs the extension from its parsed fields.
     */
    private H264Avc3dExtension(int acquisitionIdc, int[] cameraOrder, int disparityPrecision, Acquisition acquisition,
                               boolean reducedResolution, int depthWidthInMbs, int depthHeightInMapUnits,
                               int depthHorMult, int depthVerMult, int depthHorRsh, int depthVerRsh, int[] depthCrop,
                               int[] gridPosX, int[] gridPosY, boolean sliceHeaderPrediction, boolean viewSynthesis,
                               boolean adaptiveLuminance, boolean rleSkip) {
        this.acquisitionIdc = acquisitionIdc;
        this.cameraOrder = cameraOrder;
        this.disparityPrecision = disparityPrecision;
        this.acquisition = acquisition;
        this.reducedResolution = reducedResolution;
        this.depthWidthInMbs = depthWidthInMbs;
        this.depthHeightInMapUnits = depthHeightInMapUnits;
        this.depthHorMult = depthHorMult;
        this.depthVerMult = depthVerMult;
        this.depthHorRsh = depthHorRsh;
        this.depthVerRsh = depthVerRsh;
        this.depthCrop = depthCrop;
        this.gridPosX = gridPosX;
        this.gridPosY = gridPosY;
        this.sliceHeaderPrediction = sliceHeaderPrediction;
        this.viewSynthesis = viewSynthesis;
        this.adaptiveLuminance = adaptiveLuminance;
        this.rleSkip = rleSkip;
    }

    /**
     * Parses a {@code seq_parameter_set_3davc_extension()}.
     *
     * @param reader the reader positioned at the extension
     * @param views the number of views of the subset sequence parameter set, 1 to 1024
     * @return the extension
     * @throws MediaException.Malformed if a field is out of range or the extension is truncated
     */
    static H264Avc3dExtension parse(H264BitReader reader, int views) {
        var acquisitionIdc = reader.readBits(2);
        if (acquisitionIdc > 2) {
            throw new MediaException.Malformed("3dv_acquisition_idc " + acquisitionIdc + " out of range");
        }
        int[] cameraOrder = null;
        var precision = 0;
        Acquisition acquisition = null;
        if (acquisitionIdc != 0) {
            cameraOrder = new int[views];
            for (var k = 0; k < views; k++) {
                cameraOrder[k] = reader.readUe(1023, "camera view_id");
            }
            precision = reader.readUe(31, "disparity parameter precision");
            acquisition = new Acquisition(views);
            acquisition.parseRanges(reader, acquisition, acquisition, acquisition);
            acquisition.parseDisparities(reader, acquisition, acquisition, acquisition);
        }
        var reduced = reader.readFlag();
        var depthWidth = 0;
        var depthHeight = 0;
        var horMult = 1;
        var verMult = 1;
        var horRsh = 0;
        var verRsh = 0;
        if (reduced) {
            depthWidth = reader.readUe(4095, "depth_pic_width_in_mbs_minus1") + 1;
            depthHeight = reader.readUe(4095, "depth_pic_height_in_map_units_minus1") + 1;
            horMult = reader.readUe(1023, "depth_hor_mult_minus1") + 1;
            verMult = reader.readUe(1023, "depth_ver_mult_minus1") + 1;
            horRsh = reader.readUe(31, "depth_hor_rsh");
            verRsh = reader.readUe(31, "depth_ver_rsh");
        }
        int[] crop = null;
        if (reader.readFlag()) {
            crop = new int[4];
            for (var k = 0; k < 4; k++) {
                crop[k] = reader.readUe(8191, "depth frame crop offset");
            }
        }
        var gridX = new int[1024];
        var gridY = new int[1024];
        var listed = reader.readUe(1024, "grid_pos_num_views");
        for (var i = 0; i < listed; i++) {
            var viewId = reader.readUe(1023, "grid_pos_view_id");
            gridX[viewId] = reader.readSe(-65536, 65535, "grid_pos_x");
            gridY[viewId] = reader.readSe(-65536, 65535, "grid_pos_y");
        }
        var prediction = reader.readFlag();
        var synthesis = reader.readFlag();
        var luminance = reader.readFlag();
        var rle = reader.readFlag();
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set 3D-AVC extension");
        }
        return new H264Avc3dExtension(acquisitionIdc, cameraOrder, precision, acquisition, reduced, depthWidth,
                depthHeight, horMult, verMult, horRsh, verRsh, crop, gridX, gridY, prediction, synthesis, luminance,
                rle);
    }

    /**
     * Camera parameters of a set of views: the closest and farthest depths and the scale and offset that turn a
     * depth sample into a disparity between two views (ITU-T H.264 I.7.3.2.13.1 to I.7.3.2.13.3), as a sequence
     * parameter set or a depth parameter set carries them.
     *
     * <p>Instances are filled by parsing and not modified afterwards.
     */
    static final class Acquisition {
        /**
         * The number of views the parameters describe.
         */
        final int views;

        /**
         * The closest depth of each view, in camera order.
         */
        final Element near;

        /**
         * The farthest depth of each view, in camera order.
         */
        final Element far;

        /**
         * The disparity scale between two views, indexed {@code [source][target]} in camera order.
         */
        final int[][] disparityScale;

        /**
         * The disparity offset between two views, indexed {@code [source][target]} in camera order.
         */
        final int[][] disparityOffset;

        /**
         * The {@code pred_direction}: 0 and 1 for parameters predicted from two or one other set, 2 for parameters
         * coded on their own.
         */
        int predDirection = 2;

        /**
         * The weight of the first reference set in a prediction from two sets, out of 64.
         */
        int predWeight0 = 64;

        /**
         * Constructs empty parameters for a number of views.
         *
         * @param views the number of views, 1 to 1024
         */
        Acquisition(int views) {
            this.views = views;
            this.near = new Element(views);
            this.far = new Element(views);
            this.disparityScale = new int[views][views];
            this.disparityOffset = new int[views][views];
        }

        /**
         * Parses the {@code depth_ranges()} of these parameters.
         *
         * @param reader the reader positioned at the depth ranges
         * @param sequence the parameters of the sequence parameter set, copied for an absent element
         * @param forward the first reference parameters of a prediction
         * @param backward the second reference parameters of a prediction
         */
        void parseRanges(H264BitReader reader, Acquisition sequence, Acquisition forward, Acquisition backward) {
            var nearPresent = reader.readFlag();
            var farPresent = reader.readFlag();
            if (nearPresent) {
                near.parse(reader, this, forward.near, backward.near);
            } else {
                near.copyFrom(sequence.near);
            }
            if (farPresent) {
                far.parse(reader, this, forward.far, backward.far);
            } else {
                far.copyFrom(sequence.far);
            }
        }

        /**
         * Parses the presence flag and the {@code vsp_param()} of these parameters.
         *
         * @param reader the reader positioned at the flag
         * @param sequence the parameters of the sequence parameter set, copied when the flag is clear
         * @param forward the first reference parameters of a prediction
         * @param backward the second reference parameters of a prediction
         */
        void parseDisparities(H264BitReader reader, Acquisition sequence, Acquisition forward, Acquisition backward) {
            if (!reader.readFlag()) {
                for (var i = 0; i < views; i++) {
                    for (var j = 0; j < views; j++) {
                        disparityScale[i][j] = sequence.at(sequence.disparityScale, i, j);
                        disparityOffset[i][j] = sequence.at(sequence.disparityOffset, i, j);
                    }
                }
                return;
            }
            parseVsp(reader, forward, backward);
        }

        /**
         * Parses a {@code vsp_param()}.
         *
         * @param reader the reader positioned at the parameters
         * @param forward the first reference parameters of a prediction
         * @param backward the second reference parameters of a prediction
         */
        void parseVsp(H264BitReader reader, Acquisition forward, Acquisition backward) {
            var forwardWeight = predWeight0;
            var backwardWeight = 64 - forwardWeight;
            for (var i = 0; i < views; i++) {
                for (var j = 0; j < i; j++) {
                    var scaleJi = reader.readSe();
                    var offsetJi = reader.readSe();
                    var scaleIj = reader.readSe();
                    var offsetIj = reader.readSe();
                    if (predDirection == 2) {
                        disparityScale[j][i] = scaleJi;
                        disparityOffset[j][i] = offsetJi;
                        disparityScale[i][j] = scaleIj - disparityScale[j][i];
                        disparityOffset[i][j] = offsetIj - disparityOffset[j][i];
                    } else {
                        disparityScale[j][i] = scaleJi + ((forward.at(forward.disparityScale, j, i) * forwardWeight
                                + backward.at(backward.disparityScale, j, i) * backwardWeight + 32) >> 6);
                        disparityOffset[j][i] = offsetJi + ((forward.at(forward.disparityOffset, j, i) * forwardWeight
                                + backward.at(backward.disparityOffset, j, i) * backwardWeight + 32) >> 6);
                        var scaleSum = ((forward.at(forward.disparityScale, i, j)
                                + forward.at(forward.disparityScale, j, i)) * forwardWeight
                                + (backward.at(backward.disparityScale, i, j)
                                + backward.at(backward.disparityScale, j, i)) * backwardWeight + 32) >> 6;
                        disparityScale[i][j] = scaleIj - disparityScale[j][i] + scaleSum;
                        var offsetSum = ((forward.at(forward.disparityOffset, i, j)
                                + forward.at(forward.disparityOffset, j, i)) * forwardWeight
                                + (backward.at(backward.disparityOffset, i, j)
                                + backward.at(backward.disparityOffset, j, i)) * backwardWeight + 32) >> 6;
                        disparityOffset[i][j] = offsetIj - disparityOffset[j][i] + offsetSum;
                    }
                }
            }
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated view synthesis prediction parameters");
            }
        }

        /**
         * {@return an entry of a disparity table of these parameters, 0 outside it}
         *
         * @param table the disparity scale or offset table of these parameters
         * @param i the first index
         * @param j the second index
         */
        int at(int[][] table, int i, int j) {
            return i < views && j < views ? table[i][j] : 0;
        }
    }

    /**
     * One element of the depth ranges, a floating-point value per view coded as a sign, a 7-bit exponent and a
     * mantissa (ITU-T H.264 I.7.3.2.13.2).
     *
     * <p>Instances are filled by parsing and not modified afterwards.
     */
    static final class Element {
        /**
         * The sign of each view's value, 0 positive and 1 negative.
         */
        final int[] sign;

        /**
         * The exponent of each view's value, 0 to 126.
         */
        final int[] exponent;

        /**
         * The mantissa of each view's value, of {@link #mantissaLength} bits.
         */
        final int[] mantissa;

        /**
         * The value of each view.
         */
        final double[] value;

        /**
         * The number of bits of the mantissas, 1 to 32.
         */
        int mantissaLength;

        /**
         * Constructs an element of zeros for a number of views.
         *
         * @param views the number of views
         */
        Element(int views) {
            sign = new int[views];
            exponent = new int[views];
            mantissa = new int[views];
            value = new double[views];
        }

        /**
         * Copies the values of another element of at least as many views.
         *
         * @param other the element to copy
         */
        void copyFrom(Element other) {
            var count = Math.min(sign.length, other.sign.length);
            System.arraycopy(other.sign, 0, sign, 0, count);
            System.arraycopy(other.exponent, 0, exponent, 0, count);
            System.arraycopy(other.mantissa, 0, mantissa, 0, count);
            System.arraycopy(other.value, 0, value, 0, count);
            mantissaLength = other.mantissaLength;
        }

        /**
         * Parses a {@code 3dv_acquisition_element()}.
         *
         * @param reader the reader positioned at the element
         * @param owner the parameters the element belongs to, which give the prediction
         * @param forward the element of the first reference parameters
         * @param backward the element of the second reference parameters
         */
        void parse(H264BitReader reader, Acquisition owner, Element forward, Element backward) {
            var views = sign.length;
            var sets = views;
            var equal = sets > 1 && reader.readFlag();
            if (equal) {
                sets = 1;
            }
            var prediction = owner.predDirection;
            if (prediction == 2) {
                mantissaLength = reader.readBits(5) + 1;
            } else {
                mantissaLength = forward.mantissaLength;
            }
            var forwardWeight = owner.predWeight0;
            var backwardWeight = 64 - forwardWeight;
            for (var k = 0; k < sets; k++) {
                if (prediction == 2) {
                    sign[k] = reader.readBit();
                    exponent[k] = reader.readBits(7);
                    mantissa[k] = reader.readBits(mantissaLength);
                } else {
                    var predicted = (forwardWeight * entry(forward.mantissa, k) + backwardWeight
                            * entry(backward.mantissa, k) + 32) >> 6;
                    var difference = 0;
                    if (reader.readFlag()) {
                        sign[k] = entry(forward.sign, k);
                        exponent[k] = entry(forward.exponent, k);
                    } else {
                        sign[k] = reader.readBit();
                        exponent[k] = reader.readFlag() ? entry(forward.exponent, k) : reader.readBits(7);
                        difference = reader.readSe();
                    }
                    mantissa[k] = difference + predicted;
                }
                if (exponent[k] == 127) {
                    throw new MediaException.Malformed("reserved depth range exponent");
                }
                value[k] = (sign[k] == 0 ? 1 : -1) * Math.scalb(1 + Math.scalb((double) mantissa[k], -mantissaLength),
                        exponent[k] - 31);
            }
            if (equal) {
                for (var k = 1; k < views; k++) {
                    sign[k] = sign[0];
                    exponent[k] = exponent[0];
                    mantissa[k] = mantissa[0];
                    value[k] = value[0];
                }
            }
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated depth ranges");
            }
        }

        /**
         * {@return an entry of an array, 0 past its end}
         *
         * @param values the array
         * @param k the index
         */
        private static int entry(int[] values, int k) {
            return k < values.length ? values[k] : 0;
        }
    }

    /**
     * A parsed depth parameter set (ITU-T H.264 I.7.3.2.13): camera parameters that replace those of the
     * sequence parameter set for the slices naming it, and the piecewise linear depth representation.
     *
     * <p>Instances are immutable after parsing.
     *
     * @param id the {@code depth_parameter_set_id}, 1 to 63
     * @param acquisition the camera parameters
     * @param nonlinearPoints the deviations of the nodes of the depth representation, with a 0 node added at
     * each end, so its length is {@code nonlinear_depth_representation_num + 2}
     */
    record DepthParameterSet(int id, Acquisition acquisition, int[] nonlinearPoints) {
        /**
         * Parses a {@code depth_parameter_set_rbsp()}.
         *
         * @implNote This implementation reads the layout of the 3D-AVC conformance bitstreams and 3DV-ATM, where
         * a presence flag of the disparity parameters follows {@code vsp_param_flag}, and keeps the 3DV-ATM rule
         * that a prediction from two sets takes its second set from the sequence parameter set whenever the first
         * one does.
         * @param reader the reader positioned at the start of the payload
         * @param sequence the camera parameters of the sequence parameter set, the set with id 0
         * @param sets the depth parameter sets parsed so far, indexed by id, with {@code null} for absent ones
         * @return the set
         * @throws MediaException.Malformed if a field is out of range, a reference set is missing or the payload
         * is truncated
         */
        static DepthParameterSet parse(H264BitReader reader, Acquisition sequence, DepthParameterSet[] sets) {
            var id = reader.readUe(63, "depth_parameter_set_id");
            var direction = reader.readBits(2);
            if (direction > 2) {
                throw new MediaException.Malformed("pred_direction " + direction + " out of range");
            }
            Acquisition forward = null;
            Acquisition backward = null;
            var weight = 64;
            if (direction <= 1) {
                var ref0 = reader.readUe(63, "ref_dps_id0");
                forward = reference(ref0, sequence, sets);
                if (direction == 0) {
                    var ref1 = reader.readUe(63, "ref_dps_id1");
                    weight = reader.readBits(6);
                    backward = ref0 == 0 ? sequence : reference(ref1, sequence, sets);
                }
            }
            var views = reader.readUe(1023, "num_depth_views_minus1") + 1;
            if (views > sequence.views) {
                throw new MediaException.Malformed("num_depth_views_minus1 " + (views - 1)
                        + " exceeds the views of the subset sequence parameter set");
            }
            var acquisition = new Acquisition(views);
            acquisition.predDirection = direction;
            acquisition.predWeight0 = weight;
            if (forward == null) {
                forward = acquisition;
            }
            if (backward == null) {
                backward = acquisition;
            }
            acquisition.parseRanges(reader, sequence, forward, backward);
            if (reader.readFlag()) {
                acquisition.parseDisparities(reader, sequence, forward, backward);
            }
            reader.readFlag();
            var count = reader.readUe(254, "nonlinear_depth_representation_num");
            var points = new int[count + 2];
            for (var k = 1; k <= count; k++) {
                points[k] = (byte) reader.readUe();
            }
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated depth parameter set");
            }
            return new DepthParameterSet(id, acquisition, points);
        }

        /**
         * {@return the camera parameters a depth parameter set predicts from}
         *
         * @param id the id of the reference set, 0 for the sequence parameter set
         * @param sequence the camera parameters of the sequence parameter set
         * @param sets the depth parameter sets parsed so far
         * @throws MediaException.Malformed if the set is missing
         */
        private static Acquisition reference(int id, Acquisition sequence, DepthParameterSet[] sets) {
            if (id == 0) {
                return sequence;
            }
            if (sets[id] == null) {
                throw new MediaException.Malformed("missing reference depth parameter set " + id);
            }
            return sets[id].acquisition;
        }
    }
}
