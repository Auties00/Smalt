/* Ports OpenJPEG 2.5.3 (j2k.c opj_j2k_set_cinema_parameters, opj_j2k_set_imf_parameters, opj_j2k_is_imf_compliant;
   src/bin/jp2/opj_compress.c). Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium, and the
   OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Holds the settings of a {@link Jp2Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The wavelet decides whether coding can be lossless, and the quality layers decide the size:
 * each layer meets either a compression ratio or a PSNR target and refines the previous one, so a
 * decoder that reads only the first layers gets a smaller, coarser image.
 */
public final class Jp2EncoderOptions {
    /**
     * The largest number of quality layers.
     */
    private static final int MAX_LAYERS = 100;

    /**
     * The longest comment, in characters: a {@code COM} segment holds 65535 bytes, four of them its
     * length and registration fields.
     */
    private static final int MAX_COMMENT = 65531;

    /**
     * The largest number of progression changes.
     */
    private static final int MAX_PROGRESSION_CHANGES = 255;

    /**
     * The largest position on the reference grid, the range of the {@code SIZ} fields.
     */
    private static final long MAX_POSITION = 0xFFFF_FFFEL;

    /**
     * The largest codestream of a digital cinema frame at 24 frames per second, in bytes.
     */
    private static final long CINEMA_24_CODESTREAM = 1302083;

    /**
     * The largest codestream of a digital cinema frame at 48 frames per second, in bytes.
     */
    private static final long CINEMA_48_CODESTREAM = 651041;

    /**
     * The largest component of a digital cinema frame at 24 frames per second, in bytes.
     */
    private static final long CINEMA_24_COMPONENT = 1041666;

    /**
     * The largest component of a digital cinema frame at 48 frames per second, in bytes.
     */
    private static final long CINEMA_48_COMPONENT = 520833;

    /**
     * The bit rate limit of each IMF sub level from 1 to 9, in megabits per second (ISO/IEC 15444-1
     * Table A.54).
     */
    private static final long[] IMF_SUB_LEVEL_MEGABITS = {0, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200};

    /**
     * The largest IMF sub level of each main level from 0 to 11 (ISO/IEC 15444-1 Table A.53).
     */
    private static final int[] IMF_MAX_SUB_LEVEL = {15, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    /**
     * The options of {@link #defaults()}.
     */
    private static final Jp2EncoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final Jp2Format format;

    private final boolean reversible;

    /**
     * The compression ratio of each layer, or {@code null} when layers target PSNR values.
     */
    private final double[] ratios;

    /**
     * The PSNR target of each layer, or {@code null} when layers target ratios.
     */
    private final double[] psnrTargets;

    private final int decompositionLevels;

    /**
     * The tile width, or 0 for one tile.
     */
    private final int tileWidth;

    /**
     * The tile height, or 0 for one tile.
     */
    private final int tileHeight;

    private final int codeBlockWidth;

    private final int codeBlockHeight;

    /**
     * The precinct widths from the highest resolution down, or {@code null} for maximal precincts.
     */
    private final int[] precinctWidths;

    /**
     * The precinct heights from the highest resolution down, or {@code null} for maximal precincts.
     */
    private final int[] precinctHeights;

    private final long imageOriginX;

    private final long imageOriginY;

    private final long tileOriginX;

    private final long tileOriginY;

    private final Jp2ProgressionOrder progression;

    private final List<Jp2ProgressionChange> progressionChanges;

    /**
     * The tile-part division, or {@code null} for one tile-part per progression.
     */
    private final Jp2TilePartDivision tilePartDivision;

    private final boolean tlmMarkers;

    private final boolean pltMarkers;

    private final Jp2RegionOfInterest regionOfInterest;

    private final int guardBits;

    private final long maxCodestreamBytes;

    private final long maxComponentBytes;

    private final Jp2Profile profile;

    private final int imfMainLevel;

    private final int imfSubLevel;

    private final ExifData exif;

    private final XmpPacket xmp;

    private final IptcData iptc;

    private final Set<Jp2CodeBlockStyle> codeBlockStyles;

    private final boolean highThroughput;

    private final boolean sopMarkers;

    private final boolean ephMarkers;

    private final boolean colorTransform;

    private final ColorProfile colorProfile;

    private final String comment;

    private Jp2EncoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.format = builder.format;
        this.reversible = builder.reversible;
        this.ratios = builder.ratios == null ? null : builder.ratios.clone();
        this.psnrTargets = builder.psnrTargets == null ? null : builder.psnrTargets.clone();
        this.decompositionLevels = builder.decompositionLevels;
        this.tileWidth = builder.tileWidth;
        this.tileHeight = builder.tileHeight;
        this.codeBlockWidth = builder.codeBlockWidth;
        this.codeBlockHeight = builder.codeBlockHeight;
        this.precinctWidths = builder.precinctWidths;
        this.precinctHeights = builder.precinctHeights;
        this.imageOriginX = builder.imageOriginX;
        this.imageOriginY = builder.imageOriginY;
        this.tileOriginX = builder.tileOriginX;
        this.tileOriginY = builder.tileOriginY;
        this.progression = builder.progression;
        this.progressionChanges = builder.progressionChanges;
        this.tilePartDivision = builder.tilePartDivision;
        this.tlmMarkers = builder.tlmMarkers;
        this.pltMarkers = builder.pltMarkers;
        this.regionOfInterest = builder.regionOfInterest;
        this.guardBits = builder.guardBits;
        this.maxCodestreamBytes = builder.codestreamLimited ? builder.maxCodestreamBytes : 0;
        this.maxComponentBytes = builder.componentLimited ? builder.maxComponentBytes : 0;
        this.profile = builder.profile;
        this.imfMainLevel = builder.imfMainLevel;
        this.imfSubLevel = builder.imfSubLevel;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.iptc = builder.iptc;
        this.codeBlockStyles = builder.codeBlockStyles.isEmpty() ? Set.of()
                : Set.copyOf(EnumSet.copyOf(builder.codeBlockStyles));
        this.highThroughput = builder.highThroughput;
        this.sopMarkers = builder.sopMarkers;
        this.ephMarkers = builder.ephMarkers;
        this.colorTransform = builder.colorTransform;
        this.colorProfile = builder.colorProfile;
        this.comment = builder.comment;
    }

    /**
     * Returns lossless coding in one layer: the 5-3 wavelet, 5 decomposition levels, 64x64
     * code-blocks, maximal precincts, LRCP progression, one tile, the component transform, and a
     * JP2 file.
     *
     * @return the default options
     */
    public static Jp2EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns lossless coding in one layer, the same as {@link #defaults()}.
     *
     * @return the options
     */
    public static Jp2EncoderOptions lossless() {
        return DEFAULTS;
    }

    /**
     * Returns lossy coding with the 9-7 wavelet at a compression ratio, in one quality layer; every
     * other setting is that of {@link #defaults()}.
     *
     * @param compressionRatio how many times smaller than the raw samples the file is, above 1;
     * 10 to 20 keeps photographs visually lossless
     * @return the options
     * @throws IllegalArgumentException if {@code compressionRatio} is not above 1 and finite
     */
    public static Jp2EncoderOptions lossy(double compressionRatio) {
        if (!(compressionRatio > 1) || Double.isInfinite(compressionRatio)) {
            throw new IllegalArgumentException("compression ratio " + compressionRatio + " is not above 1");
        }
        return new Builder().reversible(false).compressionRatios(compressionRatio).build();
    }

    /**
     * Returns lossy coding with the 9-7 wavelet at a PSNR target, in one quality layer; every other
     * setting is that of {@link #defaults()}.
     *
     * @param decibels the PSNR the decoded image reaches, above 0; 40 is visually lossless
     * @return the options
     * @throws IllegalArgumentException if {@code decibels} is not positive and finite
     */
    public static Jp2EncoderOptions ofPsnr(double decibels) {
        if (!(decibels > 0) || Double.isInfinite(decibels)) {
            throw new IllegalArgumentException("PSNR " + decibels + " is not positive");
        }
        return new Builder().reversible(false).psnrTargets(decibels).build();
    }

    /**
     * Returns the settings of a digital cinema 2K codestream ({@link Jp2Profile#CINEMA_2K},
     * {@code opj_compress -cinema2K}): the 9-7 wavelet, 5 decomposition levels, 32x32 code-blocks,
     * 256x256 precincts, CPRL progression, one guard bit, a tile-part per component, {@code TLM}
     * markers, and one quality layer meeting the frame rate's size limits; every other setting is
     * that of {@link #defaults()}.
     *
     * @param framesPerSecond the frame rate, 24 or 48
     * @return the options
     * @throws IllegalArgumentException if {@code framesPerSecond} is neither 24 nor 48
     */
    public static Jp2EncoderOptions forCinema2k(int framesPerSecond) {
        if (framesPerSecond != 24 && framesPerSecond != 48) {
            throw new IllegalArgumentException("digital cinema 2K runs at 24 or 48 frames per second, not "
                    + framesPerSecond);
        }
        var fast = framesPerSecond == 48;
        return cinema(Jp2Profile.CINEMA_2K, 1).maxCodestreamBytes(fast ? CINEMA_48_CODESTREAM : CINEMA_24_CODESTREAM)
                .maxComponentBytes(fast ? CINEMA_48_COMPONENT : CINEMA_24_COMPONENT).build();
    }

    /**
     * Returns the settings of a digital cinema 4K codestream ({@link Jp2Profile#CINEMA_4K},
     * {@code opj_compress -cinema4K}): as {@link #forCinema2k(int)} at 24 frames per second, with
     * two guard bits and the two progressions of the profile in the main header.
     *
     * @return the options
     */
    public static Jp2EncoderOptions forCinema4k() {
        var levels = 5;
        return cinema(Jp2Profile.CINEMA_4K, 2).maxCodestreamBytes(CINEMA_24_CODESTREAM)
                .maxComponentBytes(CINEMA_24_COMPONENT)
                .progressionChanges(List.of(
                        new Jp2ProgressionChange(0, 0, 1, levels, 3, Jp2ProgressionOrder.CPRL),
                        new Jp2ProgressionChange(levels, 0, 1, levels + 1, 3, Jp2ProgressionOrder.CPRL)))
                .build();
    }

    /**
     * Returns a builder holding the settings shared by the digital cinema profiles.
     *
     * @param profile the cinema profile
     * @param guardBits the number of guard bits
     * @return the builder
     */
    private static Builder cinema(Jp2Profile profile, int guardBits) {
        return new Builder().profile(profile).reversible(false).decompositionLevels(5).codeBlockSize(32, 32)
                .precinctSizes(profilePrecincts(5), profilePrecincts(5)).progressionOrder(Jp2ProgressionOrder.CPRL)
                .guardBits(guardBits).tilePartDivision(Jp2TilePartDivision.COMPONENT).tlmMarkers(true);
    }

    /**
     * Returns the settings of an IMF codestream ({@code opj_compress -IMF}): 32x32 code-blocks,
     * 256x256 precincts (128x128 at the lowest resolution), CPRL progression, a tile-part per
     * component, {@code TLM} markers, the 9-7 wavelet and 5 decomposition levels for the lossy
     * profiles, lossless coding with the 5-3 wavelet and 4 decomposition levels for the reversible
     * ones (the most they allow for every tile width from 1024 samples; a tile of 2048 samples or more
     * allows more), and, when a frame rate and a sub level are given, one quality layer within the sub
     * level's bit rate; every other setting is that of {@link #defaults()}.
     *
     * @param profile the IMF profile
     * @param mainLevel the main level, from 0 (unspecified) to 11
     * @param subLevel the sub level, from 0 (unspecified) to the largest the main level allows (1 for
     * main levels 1 to 3, the main level minus 2 above)
     * @param framesPerSecond the frame rate, positive, or 0 to leave the size unbounded
     * @return the options
     * @throws IllegalArgumentException if {@code profile} is not an IMF profile, a level is out of
     * range, or {@code framesPerSecond} is negative
     */
    public static Jp2EncoderOptions forImf(Jp2Profile profile, int mainLevel, int subLevel, int framesPerSecond) {
        Objects.requireNonNull(profile, "profile");
        if (profile.isCinema()) {
            throw new IllegalArgumentException(profile + " is not an IMF profile");
        }
        Checks.inRange("frame rate", framesPerSecond, 0, Integer.MAX_VALUE);
        var levels = profile.isReversibleImf() ? 4 : 5;
        var builder = new Builder().profile(profile).imfLevels(mainLevel, subLevel)
                .reversible(profile.isReversibleImf()).decompositionLevels(levels).codeBlockSize(32, 32)
                .precinctSizes(profilePrecincts(levels), profilePrecincts(levels))
                .progressionOrder(Jp2ProgressionOrder.CPRL)
                .tilePartDivision(Jp2TilePartDivision.COMPONENT).tlmMarkers(true);
        if (framesPerSecond > 0 && subLevel >= 1 && subLevel <= 9) {
            builder.maxCodestreamBytes(IMF_SUB_LEVEL_MEGABITS[subLevel] * 125_000 / framesPerSecond);
        }
        return builder.build();
    }

    /**
     * Returns the precinct sizes of the cinema and IMF profiles: 256 for every resolution but the
     * lowest, which the halving of the last size makes 128.
     *
     * @param levels the number of decomposition levels
     * @return the sizes, from the highest resolution down
     */
    private static int[] profilePrecincts(int levels) {
        var sizes = new int[Math.max(1, levels)];
        Arrays.fill(sizes, levels == 0 ? 128 : 256);
        return sizes;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the resource bounds checked before every allocation; {@link MediaLimits#DEFAULT} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the output form; {@link Jp2Format#JP2} by default}
     */
    public Jp2Format format() {
        return format;
    }

    /**
     * {@return whether the reversible 5-3 wavelet is used rather than the irreversible 9-7 one;
     * {@code true} by default}
     *
     * <p>The 5-3 wavelet without quantization reproduces every sample exactly when the last quality
     * layer holds every coding pass; the 9-7 wavelet compresses better but always loses some
     * precision.
     */
    public boolean isReversible() {
        return reversible;
    }

    /**
     * {@return whether every sample is reproduced exactly: the 5-3 wavelet with a last layer that
     * holds every coding pass, and no codestream size limit}
     */
    public boolean isLossless() {
        var layers = ratios != null ? ratios : psnrTargets;
        var last = layers[layers.length - 1];
        return reversible && maxCodestreamBytes == 0 && (ratios != null ? last <= 1 : last == 0);
    }

    /**
     * {@return a copy of the compression ratio of each quality layer, in layer order, or an empty
     * array when the layers target PSNR values; {@code {0}} by default}
     *
     * <p>A layer meeting a ratio makes the file that many times smaller than the raw samples; a
     * ratio of 0 or 1 gives the layer every coding pass left.
     */
    public double[] compressionRatios() {
        return ratios == null ? new double[0] : ratios.clone();
    }

    /**
     * {@return a copy of the PSNR target of each quality layer in decibels, in layer order, or an
     * empty array when the layers target compression ratios; empty by default}
     *
     * <p>A target of 0 gives the layer every coding pass left.
     */
    public double[] psnrTargets() {
        return psnrTargets == null ? new double[0] : psnrTargets.clone();
    }

    /**
     * {@return the number of wavelet decomposition levels, from 0 to 32; 5 by default}
     */
    public int decompositionLevels() {
        return decompositionLevels;
    }

    /**
     * {@return the tile width, positive, or 0 for one tile; 0 by default}
     */
    public int tileWidth() {
        return tileWidth;
    }

    /**
     * {@return the tile height, positive, or 0 for one tile; 0 by default}
     */
    public int tileHeight() {
        return tileHeight;
    }

    /**
     * {@return the code-block width, a power of two from 4 to 1024; 64 by default}
     *
     * <p>A code-block holds at most 4096 samples.
     */
    public int codeBlockWidth() {
        return codeBlockWidth;
    }

    /**
     * {@return the code-block height, a power of two from 4 to 1024; 64 by default}
     *
     * <p>A code-block holds at most 4096 samples.
     */
    public int codeBlockHeight() {
        return codeBlockHeight;
    }

    /**
     * {@return the precinct width at the highest resolution, a power of two from 2 to 32768, or 0
     * for maximal precincts; 0 by default}
     *
     * <p>{@link #precinctWidths()} gives the width of every resolution.
     */
    public int precinctWidth() {
        return precinctWidths == null ? 0 : precinctWidths[0];
    }

    /**
     * {@return the precinct height at the highest resolution, a power of two from 2 to 32768, or 0
     * for maximal precincts; 0 by default}
     *
     * <p>{@link #precinctHeights()} gives the height of every resolution.
     */
    public int precinctHeight() {
        return precinctHeights == null ? 0 : precinctHeights[0];
    }

    /**
     * {@return a copy of the precinct widths, from the highest resolution down, each a power of two
     * from 2 to 32768, or an empty array for maximal precincts; empty by default}
     *
     * <p>A resolution below the last one listed takes the last width halved once per level, but
     * never less than 2 except at the lowest resolution, where it is at least 1.
     */
    public int[] precinctWidths() {
        return precinctWidths == null ? new int[0] : precinctWidths.clone();
    }

    /**
     * {@return a copy of the precinct heights, from the highest resolution down, as for
     * {@link #precinctWidths()}; empty by default}
     */
    public int[] precinctHeights() {
        return precinctHeights == null ? new int[0] : precinctHeights.clone();
    }

    /**
     * {@return the position of the top-left sample of an encoded {@link com.github.auties00.smalt.image.Picture}
     * on the reference grid, horizontally, from 0 to 4294967294; 0 by default}
     *
     * <p>The codestream's {@code XOsiz} field. A {@link Jp2Image} is encoded at its own origin. A
     * subsampled component starts at the first of its samples at or right of the origin.
     */
    public long imageOriginX() {
        return imageOriginX;
    }

    /**
     * {@return the position of the top-left sample of an encoded picture on the reference grid,
     * vertically, as for {@link #imageOriginX()}; 0 by default}
     */
    public long imageOriginY() {
        return imageOriginY;
    }

    /**
     * {@return the left edge of the first tile column on the reference grid, from 0 to 4294967294;
     * 0 by default}
     *
     * <p>The codestream's {@code XTOsiz} field. The encoder requires it to be at most the image's
     * left edge and more than the image's left edge minus the tile width, so that the first tile
     * column meets the image. With one tile, the tile spans from this edge to the image's right
     * edge.
     */
    public long tileOriginX() {
        return tileOriginX;
    }

    /**
     * {@return the top edge of the first tile row on the reference grid, as for
     * {@link #tileOriginX()}; 0 by default}
     */
    public long tileOriginY() {
        return tileOriginY;
    }

    /**
     * {@return the progression order; {@link Jp2ProgressionOrder#LRCP} by default}
     */
    public Jp2ProgressionOrder progressionOrder() {
        return progression;
    }

    /**
     * {@return the progression changes of every tile, in order, at most 255, or an empty list to
     * follow {@link #progressionOrder()}; empty by default}
     *
     * <p>The changes are written in a {@code POC} marker segment: in the main header under the
     * {@link Jp2Profile#CINEMA_4K} profile, in the first tile-part header of every tile otherwise
     * ({@code opj_compress -POC} with the same changes for every tile). Each progression starts at
     * least one tile-part of its own. Every change must start at a component the image has.
     */
    public List<Jp2ProgressionChange> progressionChanges() {
        return progressionChanges;
    }

    /**
     * {@return how the packets of each tile are divided into tile-parts, or empty for one tile-part
     * per progression; empty by default}
     *
     * <p>A tile holds at most 255 tile-parts.
     */
    public Optional<Jp2TilePartDivision> tilePartDivision() {
        return Optional.ofNullable(tilePartDivision);
    }

    /**
     * {@return whether the main header holds {@code TLM} marker segments listing the length of every
     * tile-part; {@code false} by default}
     *
     * <p>A decoder or a server then finds every tile-part without reading the tile-part headers.
     */
    public boolean tlmMarkers() {
        return tlmMarkers;
    }

    /**
     * {@return whether the header of every tile-part that holds packets has {@code PLT} marker
     * segments listing the length of each of them; {@code false} by default}
     */
    public boolean pltMarkers() {
        return pltMarkers;
    }

    /**
     * {@return the region of interest coded ahead of the rest of the image, or empty for none; empty
     * by default}
     *
     * <p>A region needs the block coder of ISO/IEC 15444-1, not the high-throughput one.
     */
    public Optional<Jp2RegionOfInterest> regionOfInterest() {
        return Optional.ofNullable(regionOfInterest);
    }

    /**
     * {@return the number of guard bits of the quantization, from 0 to 7; 2 by default}
     *
     * <p>Guard bits leave room for the growth of the wavelet coefficients; with fewer, a sub-band
     * holds fewer magnitude bit-planes, and an image whose coefficients outgrow them cannot be
     * coded losslessly ({@code opj_compress -GuardBits}).
     */
    public int guardBits() {
        return guardBits;
    }

    /**
     * {@return the largest codestream, in bytes, or empty for no limit; empty by default}
     *
     * <p>Every quality layer is then coded at a compression ratio of at least the one this size
     * gives the image (the raw size of the first component's grid at its precision times the
     * component count, over 8 times this size): a ratio target below it, or a layer that would take
     * every pass, is raised to it, and a PSNR target stops at it. The codestream can then exceed the
     * size only by its headers.
     */
    public OptionalLong maxCodestreamBytes() {
        return maxCodestreamBytes == 0 ? OptionalLong.empty() : OptionalLong.of(maxCodestreamBytes);
    }

    /**
     * {@return the largest size of the packets of one component in a tile, in bytes, or empty for
     * no limit; empty by default}
     *
     * <p>The rate allocation of every layer lowers its precision until each component's packets fit.
     */
    public OptionalLong maxComponentBytes() {
        return maxComponentBytes == 0 ? OptionalLong.empty() : OptionalLong.of(maxComponentBytes);
    }

    /**
     * {@return the codestream profile, or empty for none ({@code Rsiz} 0, or the HTJ2K capability
     * flag with the high-throughput block coder); empty by default}
     *
     * <p>{@link Builder#build()} checks that the settings meet the profile, and the encoder that the image
     * does (its component count, precision, subsampling and size, and the decomposition levels and
     * tiles the profile allows for that size).
     */
    public Optional<Jp2Profile> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * {@return the IMF main level written in the {@code Rsiz} field with an IMF profile, from 0
     * (unspecified) to 11; 0 by default}
     */
    public int imfMainLevel() {
        return imfMainLevel;
    }

    /**
     * {@return the IMF sub level written in the {@code Rsiz} field with an IMF profile, from 0
     * (unspecified) to the largest {@link #imfMainLevel()} allows; 0 by default}
     */
    public int imfSubLevel() {
        return imfSubLevel;
    }

    /**
     * {@return the EXIF metadata written in a JP2 file's {@code uuid} box (the {@code JpgTiffExif->JP2}
     * UUID), or empty for none; empty by default}
     *
     * <p>A {@link Jp2Image}'s own EXIF block is written when this is empty. A raw codestream holds
     * no metadata.
     */
    public Optional<ExifData> exif() {
        return Optional.ofNullable(exif);
    }

    /**
     * {@return the XMP packet written in a JP2 file's XMP {@code uuid} box, or empty for none; empty
     * by default}
     *
     * <p>A {@link Jp2Image}'s own XMP packet is written when this is empty.
     */
    public Optional<XmpPacket> xmp() {
        return Optional.ofNullable(xmp);
    }

    /**
     * {@return the IPTC-IIM metadata written in a JP2 file's IPTC {@code uuid} box, or empty for
     * none; empty by default}
     */
    public Optional<IptcData> iptc() {
        return Optional.ofNullable(iptc);
    }

    /**
     * {@return the code-block style options; empty by default}
     */
    public Set<Jp2CodeBlockStyle> codeBlockStyles() {
        return codeBlockStyles;
    }

    /**
     * {@return whether code-blocks are coded with the high-throughput block coder of ISO/IEC 15444-15
     * (HTJ2K) rather than that of ISO/IEC 15444-1; {@code false} by default}
     *
     * <p>The high-throughput coder decodes faster than the block coder of ISO/IEC 15444-1, and
     * encodes faster when the last quality layer takes every pass, for a slightly larger file;
     * meeting a lossy target of the last layer codes each code-block at every bit-plane before
     * choosing one. It allows only {@link Jp2CodeBlockStyle#VERTICALLY_CAUSAL} among the code-block styles. A
     * file is then a JPH file ({@code .jph}, ISO/IEC 15444-15 annex D) and a raw codestream an HTJ2K
     * codestream ({@code .jhc}); decoders that implement only ISO/IEC 15444-1 cannot read them. Each
     * code-block is coded once, at the precision the last quality layer needs: an earlier layer holds
     * whole code-blocks, possibly without their refinement passes, or nothing of a code-block.
     */
    public boolean highThroughput() {
        return highThroughput;
    }

    /**
     * {@return whether packets start with {@code SOP} marker segments; {@code false} by default}
     */
    public boolean sopMarkers() {
        return sopMarkers;
    }

    /**
     * {@return whether packet headers end with {@code EPH} markers; {@code false} by default}
     */
    public boolean ephMarkers() {
        return ephMarkers;
    }

    /**
     * {@return whether the first three channels go through the component transform, which
     * decorrelates them and compresses better; {@code true} by default}
     *
     * <p>The transform applies only to an image with at least three channels whose first three
     * share one subsampling and whose color space is not sYCC.
     */
    public boolean colorTransform() {
        return colorTransform;
    }

    /**
     * {@return the color profile embedded in a JP2 file, or empty to declare sRGB (or gray); empty
     * by default}
     *
     * <p>A high-dynamic-range profile is written in its ICC form, whose {@code cicp} tag carries its PQ
     * or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the comment written in the codestream, Latin-1 text of at most 65531 characters, or
     * empty for none; empty by default}
     */
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Jp2EncoderOptions that && limits.equals(that.limits) && format == that.format
                && reversible == that.reversible && Arrays.equals(ratios, that.ratios)
                && Arrays.equals(psnrTargets, that.psnrTargets) && decompositionLevels == that.decompositionLevels
                && tileWidth == that.tileWidth
                && tileHeight == that.tileHeight && codeBlockWidth == that.codeBlockWidth
                && codeBlockHeight == that.codeBlockHeight && Arrays.equals(precinctWidths, that.precinctWidths)
                && Arrays.equals(precinctHeights, that.precinctHeights) && imageOriginX == that.imageOriginX
                && imageOriginY == that.imageOriginY && tileOriginX == that.tileOriginX
                && tileOriginY == that.tileOriginY && progression == that.progression
                && progressionChanges.equals(that.progressionChanges) && tilePartDivision == that.tilePartDivision
                && tlmMarkers == that.tlmMarkers && pltMarkers == that.pltMarkers
                && Objects.equals(regionOfInterest, that.regionOfInterest) && guardBits == that.guardBits
                && maxCodestreamBytes == that.maxCodestreamBytes && maxComponentBytes == that.maxComponentBytes
                && profile == that.profile && imfMainLevel == that.imfMainLevel && imfSubLevel == that.imfSubLevel
                && codeBlockStyles.equals(that.codeBlockStyles) && highThroughput == that.highThroughput
                && sopMarkers == that.sopMarkers
                && ephMarkers == that.ephMarkers && colorTransform == that.colorTransform
                && Objects.equals(colorProfile, that.colorProfile) && Objects.equals(comment, that.comment)
                && Objects.equals(exif, that.exif) && Objects.equals(xmp, that.xmp) && Objects.equals(iptc, that.iptc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, format, reversible, Arrays.hashCode(ratios), Arrays.hashCode(psnrTargets),
                decompositionLevels, tileWidth, tileHeight, codeBlockWidth, codeBlockHeight,
                Arrays.hashCode(precinctWidths), Arrays.hashCode(precinctHeights), imageOriginX, imageOriginY,
                tileOriginX, tileOriginY, progression, progressionChanges, tilePartDivision, tlmMarkers, pltMarkers,
                regionOfInterest, guardBits, maxCodestreamBytes, maxComponentBytes, profile, imfMainLevel,
                imfSubLevel, codeBlockStyles, highThroughput, sopMarkers, ephMarkers, colorTransform, colorProfile,
                comment, exif, xmp, iptc);
    }

    @Override
    public String toString() {
        return "Jp2EncoderOptions[format=" + format + ", reversible=" + reversible
                + (ratios != null ? ", ratios=" + Arrays.toString(ratios) : ", psnr=" + Arrays.toString(psnrTargets))
                + ", levels=" + decompositionLevels + ", tiles=" + tileWidth + "x" + tileHeight + ", codeBlocks="
                + codeBlockWidth + "x" + codeBlockHeight + ", precincts=" + Arrays.toString(precinctWidths) + "x"
                + Arrays.toString(precinctHeights) + ", imageOrigin=" + imageOriginX + "," + imageOriginY
                + ", tileOrigin=" + tileOriginX + "," + tileOriginY
                + ", progression=" + progression + ", progressionChanges=" + progressionChanges.size()
                + ", tileParts=" + tilePartDivision + ", tlm=" + tlmMarkers + ", plt=" + pltMarkers + ", roi="
                + regionOfInterest + ", guardBits=" + guardBits + ", maxCodestreamBytes=" + maxCodestreamBytes()
                + ", maxComponentBytes=" + maxComponentBytes() + ", profile=" + profile
                + (profile != null && !profile.isCinema() ? " " + imfMainLevel + "/" + imfSubLevel : "")
                + ", styles=" + codeBlockStyles + ", highThroughput=" + highThroughput
                + ", sop=" + sopMarkers + ", eph="
                + ephMarkers + ", colorTransform=" + colorTransform + ", colorProfile=" + (colorProfile != null)
                + ", comment=" + (comment != null) + ", exif=" + (exif != null) + ", xmp=" + (xmp != null)
                + ", iptc=" + (iptc != null) + "]";
    }

    /**
     * {@return the {@code Rsiz} value of the profile, with the IMF levels, or 0 without a profile}
     */
    int profileCode() {
        if (profile == null) {
            return 0;
        }
        return profile.isCinema() ? profile.code() : profile.code() | imfSubLevel << 4 | imfMainLevel;
    }

    /**
     * A builder of {@link Jp2EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges, the comment, the
     * color profile and the rules of the codestream profile. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Jp2Format format = Jp2Format.JP2;

        private boolean reversible = true;

        private double[] ratios = {0};

        private double[] psnrTargets;

        private int decompositionLevels = 5;

        private int tileWidth;

        private int tileHeight;

        /**
         * Whether {@link #tileSize(int, int)} set the tile size, so that {@link #build()} checks it.
         */
        private boolean tilesSet;

        private int codeBlockWidth = 64;

        private int codeBlockHeight = 64;

        private int[] precinctWidths;

        private int[] precinctHeights;

        private long imageOriginX;

        private long imageOriginY;

        private long tileOriginX;

        private long tileOriginY;

        private Jp2ProgressionOrder progression = Jp2ProgressionOrder.LRCP;

        private List<Jp2ProgressionChange> progressionChanges = List.of();

        private Jp2TilePartDivision tilePartDivision;

        private boolean tlmMarkers;

        private boolean pltMarkers;

        private Jp2RegionOfInterest regionOfInterest;

        private int guardBits = 2;

        private long maxCodestreamBytes;

        /**
         * Whether {@link #maxCodestreamBytes(long)} set a limit, so that {@link #build()} checks it.
         */
        private boolean codestreamLimited;

        private long maxComponentBytes;

        /**
         * Whether {@link #maxComponentBytes(long)} set a limit, so that {@link #build()} checks it.
         */
        private boolean componentLimited;

        private Jp2Profile profile;

        private int imfMainLevel;

        private int imfSubLevel;

        private Set<Jp2CodeBlockStyle> codeBlockStyles = EnumSet.noneOf(Jp2CodeBlockStyle.class);

        private boolean highThroughput;

        private boolean sopMarkers;

        private boolean ephMarkers;

        private boolean colorTransform = true;

        private ColorProfile colorProfile;

        private String comment;

        private ExifData exif;

        private XmpPacket xmp;

        private IptcData iptc;

        private Builder() {
        }

        private Builder(Jp2EncoderOptions options) {
            this.limits = options.limits;
            this.format = options.format;
            this.reversible = options.reversible;
            this.ratios = options.ratios;
            this.psnrTargets = options.psnrTargets;
            this.decompositionLevels = options.decompositionLevels;
            this.tileWidth = options.tileWidth;
            this.tileHeight = options.tileHeight;
            this.tilesSet = options.tileWidth != 0;
            this.codeBlockWidth = options.codeBlockWidth;
            this.codeBlockHeight = options.codeBlockHeight;
            this.precinctWidths = options.precinctWidths;
            this.precinctHeights = options.precinctHeights;
            this.imageOriginX = options.imageOriginX;
            this.imageOriginY = options.imageOriginY;
            this.tileOriginX = options.tileOriginX;
            this.tileOriginY = options.tileOriginY;
            this.progression = options.progression;
            this.progressionChanges = options.progressionChanges;
            this.tilePartDivision = options.tilePartDivision;
            this.tlmMarkers = options.tlmMarkers;
            this.pltMarkers = options.pltMarkers;
            this.regionOfInterest = options.regionOfInterest;
            this.guardBits = options.guardBits;
            this.maxCodestreamBytes = options.maxCodestreamBytes;
            this.codestreamLimited = options.maxCodestreamBytes != 0;
            this.maxComponentBytes = options.maxComponentBytes;
            this.componentLimited = options.maxComponentBytes != 0;
            this.profile = options.profile;
            this.imfMainLevel = options.imfMainLevel;
            this.imfSubLevel = options.imfSubLevel;
            this.codeBlockStyles = options.codeBlockStyles.isEmpty() ? EnumSet.noneOf(Jp2CodeBlockStyle.class)
                    : EnumSet.copyOf(options.codeBlockStyles);
            this.highThroughput = options.highThroughput;
            this.sopMarkers = options.sopMarkers;
            this.ephMarkers = options.ephMarkers;
            this.colorTransform = options.colorTransform;
            this.colorProfile = options.colorProfile;
            this.comment = options.comment;
            this.exif = options.exif;
            this.xmp = options.xmp;
            this.iptc = options.iptc;
        }

        /**
         * Sets the resource bounds; see {@link Jp2EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the output form; see {@link Jp2EncoderOptions#format()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder format(Jp2Format format) {
            this.format = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets the wavelet; see {@link Jp2EncoderOptions#isReversible()}.
         *
         * @param reversible the value
         * @return this builder
         */
        public Builder reversible(boolean reversible) {
            this.reversible = reversible;
            return this;
        }

        /**
         * Makes the quality layers meet compression ratios, replacing any PSNR targets; see
         * {@link Jp2EncoderOptions#compressionRatios()}.
         *
         * @param ratios the value, copied
         * @return this builder
         */
        public Builder compressionRatios(double... ratios) {
            this.ratios = ratios.clone();
            this.psnrTargets = null;
            return this;
        }

        /**
         * Makes the quality layers meet PSNR targets, replacing any compression ratios; see
         * {@link Jp2EncoderOptions#psnrTargets()}.
         *
         * @param decibels the value, copied
         * @return this builder
         */
        public Builder psnrTargets(double... decibels) {
            this.psnrTargets = decibels.clone();
            this.ratios = null;
            return this;
        }

        /**
         * Sets the number of decomposition levels; see
         * {@link Jp2EncoderOptions#decompositionLevels()}.
         *
         * @param levels the value
         * @return this builder
         */
        public Builder decompositionLevels(int levels) {
            this.decompositionLevels = levels;
            return this;
        }

        /**
         * Sets the tile size; see {@link Jp2EncoderOptions#tileWidth()} and
         * {@link Jp2EncoderOptions#tileHeight()}.
         *
         * @param width the tile width
         * @param height the tile height
         * @return this builder
         */
        public Builder tileSize(int width, int height) {
            this.tileWidth = width;
            this.tileHeight = height;
            this.tilesSet = true;
            return this;
        }

        /**
         * Codes the image as one tile; see {@link Jp2EncoderOptions#tileWidth()}.
         *
         * @return this builder
         */
        public Builder noTiles() {
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Sets the code-block size; see {@link Jp2EncoderOptions#codeBlockWidth()} and
         * {@link Jp2EncoderOptions#codeBlockHeight()}.
         *
         * @param width the code-block width
         * @param height the code-block height
         * @return this builder
         */
        public Builder codeBlockSize(int width, int height) {
            this.codeBlockWidth = width;
            this.codeBlockHeight = height;
            return this;
        }

        /**
         * Sets the precinct size at the highest resolution, each lower resolution halving it; see
         * {@link Jp2EncoderOptions#precinctWidths()} and {@link Jp2EncoderOptions#precinctHeights()}.
         *
         * @param width the precinct width
         * @param height the precinct height
         * @return this builder
         */
        public Builder precinctSize(int width, int height) {
            this.precinctWidths = new int[]{width};
            this.precinctHeights = new int[]{height};
            return this;
        }

        /**
         * Sets the precinct sizes from the highest resolution down ({@code opj_compress -c} with
         * several records); see {@link Jp2EncoderOptions#precinctWidths()} and
         * {@link Jp2EncoderOptions#precinctHeights()}.
         *
         * @param widths the precinct widths, copied
         * @param heights the precinct heights, copied
         * @return this builder
         */
        public Builder precinctSizes(int[] widths, int[] heights) {
            this.precinctWidths = widths.clone();
            this.precinctHeights = heights.clone();
            return this;
        }

        /**
         * Uses maximal precincts; see {@link Jp2EncoderOptions#precinctWidths()}.
         *
         * @return this builder
         */
        public Builder maximalPrecincts() {
            this.precinctWidths = null;
            this.precinctHeights = null;
            return this;
        }

        /**
         * Sets the position of an encoded picture on the reference grid; see
         * {@link Jp2EncoderOptions#imageOriginX()} and {@link Jp2EncoderOptions#imageOriginY()}.
         *
         * @param x the horizontal position
         * @param y the vertical position
         * @return this builder
         */
        public Builder imageOrigin(long x, long y) {
            this.imageOriginX = x;
            this.imageOriginY = y;
            return this;
        }

        /**
         * Sets the position of the tile grid on the reference grid; see
         * {@link Jp2EncoderOptions#tileOriginX()} and {@link Jp2EncoderOptions#tileOriginY()}.
         *
         * @param x the left edge of the first tile column
         * @param y the top edge of the first tile row
         * @return this builder
         */
        public Builder tileOrigin(long x, long y) {
            this.tileOriginX = x;
            this.tileOriginY = y;
            return this;
        }

        /**
         * Sets the progression order; see {@link Jp2EncoderOptions#progressionOrder()}.
         *
         * @param order the value
         * @return this builder
         */
        public Builder progressionOrder(Jp2ProgressionOrder order) {
            this.progression = Objects.requireNonNull(order, "order");
            return this;
        }

        /**
         * Sets the progression changes; see {@link Jp2EncoderOptions#progressionChanges()}.
         *
         * @param changes the value, copied
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder progressionChanges(List<Jp2ProgressionChange> changes) {
            this.progressionChanges = List.copyOf(changes);
            return this;
        }

        /**
         * Sets how the packets of each tile are divided into tile-parts; see
         * {@link Jp2EncoderOptions#tilePartDivision()}.
         *
         * @param division the value
         * @return this builder
         */
        public Builder tilePartDivision(Jp2TilePartDivision division) {
            this.tilePartDivision = Objects.requireNonNull(division, "division");
            return this;
        }

        /**
         * Writes one tile-part per progression; see {@link Jp2EncoderOptions#tilePartDivision()}.
         *
         * @return this builder
         */
        public Builder noTilePartDivision() {
            this.tilePartDivision = null;
            return this;
        }

        /**
         * Sets whether {@code TLM} segments are written; see {@link Jp2EncoderOptions#tlmMarkers()}.
         *
         * @param tlmMarkers the value
         * @return this builder
         */
        public Builder tlmMarkers(boolean tlmMarkers) {
            this.tlmMarkers = tlmMarkers;
            return this;
        }

        /**
         * Sets whether {@code PLT} segments are written; see {@link Jp2EncoderOptions#pltMarkers()}.
         *
         * @param pltMarkers the value
         * @return this builder
         */
        public Builder pltMarkers(boolean pltMarkers) {
            this.pltMarkers = pltMarkers;
            return this;
        }

        /**
         * Sets the region of interest; see {@link Jp2EncoderOptions#regionOfInterest()}.
         *
         * @param region the value
         * @return this builder
         */
        public Builder regionOfInterest(Jp2RegionOfInterest region) {
            this.regionOfInterest = Objects.requireNonNull(region, "region");
            return this;
        }

        /**
         * Codes no region ahead of the rest; see {@link Jp2EncoderOptions#regionOfInterest()}.
         *
         * @return this builder
         */
        public Builder noRegionOfInterest() {
            this.regionOfInterest = null;
            return this;
        }

        /**
         * Sets the number of guard bits; see {@link Jp2EncoderOptions#guardBits()}.
         *
         * @param guardBits the value
         * @return this builder
         */
        public Builder guardBits(int guardBits) {
            this.guardBits = guardBits;
            return this;
        }

        /**
         * Sets the largest codestream; see {@link Jp2EncoderOptions#maxCodestreamBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder maxCodestreamBytes(long bytes) {
            this.maxCodestreamBytes = bytes;
            this.codestreamLimited = true;
            return this;
        }

        /**
         * Removes the codestream size limit; see {@link Jp2EncoderOptions#maxCodestreamBytes()}.
         *
         * @return this builder
         */
        public Builder noMaxCodestreamBytes() {
            this.maxCodestreamBytes = 0;
            this.codestreamLimited = false;
            return this;
        }

        /**
         * Sets the largest size of one component's packets in a tile; see
         * {@link Jp2EncoderOptions#maxComponentBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder maxComponentBytes(long bytes) {
            this.maxComponentBytes = bytes;
            this.componentLimited = true;
            return this;
        }

        /**
         * Removes the per-component size limit; see {@link Jp2EncoderOptions#maxComponentBytes()}.
         *
         * @return this builder
         */
        public Builder noMaxComponentBytes() {
            this.maxComponentBytes = 0;
            this.componentLimited = false;
            return this;
        }

        /**
         * Sets the codestream profile; see {@link Jp2EncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(Jp2Profile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Writes no codestream profile; see {@link Jp2EncoderOptions#profile()}.
         *
         * @return this builder
         */
        public Builder noProfile() {
            this.profile = null;
            return this;
        }

        /**
         * Sets the IMF main and sub levels; see {@link Jp2EncoderOptions#imfMainLevel()} and
         * {@link Jp2EncoderOptions#imfSubLevel()}.
         *
         * @param mainLevel the main level
         * @param subLevel the sub level
         * @return this builder
         */
        public Builder imfLevels(int mainLevel, int subLevel) {
            this.imfMainLevel = mainLevel;
            this.imfSubLevel = subLevel;
            return this;
        }

        /**
         * Sets the code-block style options; see {@link Jp2EncoderOptions#codeBlockStyles()}.
         *
         * @param styles the value, copied
         * @return this builder
         */
        public Builder codeBlockStyles(Set<Jp2CodeBlockStyle> styles) {
            this.codeBlockStyles = styles.isEmpty() ? EnumSet.noneOf(Jp2CodeBlockStyle.class) : EnumSet.copyOf(styles);
            return this;
        }

        /**
         * Sets whether code-blocks are coded with the high-throughput block coder; see
         * {@link Jp2EncoderOptions#highThroughput()}.
         *
         * @param highThroughput the value
         * @return this builder
         */
        public Builder highThroughput(boolean highThroughput) {
            this.highThroughput = highThroughput;
            return this;
        }

        /**
         * Sets whether {@code SOP} segments are written; see {@link Jp2EncoderOptions#sopMarkers()}.
         *
         * @param sopMarkers the value
         * @return this builder
         */
        public Builder sopMarkers(boolean sopMarkers) {
            this.sopMarkers = sopMarkers;
            return this;
        }

        /**
         * Sets whether {@code EPH} markers are written; see {@link Jp2EncoderOptions#ephMarkers()}.
         *
         * @param ephMarkers the value
         * @return this builder
         */
        public Builder ephMarkers(boolean ephMarkers) {
            this.ephMarkers = ephMarkers;
            return this;
        }

        /**
         * Sets whether the first three channels go through the component transform; see
         * {@link Jp2EncoderOptions#colorTransform()}.
         *
         * @param colorTransform the value
         * @return this builder
         */
        public Builder colorTransform(boolean colorTransform) {
            this.colorTransform = colorTransform;
            return this;
        }

        /**
         * Sets the color profile to embed; see {@link Jp2EncoderOptions#colorProfile()}.
         *
         * @param profile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile profile) {
            this.colorProfile = profile;
            return this;
        }

        /**
         * Sets the codestream comment; see {@link Jp2EncoderOptions#comment()}.
         *
         * @param comment the value, or {@code null} for none
         * @return this builder
         */
        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        /**
         * Sets the EXIF metadata; see {@link Jp2EncoderOptions#exif()}.
         *
         * @param exif the value
         * @return this builder
         */
        public Builder exif(ExifData exif) {
            this.exif = Objects.requireNonNull(exif, "exif");
            return this;
        }

        /**
         * Writes no EXIF metadata of the options; see {@link Jp2EncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link Jp2EncoderOptions#xmp()}.
         *
         * @param xmp the value
         * @return this builder
         */
        public Builder xmp(XmpPacket xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp");
            return this;
        }

        /**
         * Writes no XMP packet of the options; see {@link Jp2EncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Sets the IPTC-IIM metadata; see {@link Jp2EncoderOptions#iptc()}.
         *
         * @param iptc the value
         * @return this builder
         */
        public Builder iptc(IptcData iptc) {
            this.iptc = Objects.requireNonNull(iptc, "iptc");
            return this;
        }

        /**
         * Writes no IPTC-IIM metadata; see {@link Jp2EncoderOptions#iptc()}.
         *
         * @return this builder
         */
        public Builder noIptc() {
            this.iptc = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if there are no quality layers or more than 100, a layer
         * target is negative or not finite, the decomposition levels are not in [0, 32], a tile
         * dimension is not positive, a code-block dimension is not a power of two in [4, 1024] or a
         * code-block holds more than 4096 samples, a precinct dimension is not a power of two in
         * [2, 32768] or the width and height lists differ in length or hold more than 33 sizes, an
         * origin is negative or above 4294967294, there are more than 255 progression changes, the
         * guard bits are not in [0, 7], a size limit is not positive, the IMF levels are out of range, the
         * comment is not Latin-1 text of at most 65531 characters, the high-throughput block coder
         * is combined with a code-block style other than
         * {@link Jp2CodeBlockStyle#VERTICALLY_CAUSAL} or with a region of interest, or a setting
         * breaks a rule of the profile
         */
        public Jp2EncoderOptions build() {
            var layers = ratios != null ? ratios : psnrTargets;
            Checks.inRange("quality layer count", layers.length, 1, MAX_LAYERS);
            for (var value : layers) {
                if (!(value >= 0) || Double.isInfinite(value)) {
                    throw new IllegalArgumentException("layer target " + value + " is not a finite non-negative "
                            + "number");
                }
            }
            Checks.inRange("decomposition levels", decompositionLevels, 0, 32);
            if (tilesSet) {
                Checks.positive("tile width", tileWidth);
                Checks.positive("tile height", tileHeight);
            }
            if (!powerOfTwo(codeBlockWidth, 4, 1024) || !powerOfTwo(codeBlockHeight, 4, 1024)
                    || codeBlockWidth * codeBlockHeight > 4096) {
                throw new IllegalArgumentException("code-block size " + codeBlockWidth + "x" + codeBlockHeight
                        + " is not two powers of two in [4, 1024] with at most 4096 samples");
            }
            if (precinctWidths != null) {
                if (precinctWidths.length != precinctHeights.length || precinctWidths.length == 0
                        || precinctWidths.length > 33) {
                    throw new IllegalArgumentException("the precinct width and height lists must hold the same "
                            + "number of sizes, from 1 to 33");
                }
                for (var i = 0; i < precinctWidths.length; i++) {
                    if (!powerOfTwo(precinctWidths[i], 2, 1 << 15) || !powerOfTwo(precinctHeights[i], 2, 1 << 15)) {
                        throw new IllegalArgumentException("precinct size " + precinctWidths[i] + "x"
                                + precinctHeights[i] + " is not two powers of two in [2, 32768]");
                    }
                }
            }
            Checks.inRange("image origin x", imageOriginX, 0, MAX_POSITION);
            Checks.inRange("image origin y", imageOriginY, 0, MAX_POSITION);
            Checks.inRange("tile origin x", tileOriginX, 0, MAX_POSITION);
            Checks.inRange("tile origin y", tileOriginY, 0, MAX_POSITION);
            Checks.inRange("progression change count", progressionChanges.size(), 0, MAX_PROGRESSION_CHANGES);
            Checks.inRange("guard bits", guardBits, 0, 7);
            if (codestreamLimited) {
                Checks.positive("maximum codestream bytes", maxCodestreamBytes);
            }
            if (componentLimited) {
                Checks.positive("maximum component bytes", maxComponentBytes);
            }
            Checks.inRange("IMF main level", imfMainLevel, 0, IMF_MAX_SUB_LEVEL.length - 1);
            Checks.inRange("IMF sub level", imfSubLevel, 0, IMF_MAX_SUB_LEVEL[imfMainLevel]);
            if (highThroughput) {
                for (var style : codeBlockStyles) {
                    if (style != Jp2CodeBlockStyle.VERTICALLY_CAUSAL) {
                        throw new IllegalArgumentException("the high-throughput block coder does not allow the "
                                + style + " code-block style");
                    }
                }
                if (regionOfInterest != null) {
                    throw new IllegalArgumentException("the high-throughput block coder does not code a region of "
                            + "interest");
                }
            }
            if (comment != null && (comment.length() > MAX_COMMENT
                    || !StandardCharsets.ISO_8859_1.newEncoder().canEncode(comment))) {
                throw new IllegalArgumentException("comment is not Latin-1 text of at most " + MAX_COMMENT
                        + " characters");
            }
            if (profile != null) {
                checkProfile();
            }
            return new Jp2EncoderOptions(this);
        }

        /**
         * Checks the settings against the rules of the profile that do not depend on the image.
         *
         * @throws IllegalArgumentException if a setting breaks a rule
         */
        private void checkProfile() {
            var name = profile.name();
            var levels = decompositionLevels;
            require(!tilesSet || !profile.isCinema(), name + " codes one tile");
            require(imageOriginX == 0 && imageOriginY == 0 && tileOriginX == 0 && tileOriginY == 0,
                    name + " places the image and the tiles at the origin");
            require(codeBlockWidth == 32 && codeBlockHeight == 32, name + " needs 32x32 code-blocks");
            require(codeBlockStyles.isEmpty(), name + " allows no code-block style");
            require(regionOfInterest == null, name + " allows no region of interest");
            require(!highThroughput, name + " needs the block coder of ISO/IEC 15444-1");
            require((ratios != null ? ratios.length : psnrTargets.length) == 1, name + " needs one quality layer");
            require(progression == Jp2ProgressionOrder.CPRL, name + " needs the CPRL progression");
            var precincts = profilePrecincts(levels);
            var resolutions = levels + 1;
            require(precinctWidths != null && sameSizes(precinctWidths, precincts, resolutions)
                    && sameSizes(precinctHeights, precincts, resolutions),
                    name + " needs 256x256 precincts, 128x128 at the lowest resolution");
            if (profile.isCinema()) {
                require(!reversible, name + " needs the 9-7 wavelet");
                require(codestreamLimited && maxCodestreamBytes <= CINEMA_24_CODESTREAM,
                        name + " needs a codestream size limit of at most " + CINEMA_24_CODESTREAM + " bytes");
                require(componentLimited && maxComponentBytes <= CINEMA_24_COMPONENT,
                        name + " needs a component size limit of at most " + CINEMA_24_COMPONENT + " bytes");
                require(tlmMarkers, name + " needs TLM markers");
                require(tilePartDivision == Jp2TilePartDivision.COMPONENT, name + " needs a tile-part per component");
                if (profile == Jp2Profile.CINEMA_2K) {
                    require(levels <= 5, name + " allows at most 5 decomposition levels");
                    require(progressionChanges.isEmpty(), name + " allows no progression change");
                    require(guardBits == 1, name + " needs one guard bit");
                } else {
                    require(levels >= 1 && levels <= 6, name + " needs from 1 to 6 decomposition levels");
                    require(progressionChanges.equals(List.of(
                                    new Jp2ProgressionChange(0, 0, 1, levels, 3, Jp2ProgressionOrder.CPRL),
                                    new Jp2ProgressionChange(levels, 0, 1, levels + 1, 3,
                                            Jp2ProgressionOrder.CPRL))),
                            name + " needs the progressions of the 2K image, then of the highest resolution");
                }
            } else {
                var reversibleProfile = profile.isReversibleImf();
                require(reversible == reversibleProfile, name + " needs the "
                        + (reversibleProfile ? "5-3" : "9-7") + " wavelet");
                require(progressionChanges.isEmpty(), name + " allows no progression change");
                var maxLevels = switch (profile) {
                    case IMF_2K, IMF_2K_REVERSIBLE -> 5;
                    case IMF_4K, IMF_4K_REVERSIBLE -> 6;
                    default -> 7;
                };
                require(levels >= 1 && levels <= maxLevels, name + " needs from 1 to " + maxLevels
                        + " decomposition levels");
            }
        }

        /**
         * Returns whether a list of precinct sizes gives every resolution the size another list gives.
         *
         * @param sizes the sizes to test
         * @param expected the reference sizes
         * @param resolutions the number of resolutions
         * @return whether the effective sizes match
         */
        private static boolean sameSizes(int[] sizes, int[] expected, int resolutions) {
            for (var p = 0; p < resolutions; p++) {
                if (effective(sizes, p) != effective(expected, p)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns the precinct size a list gives the resolution {@code p} levels below the highest.
         *
         * @param sizes the sizes from the highest resolution down
         * @param p the number of levels below the highest resolution
         * @return the size
         */
        private static int effective(int[] sizes, int p) {
            return p < sizes.length ? sizes[p] : sizes[sizes.length - 1] >> (p - sizes.length + 1);
        }

        /**
         * Throws when a rule is broken.
         *
         * @param rule whether the rule holds
         * @param message the description of the rule
         * @throws IllegalArgumentException if {@code rule} is {@code false}
         */
        private static void require(boolean rule, String message) {
            if (!rule) {
                throw new IllegalArgumentException(message);
            }
        }

        /**
         * Returns whether a value is a power of two within a range.
         *
         * @param value the number to test
         * @param min the smallest value allowed
         * @param max the largest value allowed
         * @return whether it is
         */
        private static boolean powerOfTwo(int value, int min, int max) {
            return value >= min && value <= max && Integer.bitCount(value) == 1;
        }
    }
}
