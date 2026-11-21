package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts between ambisonic {@link ChannelLayout}s and the layout tags and channel labels of a Core Audio
 * {@code AudioChannelLayout}, as a CAF {@code chan} chunk and a QuickTime {@code chan} atom store it.
 *
 * <p>The values are those of Apple's {@code CoreAudioBaseTypes.h}: the layout tags
 * {@code kAudioChannelLayoutTag_HOA_ACN_SN3D} ({@code 190 << 16}) and {@code kAudioChannelLayoutTag_HOA_ACN_N3D}
 * ({@code 191 << 16}), each ORed with the channel count, which describe every component of an order in ACN order;
 * {@code kAudioChannelLayoutTag_Ambisonic_B_Format} ({@code 107 << 16 | 4}), first-order Furse-Malham B-format
 * {@code W X Y Z}; the channel labels {@code kAudioChannelLabel_HOA_SN3D} ({@code 2 << 16}) and
 * {@code kAudioChannelLabel_HOA_N3D} ({@code 3 << 16}), each ORed with the ACN of the channel's component; and
 * {@code kAudioChannelLabel_Ambisonic_W}, {@code _X}, {@code _Y} and {@code _Z} (200 to 203), the channels of
 * traditional B-format. The generic label {@code kAudioChannelLabel_HOA_ACN} (500) names no component and is read as a
 * channel of unknown role.
 */
public final class CoreAudioAmbisonics {
    /**
     * The layout tag family of every component of an order in ACN order with SN3D normalization.
     */
    public static final int TAG_HOA_ACN_SN3D = 190 << 16;

    /**
     * The layout tag family of every component of an order in ACN order with N3D normalization.
     */
    public static final int TAG_HOA_ACN_N3D = 191 << 16;

    /**
     * The layout tag of first-order Furse-Malham B-format, {@code W X Y Z}.
     */
    public static final int TAG_AMBISONIC_B_FORMAT = 107 << 16 | 4;

    /**
     * The label family of a component with SN3D normalization, ORed with its ACN.
     */
    public static final int LABEL_HOA_SN3D = 2 << 16;

    /**
     * The label family of a component with N3D normalization, ORed with its ACN.
     */
    public static final int LABEL_HOA_N3D = 3 << 16;

    /**
     * The label of the Furse-Malham {@code W} channel; {@code X}, {@code Y} and {@code Z} follow.
     */
    public static final int LABEL_AMBISONIC_W = 200;

    /**
     * The label of a channel of unknown role.
     */
    public static final int LABEL_UNKNOWN = 0xFFFFFFFF;

    /**
     * The ACN of the Furse-Malham channels {@code W X Y Z}, by label offset from {@link #LABEL_AMBISONIC_W}.
     */
    private static final int[] B_FORMAT_ACN = {0, 3, 1, 2};

    /**
     * Prevents instantiation.
     */
    private CoreAudioAmbisonics() {
        throw new AssertionError();
    }

    /**
     * Returns the ambisonic layout of a layout tag.
     *
     * @param tag the layout tag, its low 16 bits the channel count
     * @return the layout, or {@code null} when the tag is not an ambisonic one or names a channel count that is not
     * the square of an order up to {@value Ambisonics#MAX_ORDER}
     */
    public static ChannelLayout fromTag(int tag) {
        if (tag == TAG_AMBISONIC_B_FORMAT) {
            return ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4));
        }
        var family = tag & 0xFFFF0000;
        if (family != TAG_HOA_ACN_SN3D && family != TAG_HOA_ACN_N3D) {
            return null;
        }
        var order = orderOf(tag & 0xFFFF);
        if (order < 0) {
            return null;
        }
        return ChannelLayout.ofAmbisonics(Ambisonics.of(order, family == TAG_HOA_ACN_SN3D
                ? Ambisonics.Normalization.SN3D : Ambisonics.Normalization.N3D));
    }

    /**
     * Returns the layout tag of an ambisonic layout.
     *
     * @param layout the channel layout to describe
     * @return the tag of a layout of only every component of an order in ACN order with SN3D or N3D normalization,
     * or of first-order Furse-Malham B-format; {@code -1} for any other layout
     */
    public static int tagOf(ChannelLayout layout) {
        var ambisonics = layout.ambisonics().orElse(null);
        if (ambisonics == null || ambisonics.channels() != layout.channels()) {
            return -1;
        }
        if (layout.equals(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)))) {
            return TAG_AMBISONIC_B_FORMAT;
        }
        if (!ambisonics.isSequential() || !ambisonics.isFull()) {
            return -1;
        }
        return switch (ambisonics.normalization()) {
            case SN3D -> TAG_HOA_ACN_SN3D | layout.channels();
            case N3D -> TAG_HOA_ACN_N3D | layout.channels();
            case FUMA -> -1;
        };
    }

    /**
     * Returns whether a channel label names an ambisonic component.
     *
     * @param label the channel label
     * @return {@code true} for the HOA labels of a component up to {@value Ambisonics#MAX_COMPONENT} and the
     * B-format labels
     */
    public static boolean isAmbisonic(int label) {
        return componentOf(label) >= 0;
    }

    /**
     * Returns the layout of channels some of which carry ambisonic labels.
     *
     * <p>The channels with an ambisonic label become the ambisonic channels of the layout, provided that every one of
     * them has the same normalization, no two carry the same component and the speakers hold no other channel of
     * unknown position; otherwise the speakers are returned without ambisonics.
     *
     * @param labels the label of every channel, in channel order
     * @param speakers the speaker of every channel, {@link Speaker#UNKNOWN} for the ambisonic labels
     * @return the layout
     */
    public static ChannelLayout withAmbisonics(int[] labels, List<Speaker> speakers) {
        Ambisonics.Normalization normalization = null;
        var components = new ArrayList<Integer>();
        var unknown = 0;
        for (var channel = 0; channel < labels.length; channel++) {
            if (speakers.get(channel) == Speaker.UNKNOWN) {
                unknown++;
            }
            var component = componentOf(labels[channel]);
            if (component < 0) {
                continue;
            }
            var own = normalizationOf(labels[channel]);
            if (normalization != null && normalization != own || components.contains(component)
                    || speakers.get(channel) != Speaker.UNKNOWN) {
                return new ChannelLayout(speakers);
            }
            normalization = own;
            components.add(component);
        }
        if (normalization == null || unknown != components.size()) {
            return new ChannelLayout(speakers);
        }
        return new ChannelLayout(speakers, Optional.of(new Ambisonics(normalization, components)));
    }

    /**
     * Returns the label of every ambisonic channel of a layout.
     *
     * @param layout the channel layout to label
     * @return the labels in channel order, with {@link #LABEL_UNKNOWN} for the channels that are not ambisonic and for
     * the channels of a Furse-Malham layout whose component is above first order, which no label names; {@code null}
     * when the layout has no ambisonics
     */
    public static int[] labelsOf(ChannelLayout layout) {
        var ambisonics = layout.ambisonics().orElse(null);
        if (ambisonics == null) {
            return null;
        }
        var labels = new int[layout.channels()];
        var next = 0;
        for (var channel = 0; channel < labels.length; channel++) {
            if (layout.speakers().get(channel) != Speaker.UNKNOWN) {
                labels[channel] = LABEL_UNKNOWN;
                continue;
            }
            var component = ambisonics.components().get(next++);
            labels[channel] = switch (ambisonics.normalization()) {
                case SN3D -> LABEL_HOA_SN3D | component;
                case N3D -> LABEL_HOA_N3D | component;
                case FUMA -> component < 4 ? LABEL_AMBISONIC_W + indexOf(B_FORMAT_ACN, component) : LABEL_UNKNOWN;
            };
        }
        return labels;
    }

    /**
     * Returns the component a label names.
     *
     * @param label the channel label
     * @return the ACN, or {@code -1} when the label names no component this library represents
     */
    private static int componentOf(int label) {
        if (label >= LABEL_AMBISONIC_W && label < LABEL_AMBISONIC_W + 4) {
            return B_FORMAT_ACN[label - LABEL_AMBISONIC_W];
        }
        var family = label & 0xFFFF0000;
        if (family == LABEL_HOA_SN3D || family == LABEL_HOA_N3D) {
            var component = label & 0xFFFF;
            return component <= Ambisonics.MAX_COMPONENT ? component : -1;
        }
        return -1;
    }

    /**
     * Returns the normalization of an ambisonic label.
     *
     * @param label the channel label, ambisonic
     * @return the normalization
     */
    private static Ambisonics.Normalization normalizationOf(int label) {
        return switch (label & 0xFFFF0000) {
            case LABEL_HOA_SN3D -> Ambisonics.Normalization.SN3D;
            case LABEL_HOA_N3D -> Ambisonics.Normalization.N3D;
            default -> Ambisonics.Normalization.FUMA;
        };
    }

    /**
     * Returns the order whose full set of components has a channel count.
     *
     * @param channels the channel count
     * @return the order, or {@code -1} when the count is not the square of an order up to
     * {@value Ambisonics#MAX_ORDER}
     */
    private static int orderOf(int channels) {
        for (var order = 0; order <= Ambisonics.MAX_ORDER; order++) {
            if ((order + 1) * (order + 1) == channels) {
                return order;
            }
        }
        return -1;
    }

    /**
     * Returns the index of a value in an array.
     *
     * @param values the array
     * @param value the value, present
     * @return its index
     */
    private static int indexOf(int[] values, int value) {
        var index = 0;
        while (values[index] != value) {
            index++;
        }
        return index;
    }
}
