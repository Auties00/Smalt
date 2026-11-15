package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.image.AmbientViewingEnvironment;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.sei.H274Sei;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.OmnidirectionalVideo;

import java.util.Arrays;

/**
 * A decoded picture of the decoded picture buffer: its sample planes, its motion field and the state the
 * reference picture marking and the output process keep for it.
 *
 * <p>Samples are stored unpadded at the coded picture size, one {@code short} per sample. The motion field holds a
 * vector pair per 4x4 luma block while the picture is decoded; {@link #compressMotion} reduces it to the 8x8 grid
 * that temporal motion vector prediction reads (8.5.2.12).
 */
public final class VvcPicture {
    /**
     * The coded width in luma samples.
     */
    public final int width;
    /**
     * The coded height in luma samples.
     */
    public final int height;
    /**
     * {@code sps_chroma_format_idc} of the picture.
     */
    public final int chromaFormat;
    /**
     * The horizontal chroma subsampling shift.
     */
    public final int shiftX;
    /**
     * The vertical chroma subsampling shift.
     */
    public final int shiftY;
    /**
     * The bit depth of the samples.
     */
    public final int bitDepth;
    /**
     * The sample planes: luma, then Cb and Cr unless the picture is monochrome.
     */
    public final short[][] planes;
    /**
     * The width of each plane, also its row stride.
     */
    public final int[] planeWidth;
    /**
     * The height of each plane.
     */
    public final int[] planeHeight;

    /**
     * {@code PicOrderCntVal}.
     */
    public int poc;
    /**
     * {@code nuh_layer_id}.
     */
    public int layer;
    /**
     * {@code TemporalId}.
     */
    public int temporalId;
    /**
     * Whether the picture is marked as a reference picture.
     */
    public boolean reference;
    /**
     * Whether the picture is marked as a long-term reference picture.
     */
    public boolean longTerm;
    /**
     * Whether the picture waits in the decoded picture buffer for output.
     */
    public boolean neededForOutput;
    /**
     * {@code PictureOutputFlag}.
     */
    public boolean outputFlag;
    /**
     * Whether the picture was decoded completely.
     */
    public boolean decoded;
    /**
     * Whether the picture was generated as a stand-in for a missing reference.
     */
    public boolean generated;
    /**
     * The position of the picture in decoding order.
     */
    public long decodeOrder;
    /**
     * The timestamp of the packet in which the picture's header arrived, or {@link Long#MIN_VALUE} when it had none.
     */
    public long pts = Long.MIN_VALUE;
    /**
     * The duration of that packet, or 0 when unknown.
     */
    public long duration;
    /**
     * The number of pictures decoded since the picture was marked for output ({@code PicLatencyCount}).
     */
    int latency;
    /**
     * Whether the picture is a random access point: an IRAP picture, or a GDR picture recovering at itself.
     */
    public boolean keyframe;
    /**
     * The left conformance window offset of the picture parameter set, in chroma samples.
     */
    public int confLeft;
    /**
     * The right conformance window offset of the picture parameter set, in chroma samples.
     */
    public int confRight;
    /**
     * The top conformance window offset of the picture parameter set, in chroma samples.
     */
    public int confTop;
    /**
     * The bottom conformance window offset of the picture parameter set, in chroma samples.
     */
    public int confBottom;
    /**
     * The scaling window offsets in chroma units (left, right, top, bottom) of the picture parameter set the picture
     * was coded with.
     */
    public final int[] scalingWindow = new int[4];
    /**
     * The CTU size of the sequence the picture was coded in.
     */
    public int ctuSize;
    /**
     * {@code sps_subpic_treated_as_pic_flag} of each subpicture of that sequence.
     */
    public boolean[] subpicTreatedAsPic;
    /**
     * The picture parameter set the picture was coded with.
     */
    public VvcPps pps;
    /**
     * The sequence parameter set the picture was coded with.
     */
    public VvcSps sps;
    /**
     * For a field of a sequence of fields, its parity from the frame-field information SEI message (0 top, 1
     * bottom), or -1 when unknown or not a field.
     */
    public int fieldParity = -1;

    /**
     * Whether the picture starts a coded layer video sequence, which ends the persistence of the display SEI
     * messages of the previous one.
     */
    public boolean newSequence;

    /**
     * The film grain characteristics SEI message of the picture, or {@code null}.
     */
    public FilmGrainParameters filmGrain;

    /**
     * The frame packing arrangement SEI message of the picture, or {@code null}.
     */
    public DisplaySei.FramePackingMessage framePacking;

    /**
     * The display orientation SEI message of the picture, or {@code null}.
     */
    public DisplaySei.OrientationMessage orientation;

    /**
     * The {@code cc_data} constructs of the caption SEI messages of the picture, or {@code null}.
     */
    public byte[] captions;

    /**
     * The scan a frame-field information SEI message gives a frame picture, or {@code null}.
     */
    public FieldOrder fieldOrder;

    /**
     * The field periods a frame-field information SEI message displays a frame picture for beyond its two fields.
     */
    public int repeatedFields;

    /**
     * The mastering display of the last mastering display colour volume SEI message before the picture, or
     * {@code null}.
     */
    public MasteringDisplay masteringDisplay;

    /**
     * The content light level of the last content light level information SEI message before the picture, or
     * {@code null}.
     */
    public ContentLightLevel contentLightLevel;

    /**
     * The {@code preferred_transfer_characteristics} of the alternative transfer characteristics SEI message in
     * effect, or -1.
     */
    public int preferredTransfer = -1;

    /**
     * The ambient viewing environment of the picture's coded layer video sequence, or {@code null}.
     */
    public AmbientViewingEnvironment ambientViewing;

    /**
     * The content colour volume SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<ContentColorVolume> contentColorVolume;

    /**
     * The sample aspect ratio information SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<Rational> sampleAspectRatio;

    /**
     * The equirectangular projection SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Projection> equirectangular;

    /**
     * The generalized cubemap projection SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Projection> cubemap;

    /**
     * The sphere rotation SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.SphereRotation> sphereRotation;

    /**
     * The region-wise packing SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.RegionWisePacking> regionWisePacking;

    /**
     * The omnidirectional viewport SEI message of the picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Viewports> viewports;

    /**
     * Whether the picture precedes the recovery point of the GDR picture that started its coded layer video
     * sequence: it is not output, and its samples, decoded from generated references, need not match its hash.
     */
    boolean beforeRecovery;

    /**
     * The width of the picture in 4x4 blocks.
     */
    final int width4;
    /**
     * The height of the picture in 4x4 blocks.
     */
    final int height4;
    /**
     * The horizontal list 0 vector of each 4x4 block, in 1/16 luma samples.
     */
    final int[] mvx0;
    /**
     * The vertical list 0 vector of each 4x4 block, in 1/16 luma samples.
     */
    final int[] mvy0;
    /**
     * The horizontal list 1 vector of each 4x4 block, in 1/16 luma samples.
     */
    final int[] mvx1;
    /**
     * The vertical list 1 vector of each 4x4 block, in 1/16 luma samples.
     */
    final int[] mvy1;
    /**
     * The list 0 reference index of each 4x4 block, -1 when list 0 is unused.
     */
    final byte[] ref0;
    /**
     * The list 1 reference index of each 4x4 block, -1 when list 1 is unused.
     */
    final byte[] ref1;

    /**
     * The width of the picture in 8x8 blocks.
     */
    final int width8;
    /**
     * The height of the picture in 8x8 blocks.
     */
    final int height8;
    /**
     * The horizontal list 0 vector of each 8x8 block of the compressed field temporal prediction reads.
     */
    final int[] colMvx0;
    /**
     * The vertical list 0 vector of each 8x8 block of the compressed field.
     */
    final int[] colMvy0;
    /**
     * The horizontal list 1 vector of each 8x8 block of the compressed field.
     */
    final int[] colMvx1;
    /**
     * The vertical list 1 vector of each 8x8 block of the compressed field.
     */
    final int[] colMvy1;
    /**
     * The picture order count of the list 0 reference of each 8x8 block of the compressed field.
     */
    final int[] colPoc0;
    /**
     * The picture order count of the list 1 reference of each 8x8 block of the compressed field.
     */
    final int[] colPoc1;
    /**
     * Per 8x8 block: bit 0 list 0 used, bit 1 list 1 used, bit 2 list 0 long-term, bit 3 list 1 long-term.
     */
    final byte[] colFlags;

    /**
     * The POC of every active reference of every slice of the picture, indexed by slice, list and index; read while
     * compressing the motion field.
     */
    int[][][] slicePocs = new int[0][][];
    /**
     * Whether every active reference of every slice of the picture is a long-term reference, indexed as {@link
     * #slicePocs}.
     */
    boolean[][][] sliceLongTerm = new boolean[0][][];
    /**
     * The number of slices whose references {@link #slicePocs} holds; the entries past it are unused.
     */
    int sliceRecords;
    /**
     * The slice index of each 4x4 block.
     */
    final short[] slice4;

    /**
     * Allocates a picture.
     *
     * @param width the luma width
     * @param height the luma height
     * @param chromaFormat {@code sps_chroma_format_idc}, 0 or 1
     * @param bitDepth the sample bit depth
     */
    public VvcPicture(int width, int height, int chromaFormat, int bitDepth) {
        this.width = width;
        this.height = height;
        this.chromaFormat = chromaFormat;
        this.shiftX = chromaFormat == 1 || chromaFormat == 2 ? 1 : 0;
        this.shiftY = chromaFormat == 1 ? 1 : 0;
        this.bitDepth = bitDepth;
        var components = chromaFormat == 0 ? 1 : 3;
        planes = new short[components][];
        planeWidth = new int[components];
        planeHeight = new int[components];
        for (var c = 0; c < components; c++) {
            planeWidth[c] = c == 0 ? width : width >> shiftX;
            planeHeight[c] = c == 0 ? height : height >> shiftY;
            planes[c] = new short[planeWidth[c] * planeHeight[c]];
        }
        width4 = (width + 3) >> 2;
        height4 = (height + 3) >> 2;
        var n4 = width4 * height4;
        mvx0 = new int[n4];
        mvy0 = new int[n4];
        mvx1 = new int[n4];
        mvy1 = new int[n4];
        ref0 = new byte[n4];
        ref1 = new byte[n4];
        slice4 = new short[n4];
        width8 = (width + 7) >> 3;
        height8 = (height + 7) >> 3;
        var n8 = width8 * height8;
        colMvx0 = new int[n8];
        colMvy0 = new int[n8];
        colMvx1 = new int[n8];
        colMvy1 = new int[n8];
        colPoc0 = new int[n8];
        colPoc1 = new int[n8];
        colFlags = new byte[n8];
    }

    /**
     * Clears the motion field to intra before decoding.
     */
    void resetMotion() {
        Arrays.fill(ref0, (byte) -1);
        Arrays.fill(ref1, (byte) -1);
        Arrays.fill(colFlags, (byte) 0);
    }

    /**
     * Fills every plane with mid-gray, the content of a picture generated for a missing reference (8.3.4).
     */
    void fillGray() {
        var gray = (short) (1 << (bitDepth - 1));
        for (var plane : planes) {
            Arrays.fill(plane, gray);
        }
        resetMotion();
    }

    /**
     * Reduces the motion field to the 8x8 grid, keeping the vectors of the top-left 4x4 block of each 8x8 block with
     * the POC and long-term marking of their reference pictures (8.5.2.12).
     */
    void compressMotion() {
        for (var y8 = 0; y8 < height8; y8++) {
            for (var x8 = 0; x8 < width8; x8++) {
                var i4 = (y8 << 1) * width4 + (x8 << 1);
                var i8 = y8 * width8 + x8;
                var flags = 0;
                var slice = slice4[i4];
                int r0 = ref0[i4];
                int r1 = ref1[i4];
                if (r0 >= 0 && slice < sliceRecords) {
                    flags |= 1;
                    colMvx0[i8] = mvx0[i4];
                    colMvy0[i8] = mvy0[i4];
                    colPoc0[i8] = slicePocs[slice][0][r0];
                    if (sliceLongTerm[slice][0][r0]) {
                        flags |= 4;
                    }
                }
                if (r1 >= 0 && slice < sliceRecords) {
                    flags |= 2;
                    colMvx1[i8] = mvx1[i4];
                    colMvy1[i8] = mvy1[i4];
                    colPoc1[i8] = slicePocs[slice][1][r1];
                    if (sliceLongTerm[slice][1][r1]) {
                        flags |= 8;
                    }
                }
                colFlags[i8] = (byte) flags;
            }
        }
    }
}
