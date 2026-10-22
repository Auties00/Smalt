package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Converts pixel values from one {@link ColorProfile} to another, or through a chain of profiles.
 *
 * <p>A transform links the source profile, used in the input direction, to the target profile,
 * used in the output direction, through the profile connection space, with the rendering intent,
 * black point compensation and tone mapping of its {@link ColorTransformOptions}. It converts
 * interleaved samples of 8 or 16 bits ({@link #transform(byte[], int, int, byte[], int, int, int)},
 * {@link #transform(short[], int, int, short[], int, int, int)}); {@code ImageOps.convertColor}
 * applies it to pictures.
 *
 * <p>A chain ({@link #create(List, ColorTransformOptions)}) applies its profiles in order, with the
 * options' intent and black point compensation at every link. The first profile is used in the input
 * direction; every later profile is used in the output direction when the chain is in the connection
 * space at that point (after an input or abstract profile), otherwise in the input direction (after
 * an output profile or a device link that ends in device values). A device link
 * ({@link ProfileClass#DEVICE_LINK}) converts between its two device spaces wherever it stands, and an
 * abstract profile ({@link ProfileClass#ABSTRACT}) adjusts colors within the connection space. A named
 * color profile ({@link ProfileClass#NAMED_COLOR}) may only start a chain: its transform takes one
 * sample per pixel, the index of a color in {@link ColorProfile#namedColors()}, and converts the
 * color's connection space values through the rest of the chain, or gives its device coordinates
 * when the profile is alone. A chain of one input, display, output or color space profile, or one
 * that ends with an abstract profile, ends in the connection space, as XYZ or L*a*b* samples in the
 * ICC version 4 encoding.
 *
 * <p>8-bit transforms reproduce Little CMS 2.17 with its default flags: matrix/TRC to matrix/TRC
 * RGB conversions use the same 1.14 fixed-point tables and give the same values, and the other
 * conversions are sampled into the same lookup tables. 16-bit matrix/TRC conversions are computed
 * directly in floating point, which is more accurate than Little CMS's 16-bit lookup tables, and 16-bit RGB to RGB
 * lookup-table conversions use prelinearization curves when they make the table more accurate.
 * Floating-point {@code D2Bn} and {@code B2Dn} tables take precedence over the {@code A2Bn} and
 * {@code B2An} tables of the same intent.
 *
 * <p>High-dynamic-range profiles ({@link ColorProfile#isHighDynamicRange()}) are converted through
 * display light in candelas per square meter, PQ by the SMPTE ST 2084 EOTF and HLG by the ITU-R
 * BT.2100 EOTF of the 1000 candelas per square meter reference display (system gamma 1.2,
 * luminance from the profile's primaries), never through their ICC tone curves:
 * <ul>
 *   <li>from a high-dynamic-range source to a standard-dynamic-range target, luminance is
 *       compressed to {@link ColorTransformOptions#referenceWhite()} as {@link ToneMapping}
 *       describes, and the target white is that reference white;</li>
 *   <li>from a standard-dynamic-range source to a high-dynamic-range target, the source white is
 *       placed at the reference white (ITU-R BT.2408: 203 candelas per square meter by default,
 *       HLG signal 0.75) and colors are converted to the target primaries with the rendering
 *       intent;</li>
 *   <li>between two high-dynamic-range profiles (PQ to HLG and back, as ITU-R BT.2390 section 7
 *       converts them through display light), luminance is kept and colors are converted between
 *       the primaries with a 3x3 matrix, adapting the white with the Bradford transform and
 *       clipping colors outside the target gamut; a source whose peak exceeds the target limit is
 *       compressed to that limit as {@link ToneMapping} describes.</li>
 * </ul>
 * The limit of an HLG target is the 1000 candelas per square meter of its reference display; that
 * of a PQ target is the peak of its mastering display or content light level when its
 * {@link ColorProfile#colorInfo()} describes one, else the 10000 of the PQ signal range. A source
 * peak is the one {@link ColorProfile#of(com.github.auties00.smalt.image.ColorInfo)} documents,
 * and the reference white for a standard-dynamic-range source. A transform between two profiles
 * with the same transfer characteristics and primaries whose source peak is within the target
 * limit leaves values unchanged.
 *
 * <p>Creating a transform reads and links the tags it needs, and the tables of each sample depth are
 * built on their first use; both costs grow with the size of the profiles' lookup tables, so a
 * transform is meant to be reused for every picture with the same profiles, and
 * {@link #create} may return the same instance for equal arguments. Instances are immutable and
 * thread-safe.
 *
 * @implNote This implementation ports the transform logic of Little CMS 2.17: profile reading ({@code cmsio1.c}),
 * intent linking, device links, abstract profiles and black point compensation ({@code cmscnvrt.c},
 * {@code cmssamp.c}), tone curves and segmented curves ({@code cmsgamma.c}), interpolation ({@code cmsintrp.c}),
 * named colors ({@code cmsnamed.c}) and the matrix-shaper, curve-joining and resampling optimizations, including the
 * prelinearization of 8-bit RGB lookup-table transforms ({@code cmsopt.c}).
 */
public final class ColorTransform {
    /**
     * The number of recently created transforms kept for reuse.
     */
    private static final int CACHE_SIZE = 16;

    /**
     * The recently created transforms, least recently used first; guarded by itself.
     */
    private static final Map<List<Object>, ColorTransform> CACHE = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, ColorTransform> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * The largest number of profiles of a chain.
     */
    private static final int MAX_PROFILES = 255;

    /**
     * The profiles of the chain, at least one.
     */
    private final List<ColorProfile> profiles;

    /**
     * The source profile.
     */
    private final ColorProfile source;

    /**
     * The target profile.
     */
    private final ColorProfile target;

    /**
     * The options.
     */
    private final ColorTransformOptions options;

    /**
     * The linked and simplified pipeline, or {@code null} when a profile is high-dynamic-range.
     */
    private final Pipeline pipeline;

    /**
     * The ICC transform between the standard-dynamic-range profile and the linear light (for a
     * high-dynamic-range source) or gamma-2 light (for a high-dynamic-range target) of the
     * high-dynamic-range profile's primaries, or {@code null} when no profile or both profiles are
     * high-dynamic-range.
     */
    private final ColorTransform inner;

    /**
     * The color space signature of the source samples.
     */
    private final int entrySpace;

    /**
     * The color space signature of the target samples.
     */
    private final int exitSpace;

    /**
     * Whether the chain starts with a named color profile.
     */
    private final boolean named;

    /**
     * The number of source channels.
     */
    private final int inputs;

    /**
     * The number of target channels.
     */
    private final int outputs;

    /**
     * The 8-bit engine, built on first use.
     */
    private volatile ColorEngine engine8;

    /**
     * The 16-bit engine, built on first use.
     */
    private volatile ColorEngine engine16;

    /**
     * Guards the construction of the engines.
     */
    private final Object lock = new Object();

    /**
     * Links a chain of profiles.
     *
     * @param profiles the profiles, from one to {@value #MAX_PROFILES}
     * @param options the rendering intent, black point compensation and tone mapping
     * @throws IllegalArgumentException if a high-dynamic-range profile is in a chain that is not a pair
     */
    private ColorTransform(List<ColorProfile> profiles, ColorTransformOptions options) {
        this.profiles = profiles;
        this.source = profiles.getFirst();
        this.target = profiles.getLast();
        this.options = options;
        var hdr = source.hdr();
        var into = target.hdr();
        if (profiles.size() == 2 && (hdr != null || into != null)) {
            this.named = false;
            this.pipeline = null;
            if (hdr != null && into != null) {
                this.inner = null;
                this.inputs = 3;
                this.outputs = 3;
                this.entrySpace = source.icc().colorSpace;
                this.exitSpace = target.icc().colorSpace;
            } else if (into != null) {
                var gamma2 = ColorProfile.parse(IccWriter.rgb("Gamma 2", into.red(), into.green(), into.blue(),
                        into.white(), IccWriter.Curve.gamma(2.0)));
                this.inner = create(List.of(source, gamma2), options);
                this.inputs = inner.inputs;
                this.outputs = target.channels();
                this.entrySpace = inner.entrySpace;
                this.exitSpace = target.icc().colorSpace;
            } else {
                var linear = ColorProfile.parse(IccWriter.rgb("Linear", hdr.red(), hdr.green(), hdr.blue(), hdr.white(),
                        IccWriter.Curve.gamma(1.0)));
                this.inner = create(List.of(linear, target), options);
                this.inputs = source.channels();
                this.outputs = inner.outputs;
                this.entrySpace = source.icc().colorSpace;
                this.exitSpace = inner.exitSpace;
            }
            return;
        }
        this.inner = null;
        var intent = options.intent().code();
        var bpc = options.blackPointCompensation();
        var nodes = new ArrayList<Linker.Node>(profiles.size());
        var parsed = new ArrayList<IccReader>(profiles.size());
        for (var profile : profiles) {
            if (profile.hdr() != null) {
                throw new IllegalArgumentException("a high-dynamic-range profile can only be converted to or from one "
                        + "other profile");
            }
            nodes.add(Linker.Node.of(profile.icc(), intent, bpc));
            parsed.add(profile.icc());
        }
        var linked = Linker.link(nodes);
        this.named = source.icc().deviceClass == IccReader.signature("nmcl");
        if (!named) {
            linked.simplify();
        }
        this.pipeline = linked;
        this.inputs = linked.inputs();
        this.outputs = linked.outputs();
        this.entrySpace = source.icc().colorSpace;
        this.exitSpace = Linker.exitSpace(parsed);
    }

    /**
     * Creates a transform with the {@linkplain ColorTransformOptions#defaults() default options}.
     *
     * @param source the profile the input values are in
     * @param target the profile to convert them to
     * @return the transform
     * @throws MediaException.Malformed if a profile lacks a tag its role needs or a tag is damaged
     * @throws MediaException.Unsupported if a profile is a device link, abstract or named color profile, which only
     * {@link #create(List, ColorTransformOptions)} chains
     * @see #create(ColorProfile, ColorProfile, ColorTransformOptions)
     */
    public static ColorTransform create(ColorProfile source, ColorProfile target) {
        return create(source, target, ColorTransformOptions.defaults());
    }

    /**
     * Creates a transform.
     *
     * <p>Both profiles convert between device values and the connection space: input, display, output or color
     * space profiles. The transform is the chain of the two ({@link #create(List, ColorTransformOptions)}), from the
     * source's device values to the target's.
     *
     * @apiNote Device links, abstract and named color profiles are rejected here, so that a profile embedded in a
     * file, applied to its pixels with this method, is never one that reads them as something else; chain them with
     * {@link #create(List, ColorTransformOptions)}.
     * @param source the profile the input values are in
     * @param target the profile to convert them to
     * @param options the rendering intent, black point compensation and tone mapping
     * @return the transform
     * @throws MediaException.Malformed if a profile lacks a tag its role needs or a tag is damaged
     * @throws MediaException.Unsupported if a profile is a device link, abstract or named color profile, which only
     * {@link #create(List, ColorTransformOptions)} chains
     */
    public static ColorTransform create(ColorProfile source, ColorProfile target, ColorTransformOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireDeviceProfile(source);
        requireDeviceProfile(target);
        return create(List.of(source, target), options);
    }

    /**
     * Rejects the profile classes that do not convert between device values and the connection space.
     *
     * @param profile the profile of a source-target pair
     * @throws MediaException.Unsupported if the profile is a device link, abstract or named color profile
     */
    private static void requireDeviceProfile(ColorProfile profile) {
        switch (profile.deviceClass()) {
            case DEVICE_LINK, ABSTRACT, NAMED_COLOR -> throw new MediaException.Unsupported("icc", "ICC profile class "
                    + IccReader.name(profile.icc().deviceClass) + " cannot convert between device values and the "
                    + "connection space; chain it with ColorTransform.create(List)");
            default -> {
            }
        }
    }

    /**
     * Creates a transform through a chain of profiles with the
     * {@linkplain ColorTransformOptions#defaults() default options}.
     *
     * @param profiles the profiles in the order they apply, from 1 to 255
     * @return the transform
     * @throws NullPointerException if {@code profiles} is {@code null} or holds a {@code null} element
     * @throws IllegalArgumentException if the list is empty or longer than 255, or holds a high-dynamic-range profile
     * and is not a pair
     * @throws MediaException.Malformed if a profile lacks a tag its role needs or a tag is damaged
     * @throws MediaException.Unsupported if consecutive profiles do not connect, or a named color profile is not
     * the first
     * @see #create(List, ColorTransformOptions)
     */
    public static ColorTransform create(List<ColorProfile> profiles) {
        return create(profiles, ColorTransformOptions.defaults());
    }

    /**
     * Creates a transform through a chain of profiles.
     *
     * <p>The profiles apply in order, as the class documentation describes. Consecutive profiles connect when the
     * color space one ends in is the one the next starts from; CMYK and four-color spaces connect, and XYZ and
     * L*a*b* connect through a conversion. A pair that holds a high-dynamic-range profile converts as
     * {@link #create(ColorProfile, ColorProfile, ColorTransformOptions)} describes; longer chains and chains of one
     * cannot hold one.
     *
     * @apiNote To apply a device link, pass it alone; to adjust colors with an abstract profile, put it between the
     * source and the target, for example {@code List.of(ColorProfile.srgb(), effect, ColorProfile.displayP3())}.
     * @param profiles the profiles in the order they apply, from 1 to 255
     * @param options the rendering intent, black point compensation and tone mapping, applied at every link
     * @return the transform
     * @throws NullPointerException if {@code profiles} is {@code null} or holds a {@code null} element, or
     * {@code options} is {@code null}
     * @throws IllegalArgumentException if the list is empty or longer than 255, or holds a high-dynamic-range profile
     * and is not a pair
     * @throws MediaException.Malformed if a profile lacks a tag its role needs or a tag is damaged
     * @throws MediaException.Unsupported if consecutive profiles do not connect, or a named color profile is not
     * the first
     */
    public static ColorTransform create(List<ColorProfile> profiles, ColorTransformOptions options) {
        var chain = List.copyOf(profiles);
        Objects.requireNonNull(options, "options");
        if (chain.isEmpty() || chain.size() > MAX_PROFILES) {
            throw new IllegalArgumentException("a chain needs 1 to " + MAX_PROFILES + " profiles, got " + chain.size());
        }
        var key = List.<Object>of(chain, options);
        synchronized (CACHE) {
            var cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        var transform = new ColorTransform(chain, options);
        synchronized (CACHE) {
            var cached = CACHE.putIfAbsent(key, transform);
            return cached != null ? cached : transform;
        }
    }

    /**
     * Returns the source profile, the first of the chain.
     *
     * @return the profile
     */
    public ColorProfile source() {
        return source;
    }

    /**
     * Returns the target profile, the last of the chain.
     *
     * @return the profile
     */
    public ColorProfile target() {
        return target;
    }

    /**
     * {@return the profiles of the chain in the order they apply, the source first and the target last; an
     * unmodifiable list}
     */
    public List<ColorProfile> profiles() {
        return profiles;
    }

    /**
     * {@return the options this transform was created with}
     */
    public ColorTransformOptions options() {
        return options;
    }

    /**
     * Returns the number of channels each source pixel provides.
     *
     * @return the source channel count, 1 when the source is a named color profile
     */
    public int inputChannels() {
        return inputs;
    }

    /**
     * Returns the number of channels each target pixel receives.
     *
     * @return the target channel count
     */
    public int outputChannels() {
        return outputs;
    }

    /**
     * Converts interleaved 8-bit pixels.
     *
     * <p>Pixel {@code i} reads {@link #inputChannels()} samples from
     * {@code src[srcOffset + i * srcPixelStride]} on and writes {@link #outputChannels()} samples
     * to {@code dst[dstOffset + i * dstPixelStride]} on, leaving any further samples of the
     * destination pixel, such as alpha, unchanged. CMYK samples are ink amounts: {@code 0} is no ink. The
     * source and destination may be the same array at the same offset when the strides are equal
     * and the target has no more channels than the source. When the source is a named color profile,
     * the one source sample is a color index, {@code 0} to {@code 255}; an index past the last color
     * converts as if the color had all-zero connection space values or device coordinates.
     *
     * @param src the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcPixelStride the samples between consecutive source pixels, at least {@link #inputChannels()}
     * @param dst the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstPixelStride the samples between consecutive destination pixels, at least {@link #outputChannels()}
     * @param pixels the number of pixels
     * @throws IllegalArgumentException if a stride is too small or {@code pixels} is negative
     * @throws IndexOutOfBoundsException if the pixels do not fit in the arrays
     */
    public void transform(byte[] src, int srcOffset, int srcPixelStride, byte[] dst, int dstOffset,
                          int dstPixelStride, int pixels) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        checkBounds(src.length, srcOffset, srcPixelStride, inputs, dst.length, dstOffset, dstPixelStride, outputs,
                pixels);
        if (pixels > 0) {
            engine8().apply(src, srcOffset, srcPixelStride, dst, dstOffset, dstPixelStride, pixels);
        }
    }

    /**
     * Converts interleaved 16-bit pixels, each sample an unsigned value held in a {@code short}.
     *
     * <p>The layout is that of {@link #transform(byte[], int, int, byte[], int, int, int)}; a named color
     * index spans {@code 0} to {@code 65535}.
     *
     * @param src the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcPixelStride the samples between consecutive source pixels, at least {@link #inputChannels()}
     * @param dst the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstPixelStride the samples between consecutive destination pixels, at least {@link #outputChannels()}
     * @param pixels the number of pixels
     * @throws IllegalArgumentException if a stride is too small or {@code pixels} is negative
     * @throws IndexOutOfBoundsException if the pixels do not fit in the arrays
     */
    public void transform(short[] src, int srcOffset, int srcPixelStride, short[] dst, int dstOffset,
                          int dstPixelStride, int pixels) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        checkBounds(src.length, srcOffset, srcPixelStride, inputs, dst.length, dstOffset, dstPixelStride, outputs,
                pixels);
        if (pixels > 0) {
            engine16().apply(src, srcOffset, srcPixelStride, dst, dstOffset, dstPixelStride, pixels);
        }
    }

    /**
     * Checks the layout of a conversion.
     *
     * @param srcLength the source array length
     * @param srcOffset the first source index
     * @param srcStride the source stride
     * @param inputs the source channels
     * @param dstLength the destination array length
     * @param dstOffset the first destination index
     * @param dstStride the destination stride
     * @param outputs the destination channels
     * @param pixels the pixel count
     */
    private static void checkBounds(int srcLength, int srcOffset, int srcStride, int inputs, int dstLength,
                                    int dstOffset, int dstStride, int outputs, int pixels) {
        if (pixels < 0) {
            throw new IllegalArgumentException("negative pixel count " + pixels);
        }
        if (srcStride < inputs || dstStride < outputs) {
            throw new IllegalArgumentException("stride smaller than the channel count");
        }
        if (pixels == 0) {
            return;
        }
        var srcEnd = srcOffset + (long) (pixels - 1) * srcStride + inputs;
        var dstEnd = dstOffset + (long) (pixels - 1) * dstStride + outputs;
        if (srcOffset < 0 || srcEnd > srcLength || dstOffset < 0 || dstEnd > dstLength) {
            throw new IndexOutOfBoundsException("pixels do not fit in the arrays");
        }
    }

    /**
     * {@return the linked and simplified pipeline, unsimplified when the source is a named color profile, or
     * {@code null} for a transform with a high-dynamic-range profile}
     */
    Pipeline pipeline() {
        return pipeline;
    }

    /**
     * Returns the 8-bit engine, building it on first use.
     *
     * @return the engine
     */
    ColorEngine engine8() {
        var engine = engine8;
        if (engine == null) {
            synchronized (lock) {
                engine = engine8;
                if (engine == null) {
                    engine = pipeline == null ? hdrEngine() : build8(pipeline);
                    engine8 = engine;
                }
            }
        }
        return engine;
    }

    /**
     * Returns the 16-bit engine, building it on first use.
     *
     * @return the engine
     */
    ColorEngine engine16() {
        var engine = engine16;
        if (engine == null) {
            synchronized (lock) {
                engine = engine16;
                if (engine == null) {
                    engine = pipeline == null ? hdrEngine() : build16(pipeline);
                    engine16 = engine;
                }
            }
        }
        return engine;
    }

    /**
     * Builds the engine of a transform with a high-dynamic-range profile, shared by both depths.
     *
     * @return the engine
     */
    private ColorEngine hdrEngine() {
        var built = engine8 != null ? engine8 : engine16;
        if (built != null) {
            return built;
        }
        var hdr = source.hdr();
        var into = target.hdr();
        if (into != null) {
            if (hdr != null) {
                return HdrTargetEngine.isIdentity(hdr, into) ? new IdentityEngine(3)
                        : HdrTargetEngine.fromHigh(hdr, into, options);
            }
            return HdrTargetEngine.fromStandard(inner.engine16(), inputs, inner.named, into, options);
        }
        return new HdrEngine(hdr, inner.engine16(), outputs, options);
    }

    /**
     * Selects the 8-bit engine of a pipeline, in the order Little CMS tries its optimizations.
     *
     * @param pipeline the linked and simplified pipeline
     * @return the engine
     */
    private ColorEngine build8(Pipeline pipeline) {
        if (named) {
            return engine16 instanceof NamedColorEngine built ? built : new NamedColorEngine(pipeline);
        }
        var stages = pipeline.stages();
        if (stages.isEmpty()) {
            return new IdentityEngine(inputs);
        }
        if (allCurves(stages)) {
            var curves = CurvesEngine.compose(pipeline);
            return CurvesEngine.allLinear(curves) ? new IdentityEngine(inputs) : CurvesEngine.eightBit(curves);
        }
        if (inputs == 3 && outputs == 3) {
            var shaper = matShaper(stages);
            if (shaper != null) {
                return shaper;
            }
        }
        var rgb = IccReader.signature("RGB ");
        if (entrySpace == rgb && exitSpace == rgb) {
            var prelinearized = PrelinEngine.of(pipeline, options.intent() != RenderingIntent.ABSOLUTE_COLORIMETRIC);
            if (prelinearized != null) {
                return prelinearized;
            }
        }
        return clut(pipeline);
    }

    /**
     * Selects the 16-bit engine of a pipeline.
     *
     * @param pipeline the linked and simplified pipeline
     * @return the engine
     */
    private ColorEngine build16(Pipeline pipeline) {
        if (named) {
            return engine8 instanceof NamedColorEngine built ? built : new NamedColorEngine(pipeline);
        }
        var stages = pipeline.stages();
        if (stages.isEmpty()) {
            return new IdentityEngine(inputs);
        }
        if (allCurves(stages)) {
            var curves = CurvesEngine.compose(pipeline);
            return CurvesEngine.allLinear(curves) ? new IdentityEngine(inputs) : CurvesEngine.sixteenBit(curves);
        }
        var shaper = FloatShaperEngine.of(pipeline);
        if (shaper != null) {
            return shaper;
        }
        var rgb = IccReader.signature("RGB ");
        if (entrySpace == rgb && exitSpace == rgb) {
            var prelinearized = PrelinEngine.of(pipeline, options.intent() != RenderingIntent.ABSOLUTE_COLORIMETRIC);
            if (prelinearized != null) {
                var plain = clut(pipeline);
                var probes = probes();
                var expected = new short[probes.length];
                var in = new int[3];
                var out = new int[3];
                for (var p = 0; p < probes.length / 3; p++) {
                    for (var c = 0; c < 3; c++) {
                        in[c] = probes[p * 3 + c] & 0xFFFF;
                    }
                    pipeline.evaluate16(in, out);
                    for (var c = 0; c < 3; c++) {
                        expected[p * 3 + c] = (short) out[c];
                    }
                }
                return error(prelinearized, probes, expected) < error(plain, probes, expected) ? prelinearized : plain;
            }
        }
        return clut(pipeline);
    }

    /**
     * Returns the RGB colors on which {@link #build16} compares its candidate engines: the 9-level lattice and 2048
     * pseudo-random colors.
     *
     * @return the 16-bit samples, three per color
     */
    private static short[] probes() {
        var levels = 9;
        var lattice = levels * levels * levels;
        var probes = new short[(lattice + 2048) * 3];
        for (var index = 0; index < lattice; index++) {
            var rest = index;
            for (var c = 2; c >= 0; c--) {
                probes[index * 3 + c] = (short) ((rest % levels) * 65535 / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0x6A09E667F3BCC909L;
        for (var i = lattice * 3; i < probes.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            probes[i] = (short) (state >>> 48);
        }
        return probes;
    }

    /**
     * Measures how far a 16-bit engine of an RGB to RGB pipeline is from the pipeline itself.
     *
     * @param engine the candidate 16-bit engine
     * @param probes the RGB colors to convert, three samples each
     * @param expected the pipeline's outputs for {@code probes}
     * @return the sum of the squared differences, in 16-bit units
     */
    private static double error(ColorEngine engine, short[] probes, short[] expected) {
        var out = new short[probes.length];
        engine.apply(probes, 0, 3, out, 0, 3, probes.length / 3);
        var sum = 0.0;
        for (var i = 0; i < out.length; i++) {
            double difference = (out[i] & 0xFFFF) - (expected[i] & 0xFFFF);
            sum += difference * difference;
        }
        return sum;
    }

    /**
     * Builds the lookup-table engine of a pipeline.
     *
     * @param pipeline the optimized transform pipeline
     * @return the engine
     */
    private ColorEngine clut(Pipeline pipeline) {
        var fixWhite = options.intent() != RenderingIntent.ABSOLUTE_COLORIMETRIC;
        return new ClutEngine(pipeline, entrySpace, exitSpace, fixWhite);
    }

    /**
     * Returns whether every stage is a curve set.
     *
     * @param stages the stages of the optimized pipeline
     * @return {@code true} if all are curves
     */
    private static boolean allCurves(List<Stage> stages) {
        for (var stage : stages) {
            if (!(stage instanceof Stage.Curves)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the matrix-shaper engine of a pipeline that is curves, a 3x3 matrix (or two, the
     * first without offset) and curves, as Little CMS's {@code OptimizeMatrixShaper} accepts.
     *
     * @param stages the stages of the optimized pipeline
     * @return the engine, or {@code null} when the pipeline has another shape
     */
    private static ColorEngine matShaper(List<Stage> stages) {
        double[] matrix;
        double[] offset;
        Stage.Curves first;
        Stage.Curves last;
        if (stages.size() == 4 && stages.get(0) instanceof Stage.Curves c1 && stages.get(1) instanceof Stage.Matrix m1
                && stages.get(2) instanceof Stage.Matrix m2 && stages.get(3) instanceof Stage.Curves c2) {
            if (!isSquare(m1) || !isSquare(m2) || m1.offset != null) {
                return null;
            }
            matrix = ColorMath.multiply(m2.coefficients, m1.coefficients);
            offset = m2.offset;
            first = c1;
            last = c2;
        } else if (stages.size() == 3 && stages.get(0) instanceof Stage.Curves c1
                && stages.get(1) instanceof Stage.Matrix m && stages.get(2) instanceof Stage.Curves c2) {
            if (!isSquare(m)) {
                return null;
            }
            matrix = m.coefficients.clone();
            offset = m.offset;
            first = c1;
            last = c2;
        } else {
            return null;
        }
        if (first.curves.length != 3 || last.curves.length != 3) {
            return null;
        }
        if (ColorMath.isIdentity(matrix) && offset == null) {
            var joined = new Pipeline(3, 3).append(first).append(last);
            var curves = CurvesEngine.compose(joined);
            return CurvesEngine.allLinear(curves) ? new IdentityEngine(3) : CurvesEngine.eightBit(curves);
        }
        return new MatShaperEngine(first.curves, matrix, offset, last.curves);
    }

    /**
     * Returns whether a matrix stage is 3x3.
     *
     * @param matrix the stage
     * @return {@code true} for three inputs and three outputs
     */
    private static boolean isSquare(Stage.Matrix matrix) {
        return matrix.inputs == 3 && matrix.outputs == 3;
    }

    @Override
    public String toString() {
        var chain = new StringJoiner(" -> ");
        for (var profile : profiles) {
            chain.add(profile.toString());
        }
        return "ColorTransform[" + chain + ", " + options + "]";
    }
}
