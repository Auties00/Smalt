package com.github.auties00.smalt.audio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Describes the ambisonic channels of a {@link ChannelLayout}: which spherical harmonic component each carries
 * and how the components are scaled.
 *
 * <p>A component is named by its Ambisonic Channel Number (ACN): the spherical harmonic of degree {@code l} and
 * index {@code m}, {@code -l <= m <= l}, is component {@code l * (l + 1) + m}. The first-order components are
 * {@code W} (0), {@code Y} (1, left), {@code Z} (2, up) and {@code X} (3, front). The spherical harmonics are the
 * real ones without the Condon-Shortley phase; a plane wave arriving from azimuth {@code A} (counterclockwise
 * from the front, so that positive azimuths are on the left) and elevation {@code E} (positive above the
 * horizontal plane) has, in {@link Normalization#SN3D}, the first-order components
 * {@snippet :
 * W = 1
 * Y = sin(A) * cos(E)
 * Z = sin(E)
 * X = cos(A) * cos(E)
 * }
 *
 * <p>The ambisonic channels of a layout are its channels of {@link Speaker#UNKNOWN} position, in channel order:
 * the {@code k}-th of them carries {@code components().get(k)}. {@link #of(int)} describes the AmbiX convention
 * (ACN order, SN3D), the one of Opus mapping families 2 and 3 (RFC 8486), the MP4 spatial audio box and most
 * current tools; {@link #ofFurseMalham(int)} describes the traditional B-format of the WAVE ambisonic
 * sub-format.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param normalization the scaling of the components
 * @param components the ACN of the component each ambisonic channel carries, in channel order; distinct values
 * from 0 to {@value #MAX_COMPONENT}, and from 0 to 15 for {@link Normalization#FUMA}
 */
public record Ambisonics(Normalization normalization, List<Integer> components) {
    /**
     * The highest order described, 14, whose {@code 225} components fit the 255 channels of an Opus stream.
     */
    public static final int MAX_ORDER = 14;

    /**
     * The largest component number, the last component of order {@link #MAX_ORDER}.
     */
    public static final int MAX_COMPONENT = (MAX_ORDER + 1) * (MAX_ORDER + 1) - 1;

    /**
     * The ACN of each Furse-Malham channel, in the order {@code W X Y Z R S T U V K L M N O P Q}.
     */
    private static final int[] FURSE_MALHAM_ACN = {0, 3, 1, 2, 6, 7, 5, 8, 4, 12, 13, 11, 14, 10, 15, 9};

    /**
     * Validates and freezes the component list.
     *
     * @throws NullPointerException if {@code components} or an element is {@code null}
     * @throws IllegalArgumentException if {@code components} is empty, holds a duplicate or a value outside
     * 0 to {@value #MAX_COMPONENT}, or holds a component above 15 with {@link Normalization#FUMA}
     */
    public Ambisonics {
        Objects.requireNonNull(normalization, "normalization");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty()) {
            throw new IllegalArgumentException("ambisonics need at least one component");
        }
        var limit = normalization == Normalization.FUMA ? 15 : MAX_COMPONENT;
        var seen = new HashSet<Integer>();
        for (var component : components) {
            if (component < 0 || component > limit) {
                throw new IllegalArgumentException("ambisonic component " + component + " is outside 0 to " + limit
                        + " for " + normalization);
            }
            if (!seen.add(component)) {
                throw new IllegalArgumentException("ambisonic component " + component + " appears twice");
            }
        }
    }

    /**
     * Returns every component of an order in ACN order with SN3D normalization, the AmbiX convention.
     *
     * @param order the ambisonic order, from 0 to {@value #MAX_ORDER}
     * @return the {@code (order + 1)^2} components
     * @throws IllegalArgumentException if {@code order} is outside 0 to {@value #MAX_ORDER}
     */
    public static Ambisonics of(int order) {
        return of(order, Normalization.SN3D);
    }

    /**
     * Returns every component of an order in ACN order.
     *
     * @param order the ambisonic order, from 0 to {@value #MAX_ORDER}, and at most 3 for
     * {@link Normalization#FUMA}
     * @param normalization the scaling of the components
     * @return the {@code (order + 1)^2} components
     * @throws IllegalArgumentException if {@code order} is out of range
     */
    public static Ambisonics of(int order, Normalization normalization) {
        if (order < 0 || order > MAX_ORDER) {
            throw new IllegalArgumentException("ambisonic order " + order + " is outside 0 to " + MAX_ORDER);
        }
        var count = (order + 1) * (order + 1);
        var components = new ArrayList<Integer>(count);
        for (var component = 0; component < count; component++) {
            components.add(component);
        }
        return new Ambisonics(normalization, components);
    }

    /**
     * Returns the Furse-Malham B-format of a channel count: the channels in the order
     * {@code W X Y Z R S T U V K L M N O P Q}, restricted to the set of the count, with
     * {@link Normalization#FUMA} scaling.
     *
     * <table class="striped">
     * <caption>Furse-Malham channel sets</caption>
     * <thead><tr><th scope="col">Channels</th><th scope="col">Order</th><th scope="col">Channels in file
     * order</th></tr></thead>
     * <tbody>
     * <tr><td>3</td><td>1, horizontal</td><td>{@code W X Y}</td></tr>
     * <tr><td>4</td><td>1</td><td>{@code W X Y Z}</td></tr>
     * <tr><td>5</td><td>2, horizontal</td><td>{@code W X Y U V}</td></tr>
     * <tr><td>6</td><td>2 horizontal, 1 vertical</td><td>{@code W X Y Z U V}</td></tr>
     * <tr><td>7</td><td>3, horizontal</td><td>{@code W X Y U V P Q}</td></tr>
     * <tr><td>8</td><td>3 horizontal, 1 vertical</td><td>{@code W X Y Z U V P Q}</td></tr>
     * <tr><td>9</td><td>2</td><td>{@code W X Y Z R S T U V}</td></tr>
     * <tr><td>11</td><td>3 horizontal, 2 vertical</td><td>{@code W X Y Z R S T U V P Q}</td></tr>
     * <tr><td>16</td><td>3</td><td>{@code W X Y Z R S T U V K L M N O P Q}</td></tr>
     * </tbody>
     * </table>
     *
     * @param channels the number of channels, one of 3, 4, 5, 6, 7, 8, 9, 11 and 16
     * @return the components
     * @throws IllegalArgumentException if {@code channels} is not one of those counts
     */
    public static Ambisonics ofFurseMalham(int channels) {
        var letters = switch (channels) {
            case 3 -> "WXY";
            case 4 -> "WXYZ";
            case 5 -> "WXYUV";
            case 6 -> "WXYZUV";
            case 7 -> "WXYUVPQ";
            case 8 -> "WXYZUVPQ";
            case 9 -> "WXYZRSTUV";
            case 11 -> "WXYZRSTUVPQ";
            case 16 -> "WXYZRSTUVKLMNOPQ";
            default -> throw new IllegalArgumentException("no Furse-Malham B-format has " + channels + " channels");
        };
        var components = new ArrayList<Integer>(channels);
        for (var i = 0; i < letters.length(); i++) {
            components.add(FURSE_MALHAM_ACN["WXYZRSTUVKLMNOPQ".indexOf(letters.charAt(i))]);
        }
        return new Ambisonics(Normalization.FUMA, components);
    }

    /**
     * {@return the number of ambisonic channels}
     */
    public int channels() {
        return components.size();
    }

    /**
     * {@return the ambisonic order, the highest degree of any component}
     */
    public int order() {
        var highest = 0;
        for (var component : components) {
            highest = Math.max(highest, component);
        }
        return degree(highest);
    }

    /**
     * Returns whether the channels carry every component up to {@link #order()}, in any channel order.
     *
     * @return {@code true} for a full-sphere set, {@code false} for a mixed-order or partial one
     */
    public boolean isFull() {
        var order = order();
        return components.size() == (order + 1) * (order + 1);
    }

    /**
     * Returns whether the channels carry every component up to {@link #order()} in ACN order, so that ambisonic
     * channel {@code k} carries component {@code k}.
     *
     * @return {@code true} for the sequential ACN layout of {@link #of(int, Normalization)}
     */
    public boolean isSequential() {
        for (var k = 0; k < components.size(); k++) {
            if (components.get(k) != k) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the ambisonic channel carrying a component.
     *
     * @param component the ACN of the component
     * @return the index among the ambisonic channels, or {@code -1} when no channel carries it
     */
    public int indexOf(int component) {
        return components.indexOf(component);
    }

    /**
     * Returns a copy of these ambisonics with another normalization, the same channels carrying the same
     * components.
     *
     * @param normalization the scaling of the components
     * @return the ambisonics
     * @throws IllegalArgumentException if {@code normalization} is {@link Normalization#FUMA} and a component is
     * above 15
     */
    public Ambisonics withNormalization(Normalization normalization) {
        return new Ambisonics(normalization, components);
    }

    /**
     * Returns the degree {@code l} of a component.
     *
     * @param component the ACN, non-negative
     * @return {@code floor(sqrt(component))}
     */
    public static int degree(int component) {
        var degree = (int) Math.sqrt(component);
        while (degree * degree > component) {
            degree--;
        }
        while ((degree + 1) * (degree + 1) <= component) {
            degree++;
        }
        return degree;
    }

    /**
     * Enumerates the normalizations of ambisonic components.
     */
    public enum Normalization {
        /**
         * Schmidt semi-normalization: the component of degree {@code l} and index {@code m} is scaled by
         * {@code sqrt((2 - delta(m)) * (l - |m|)! / (l + |m|)!)}, so that {@code W} of a plane wave equals its
         * amplitude; the AmbiX convention.
         */
        SN3D,

        /**
         * Full three-dimensional normalization: the SN3D components of degree {@code l} multiplied by
         * {@code sqrt(2 * l + 1)}, giving orthonormal components.
         */
        N3D,

        /**
         * The Furse-Malham (MaxN) scaling of traditional B-format, up to order 3: every component scaled so that
         * its largest value over the sphere is 1, except {@code W}, which is scaled by {@code 1 / sqrt(2)}.
         */
        FUMA
    }
}
