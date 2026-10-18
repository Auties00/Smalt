/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: the MA tree) and libjxl (lib/jxl/modular/encoding/dec_ma.cc:
   DecodeTree). Copyright Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;

import java.util.Arrays;

/**
 * Holds a meta-adaptive (MA) tree and the entropy decoder of its leaves (ISO/IEC 18181-1 section
 * H.4).
 *
 * <p>Each decision node compares one property of the sample being decoded with a value and
 * continues to its left child when the property is greater, to its right child otherwise; each
 * leaf names a context, a predictor, an offset and a multiplier. The tree is stored in arrays, in
 * the breadth-first order the bitstream lists its nodes.
 *
 * <p>Instances are immutable; {@link #decoder()} returns the shared template that each stream
 * copies with {@link EntropyDecoder#copy()}.
 *
 * @implNote This implementation takes the node order from libjxl and the validity limits from
 * jxl-oxide.
 */
public final class MaTree {
    /**
     * The largest tree depth accepted.
     */
    private static final int DEPTH_LIMIT = 2048;

    /**
     * The property of each node, or -1 for a leaf.
     */
    final int[] property;

    /**
     * The split value of each decision node, or the offset of each leaf.
     */
    final int[] value;

    /**
     * The left child of each decision node, or the cluster of each leaf.
     */
    final int[] left;

    /**
     * The right child of each decision node, or the predictor of each leaf.
     */
    final int[] right;

    /**
     * The multiplier of each leaf.
     */
    final int[] multiplier;

    /**
     * The entropy decoder of the leaf contexts.
     */
    private final EntropyDecoder decoder;

    /**
     * Creates a tree.
     *
     * @param property the node properties
     * @param value the split values and leaf offsets
     * @param left the left children and leaf clusters
     * @param right the right children and leaf predictors
     * @param multiplier the leaf multipliers
     * @param decoder the leaf entropy decoder
     */
    private MaTree(int[] property, int[] value, int[] left, int[] right, int[] multiplier, EntropyDecoder decoder) {
        this.property = property;
        this.value = value;
        this.left = left;
        this.right = right;
        this.multiplier = multiplier;
        this.decoder = decoder;
    }

    /**
     * Reads a tree and its leaf decoder.
     *
     * @param br the bitstream
     * @param nodeLimit the largest number of nodes accepted
     * @return the tree
     * @throws MediaException.Malformed if the tree is invalid, too large or too deep
     */
    public static MaTree read(JxlBitReader br, long nodeLimit) {
        var treeDecoder = EntropyDecoder.read(br, 6);
        var leafCluster = treeDecoder.cluster(1);
        var single = treeDecoder.singleToken(leafCluster);
        if (single > 0) {
            throw new MediaException.Malformed("infinite MA tree");
        }
        treeDecoder.begin(br);
        var capacity = 16;
        var property = new int[capacity];
        var value = new int[capacity];
        var left = new int[capacity];
        var right = new int[capacity];
        var multiplier = new int[capacity];
        var size = 0;
        var toDecode = 1;
        var leaves = 0;
        while (toDecode > 0) {
            if (size >= nodeLimit || size >= 1 << 26) {
                throw new MediaException.Malformed("MA tree too large");
            }
            if (size == capacity) {
                capacity *= 2;
                property = Arrays.copyOf(property, capacity);
                value = Arrays.copyOf(value, capacity);
                left = Arrays.copyOf(left, capacity);
                right = Arrays.copyOf(right, capacity);
                multiplier = Arrays.copyOf(multiplier, capacity);
            }
            toDecode--;
            var prop = treeDecoder.readUint(br, 1);
            if (prop != 0) {
                if (prop < 0) {
                    throw new MediaException.Malformed("invalid MA tree property " + prop);
                }
                property[size] = prop - 1;
                value[size] = unpackSigned(treeDecoder.readUint(br, 0));
                left[size] = size + toDecode + 1;
                right[size] = size + toDecode + 2;
                toDecode += 2;
            } else {
                var predictor = treeDecoder.readUint(br, 2);
                if (predictor < 0 || predictor > 13) {
                    throw new MediaException.Malformed("invalid modular predictor " + predictor);
                }
                var offset = unpackSigned(treeDecoder.readUint(br, 3));
                var mulLog = treeDecoder.readUint(br, 4);
                if (mulLog < 0 || mulLog > 30) {
                    throw new MediaException.Malformed("invalid MA tree multiplier");
                }
                var mulBits = treeDecoder.readUint(br, 5);
                if (mulBits < 0 || (long) mulBits > (1L << (31 - mulLog)) - 2) {
                    throw new MediaException.Malformed("invalid MA tree multiplier");
                }
                property[size] = -1;
                value[size] = offset;
                left[size] = leaves++;
                right[size] = predictor;
                multiplier[size] = (mulBits + 1) << mulLog;
            }
            size++;
        }
        treeDecoder.checkFinalState();
        var decoder = EntropyDecoder.read(br, leaves);
        var clusters = decoder.clusterMap();
        for (var i = 0; i < size; i++) {
            if (property[i] < 0) {
                left[i] = clusters[left[i]];
            }
        }
        checkDepth(property, left, right, size);
        return new MaTree(Arrays.copyOf(property, size), Arrays.copyOf(value, size), Arrays.copyOf(left, size),
                Arrays.copyOf(right, size), Arrays.copyOf(multiplier, size), decoder);
    }

    /**
     * Checks that no leaf lies deeper than {@link #DEPTH_LIMIT}.
     *
     * @param property the node properties
     * @param left the left children
     * @param right the right children
     * @param size the node count
     * @throws MediaException.Malformed if the tree is too deep
     */
    private static void checkDepth(int[] property, int[] left, int[] right, int size) {
        var depth = new int[size];
        for (var i = 0; i < size; i++) {
            if (property[i] >= 0) {
                var d = depth[i] + 1;
                if (d > DEPTH_LIMIT) {
                    throw new MediaException.Malformed("MA tree too deep");
                }
                depth[left[i]] = d;
                depth[right[i]] = d;
            }
        }
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Returns the leaf entropy decoder.
     *
     * @return the shared template decoder
     */
    public EntropyDecoder decoder() {
        return decoder;
    }

    /**
     * Returns the number of nodes.
     *
     * @return the node count
     */
    public int size() {
        return property.length;
    }

    /**
     * Builds the tree a channel actually uses: decisions on the channel index, the stream index
     * and unavailable previous channels are resolved, and the remaining nodes are renumbered.
     *
     * @param channel the channel index
     * @param streamIndex the stream index
     * @param prevChannels the number of previous channels of the same size available
     * @return the pruned tree, in a compact form for the sample decoder
     */
    Pruned prune(int channel, int streamIndex, int prevChannels) {
        var order = new int[property.length];
        var mapped = new int[property.length];
        Arrays.fill(mapped, -1);
        var count = 0;
        var root = skip(0, channel, streamIndex, prevChannels);
        order[count] = root;
        mapped[root] = count++;
        for (var i = 0; i < count; i++) {
            var node = order[i];
            if (property[node] < 0) {
                continue;
            }
            for (var child : new int[]{left[node], right[node]}) {
                var target = skip(child, channel, streamIndex, prevChannels);
                if (mapped[target] < 0) {
                    order[count] = target;
                    mapped[target] = count++;
                }
            }
        }
        var p = new int[count];
        var v = new int[count];
        var l = new int[count];
        var r = new int[count];
        var m = new int[count];
        var usedProperties = 0L;
        var maxPrev = 0;
        var usesWeighted = false;
        for (var i = 0; i < count; i++) {
            var node = order[i];
            p[i] = property[node];
            v[i] = value[node];
            if (property[node] >= 0) {
                l[i] = mapped[skip(left[node], channel, streamIndex, prevChannels)];
                r[i] = mapped[skip(right[node], channel, streamIndex, prevChannels)];
                if (property[node] < 16) {
                    usedProperties |= 1L << property[node];
                    if (property[node] == 15) {
                        usesWeighted = true;
                    }
                } else {
                    maxPrev = Math.max(maxPrev, (property[node] - 16) / 4 + 1);
                }
            } else {
                l[i] = left[node];
                r[i] = right[node];
                m[i] = multiplier[node];
                if (right[node] == Predictors.WEIGHTED) {
                    usesWeighted = true;
                }
            }
        }
        return new Pruned(p, v, l, r, m, usedProperties, maxPrev, usesWeighted);
    }

    /**
     * Follows decisions that do not depend on the sample: the channel and stream indices and the
     * properties of previous channels that do not exist (whose value is 0).
     *
     * @param node the starting node
     * @param channel the channel index
     * @param streamIndex the stream index
     * @param prevChannels the number of previous channels available
     * @return the first node that is a leaf or a sample-dependent decision
     */
    private int skip(int node, int channel, int streamIndex, int prevChannels) {
        while (property[node] >= 0) {
            var prop = property[node];
            int target;
            if (prop == 0) {
                target = channel;
            } else if (prop == 1) {
                target = streamIndex;
            } else if (prop >= 16 && (prop - 16) / 4 >= prevChannels) {
                target = 0;
            } else {
                return node;
            }
            node = target > value[node] ? left[node] : right[node];
        }
        return node;
    }

    /**
     * Holds a tree pruned for one channel.
     *
     * @param property the node properties, -1 for leaves
     * @param value the split values and leaf offsets
     * @param left the left children and leaf clusters
     * @param right the right children and leaf predictors
     * @param multiplier the leaf multipliers
     * @param usedProperties the bit set of the properties below 16 that decisions use
     * @param maxPrev the number of previous channels decisions read
     * @param usesWeighted whether the weighted predictor or its error property is used
     */
    record Pruned(int[] property, int[] value, int[] left, int[] right, int[] multiplier, long usedProperties,
                  int maxPrev, boolean usesWeighted) {
        /**
         * Returns whether the tree is a single leaf.
         *
         * @return whether node 0 is a leaf
         */
        boolean isLeaf() {
            return property[0] < 0;
        }
    }
}
