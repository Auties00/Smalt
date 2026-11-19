/* Ports LibAtrac9 (C/src/structures.h) at commit efca2e3. Copyright 2018 Alex Barney; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.atrac9;

/**
 * The decoding state of one block (substream) of an ATRAC9 frame: a mono, stereo or low-frequency effects coding
 * unit of one or two channels, with the band, gradient, stereo and band extension parameters of the current frame.
 *
 * <p>Instances are not thread-safe.
 */
final class Atrac9Block {
    final Atrac9Config config;

    /**
     * The block type, {@link Atrac9Tables#MONO}, {@link Atrac9Tables#STEREO} or {@link Atrac9Tables#LFE}.
     */
    final int type;

    final Atrac9Channel[] channels;

    /**
     * Whether the frame is the first of its superframe, which forbids reusing the previous frame's parameters.
     */
    boolean firstInSuperframe;

    boolean reuseBandParams;

    int bandCount;

    int stereoBand;

    int extensionBand;

    int quantizationUnitCount;

    /**
     * The first quantization unit coded with intensity stereo: units from it on code only the primary channel.
     */
    int stereoQuantizationUnit;

    int extensionUnit;

    /**
     * The quantization units of the previous frame, the length of the scale factor baseline it leaves.
     */
    int quantizationUnitsPrevious;

    /**
     * The gradient of each quantization unit, up to {@link #quantizationUnitCount} inclusive.
     */
    final int[] gradient = new int[31];

    int gradientMode;

    int gradientStartUnit;

    int gradientStartValue;

    int gradientEndUnit;

    int gradientEndValue;

    /**
     * The quantization units below which every precision is raised by one.
     */
    int gradientBoundary;

    int primaryChannelIndex;

    boolean hasJointStereoSigns;

    final int[] jointStereoSigns = new int[30];

    boolean bandExtensionEnabled;

    boolean hasExtensionData;

    int bexDataLength;

    int bexMode;

    Atrac9Block(Atrac9Config config, int type) {
        this.config = config;
        this.type = type;
        this.channels = new Atrac9Channel[type == Atrac9Tables.STEREO ? 2 : 1];
        for (var i = 0; i < channels.length; i++) {
            channels[i] = new Atrac9Channel(this, i);
        }
    }
}
