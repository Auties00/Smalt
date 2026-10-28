/* Ports libjxl 0.11.2 (lib/jxl/modular/encoding/enc_encoding.cc: GatherTreeData, PredefinedTree, MakeFixedTree,
   EncodeModularChannelMAANS, ModularEncode; enc_ma.cc: TokenizeTree; lib/jxl/enc_modular.cc: ComputeTree,
   MergeTrees, EncodeGlobalInfo, EncodeStream). Copyright the JPEG XL Project Authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.image.jxl.internal.encode.EntropyEncoder;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlBitWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.Tokens;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Codes a set of modular streams that share one meta-adaptive tree and one set of distributions (ISO/IEC 18181-1
 * clause H): the tree is learned from (or fixed for) the streams, every channel is tokenized through it, and the
 * tree, the distributions and each stream are written separately, in the order the frame layout needs.
 *
 * <p>Instances are not thread-safe.
 */
public final class ModularEncoder {
    /**
     * A learned tree.
     */
    public static final int TREE_LEARN = 0;

    /**
     * A single leaf with the gradient predictor.
     */
    public static final int TREE_GRADIENT = 1;

    /**
     * A fixed tree splitting the gradient property, for LF coefficients and fast lossless coding.
     */
    public static final int TREE_GRADIENT_FIXED = 2;

    /**
     * A fixed tree splitting the weighted predictor's error, with the weighted predictor.
     */
    public static final int TREE_WP_FIXED = 3;

    /**
     * A single leaf with the zero predictor.
     */
    public static final int TREE_ZERO = 4;

    /**
     * The fixed tree of VarDCT AC metadata.
     */
    public static final int TREE_AC_META = 5;

    /**
     * A single leaf with the west predictor.
     */
    public static final int TREE_WEST = 6;

    private static final int[] FIXED_CUTOFFS = {
            -500, -392, -255, -191, -127, -95, -63, -47, -31, -23, -15, -11, -7, -4, -3, -1, 0, 1, 3, 5, 7, 11,
            15, 23, 31, 47, 63, 95, 127, 191, 255, 392, 500};

    /**
     * A modular sub-image coded as one stream: its channels after its transforms, and the transforms its header
     * lists.
     */
    public static final class Stream {
        final int id;

        final List<ModularChannel> channels;

        int nbMeta;

        int coded = -1;

        final List<int[]> transforms = new ArrayList<>();

        int bitDepth = 8;

        /**
         * Constructs a stream.
         *
         * @param id the stream index of ISO/IEC 18181-1 H.4.1
         * @param channels the channels, before any transform
         */
        public Stream(int id, List<ModularChannel> channels) {
            this.id = id;
            this.channels = new ArrayList<>(channels);
        }

        /**
         * {@return the stream index}
         */
        public int id() {
            return id;
        }

        /**
         * {@return the channels after the transforms, meta channels first}
         */
        public List<ModularChannel> channels() {
            return channels;
        }

        /**
         * {@return the number of meta channels}
         */
        public int nbMeta() {
            return nbMeta;
        }

        /**
         * Limits the channels this stream codes to a prefix; the frame codes the rest in group streams.
         *
         * @param count the number of leading channels coded here
         */
        public void codeOnly(int count) {
            coded = count;
        }

        /**
         * Sets the sample bit depth, which only the cost estimates of the transforms use.
         *
         * @param bitDepth the bit depth
         */
        public void bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
        }

        /**
         * {@return whether this stream has any channel to code}
         */
        public boolean isEmpty() {
            for (var i = 0; i < codedCount(); i++) {
                var c = channels.get(i);
                if (c.width > 0 && c.height > 0) {
                    return false;
                }
            }
            return true;
        }

        int codedCount() {
            return coded < 0 ? channels.size() : Math.min(coded, channels.size());
        }

        void addRct(int beginC, int type) {
            transforms.add(new int[]{ModularTransform.RCT, beginC, type});
        }

        void addPalette(int beginC, int numC, int nbColors, int nbDeltas, int predictor) {
            transforms.add(new int[]{ModularTransform.PALETTE, beginC, numC, nbColors, nbDeltas, predictor});
        }

        void writeHeader(JxlBitWriter w) {
            w.writeBool(true);
            w.writeBool(true);
            w.writeU32(transforms.size(), 0, 0, 1, 0, 2, 4, 18, 8);
            for (var t : transforms) {
                w.write(2, t[0]);
                w.writeU32(t[1], 0, 3, 8, 6, 72, 10, 1096, 13);
                if (t[0] == ModularTransform.RCT) {
                    w.writeU32(t[2], 6, 0, 0, 2, 2, 4, 10, 6);
                } else {
                    w.writeU32(t[2], 1, 0, 3, 0, 4, 0, 1, 13);
                    w.writeU32(t[3], 0, 8, 256, 10, 1280, 12, 5376, 16);
                    w.writeU32(t[4], 0, 0, 1, 8, 257, 10, 1281, 16);
                    w.write(4, t[5]);
                }
            }
        }
    }

    /**
     * How the tree of a range of streams is made.
     *
     * @param firstStream the first stream index of the range; the range ends where the next one starts
     * @param kind how the tree is made, one of the {@code TREE_*} constants
     * @param predictors the predictors a learned tree may use, the first one being the root's
     * @param properties the properties a learned tree may split on
     * @param maxPropertyValues the number of quantized values of each property
     * @param nodeThreshold the estimated saving in bits a split must reach
     * @param sampleFraction the fraction of pixels sampled for learning
     * @param fastDecodeMultiplier how much more a split avoiding the weighted predictor or using static
     * properties may cost and still be preferred
     */
    public record TreeSettings(int firstStream, int kind, int[] predictors, int[] properties, int maxPropertyValues,
                               float nodeThreshold, float sampleFraction, float fastDecodeMultiplier) {
        /**
         * Returns settings for a fixed or single-leaf tree.
         *
         * @param firstStream the first stream index
         * @param kind the tree kind
         * @return the settings
         */
        public static TreeSettings fixed(int firstStream, int kind) {
            return new TreeSettings(firstStream, kind, new int[]{Predictors.GRADIENT}, new int[0], 0, 0, 0, 1);
        }
    }

    private final List<Stream> streams;

    private int[] treeProperty;

    private int[] treeValue;

    private int[] treeLeft;

    private int[] treeRight;

    private int[] treeMultiplier;

    private int numLeaves;

    private final Tokens treeTokens = new Tokens();

    private final List<Tokens> streamTokens = new ArrayList<>();

    private EntropyEncoder treeCode;

    private EntropyEncoder code;

    private ModularEncoder(List<Stream> streams) {
        this.streams = streams;
    }

    /**
     * Learns or builds the tree of the streams and tokenizes them.
     *
     * @param streams the streams, in increasing stream index order
     * @param settings the tree settings of each stream range, in increasing first stream order; the first range
     * must start at or before the first stream
     * @param histograms the entropy coding parameters
     * @return the encoder, ready to write
     */
    public static ModularEncoder encode(List<Stream> streams, List<TreeSettings> settings,
                                        EntropyEncoder.Params histograms) {
        var encoder = new ModularEncoder(streams);
        encoder.buildTree(settings);
        encoder.tokenize(histograms);
        return encoder;
    }

    /**
     * {@return an estimate of the bits the tree, the distributions and the streams take}
     */
    public long estimatedBits() {
        return code == null ? 0 : code.estimatedBits() + treeCode.estimatedBits();
    }

    /**
     * Writes the global tree flag, the tree and the distributions of the GlobalModular section.
     *
     * @param w the output
     */
    public void writeGlobal(JxlBitWriter w) {
        if (code == null) {
            w.writeBool(false);
            return;
        }
        w.writeBool(true);
        treeCode.writeHeader(w);
        treeCode.writeTokens(treeTokens, w);
        code.writeHeader(w);
    }

    /**
     * Writes a stream: its header and its tokens.
     *
     * @param stream the stream, one of those the encoder was built from
     * @param w the output
     */
    public void writeStream(Stream stream, JxlBitWriter w) {
        var index = streams.indexOf(stream);
        stream.writeHeader(w);
        if (code != null) {
            code.writeTokens(streamTokens.get(index), w);
        }
    }

    private void buildTree(List<TreeSettings> settings) {
        var trees = new ArrayList<Tree>();
        var splits = new ArrayList<Integer>();
        for (var r = 0; r < settings.size(); r++) {
            var s = settings.get(r);
            var end = r + 1 < settings.size() ? settings.get(r + 1).firstStream() : Integer.MAX_VALUE;
            var members = new ArrayList<Stream>();
            for (var stream : streams) {
                if (stream.id >= s.firstStream() && stream.id < end && !stream.isEmpty()) {
                    members.add(stream);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            long pixels = 0;
            for (var stream : members) {
                for (var i = 0; i < stream.codedCount(); i++) {
                    pixels += (long) stream.channels.get(i).width * stream.channels.get(i).height;
                }
            }
            Tree tree = switch (s.kind()) {
                case TREE_LEARN -> TreeLearner.learn(members, s);
                case TREE_GRADIENT_FIXED -> fixedTree(9, Predictors.GRADIENT, pixels);
                case TREE_WP_FIXED -> fixedTree(15, Predictors.WEIGHTED, pixels);
                case TREE_ZERO -> Tree.leaf(Predictors.ZERO);
                case TREE_WEST -> Tree.leaf(Predictors.WEST);
                case TREE_AC_META -> pixels < 1024 ? Tree.leaf(Predictors.WEST) : acMetaTree();
                default -> Tree.leaf(Predictors.GRADIENT);
            };
            trees.add(tree);
            splits.add(s.firstStream());
        }
        if (trees.isEmpty()) {
            return;
        }
        splits.add(Integer.MAX_VALUE);
        var merged = new Tree();
        merge(trees, splits, 0, trees.size(), merged);
        tokenizeTree(merged);
    }

    private static void merge(List<Tree> trees, List<Integer> splits, int begin, int end, Tree out) {
        if (end == begin + 1) {
            var base = out.size();
            var t = trees.get(begin);
            for (var i = 0; i < t.size(); i++) {
                var leaf = t.property.get(i) < 0;
                out.add(t.property.get(i), t.splitval.get(i), leaf ? 0 : t.lchild.get(i) + base,
                        leaf ? 0 : t.rchild.get(i) + base, t.predictor.get(i), t.offset.get(i), t.multiplier.get(i));
            }
            return;
        }
        var mid = (begin + end) / 2;
        var cur = out.size();
        out.add(1, splits.get(mid) - 1, 0, 0, 0, 0, 1);
        out.lchild.set(cur, out.size());
        merge(trees, splits, mid, end, out);
        out.rchild.set(cur, out.size());
        merge(trees, splits, begin, mid, out);
    }

    private static Tree fixedTree(int property, int predictor, long pixels) {
        var logPx = pixels <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(pixels - 1);
        var minGap = logPx < 14 ? 8 * (14 - logPx) : 0;
        var tree = Tree.leaf(predictor);
        var queue = new ArrayDeque<int[]>();
        queue.add(new int[]{0, FIXED_CUTOFFS.length, 0});
        while (!queue.isEmpty()) {
            var info = queue.poll();
            if (info[0] + minGap >= info[1]) {
                continue;
            }
            var split = (info[0] + info[1]) / 2;
            var pos = info[2];
            var left = tree.size();
            tree.setSplit(pos, property, FIXED_CUTOFFS[split], left, left + 1);
            queue.add(new int[]{split + 1, info[1], left});
            tree.add(-1, 0, 0, 0, predictor, 0, 1);
            queue.add(new int[]{info[0], split, left + 1});
            tree.add(-1, 0, 0, 0, predictor, 0, 1);
        }
        return tree;
    }

    private static Tree acMetaTree() {
        var t = new Tree();
        t.add(0, 1, 1, 2, 0, 0, 1);
        t.add(0, 2, 3, 4, 0, 0, 1);
        t.add(0, 0, 5, 6, 0, 0, 1);
        t.add(6, 3, 21, 22, 0, 0, 1);
        t.add(2, 0, 7, 8, 0, 0, 1);
        t.add(-1, 0, 0, 0, Predictors.GRADIENT, 0, 1);
        t.add(-1, 0, 0, 0, Predictors.GRADIENT, 0, 1);
        t.add(7, 5, 9, 10, 0, 0, 1);
        t.add(7, 5, 15, 16, 0, 0, 1);
        t.add(7, 11, 11, 12, 0, 0, 1);
        t.add(7, 3, 13, 14, 0, 0, 1);
        for (var i = 0; i < 4; i++) {
            t.add(-1, 0, 0, 0, Predictors.WEST, 0, 1);
        }
        t.add(7, 11, 17, 18, 0, 0, 1);
        t.add(7, 3, 19, 20, 0, 0, 1);
        for (var i = 0; i < 4; i++) {
            t.add(-1, 0, 0, 0, Predictors.ZERO, 0, 1);
        }
        t.add(7, 3, 23, 24, 0, 0, 1);
        t.add(7, 3, 25, 26, 0, 0, 1);
        for (var i = 0; i < 4; i++) {
            t.add(-1, 0, 0, 0, Predictors.ZERO, 0, 1);
        }
        return t;
    }

    private void tokenizeTree(Tree tree) {
        var queue = new ArrayDeque<Integer>();
        queue.add(0);
        var property = new ArrayList<Integer>();
        var value = new ArrayList<Integer>();
        var left = new ArrayList<Integer>();
        var right = new ArrayList<Integer>();
        var multiplier = new ArrayList<Integer>();
        var leaves = 0;
        while (!queue.isEmpty()) {
            var cur = queue.poll();
            var p = tree.property.get(cur);
            treeTokens.add(1, p + 1);
            if (p < 0) {
                var mul = tree.multiplier.get(cur);
                var mulLog = Integer.numberOfTrailingZeros(mul);
                treeTokens.add(2, tree.predictor.get(cur));
                treeTokens.add(3, Tokens.packSigned(tree.offset.get(cur)));
                treeTokens.add(4, mulLog);
                treeTokens.add(5, (mul >>> mulLog) - 1);
                property.add(-1);
                value.add(tree.offset.get(cur));
                left.add(leaves++);
                right.add(tree.predictor.get(cur));
                multiplier.add(mul);
                continue;
            }
            property.add(p);
            value.add(tree.splitval.get(cur));
            left.add(property.size() + queue.size());
            right.add(property.size() + queue.size() + 1);
            multiplier.add(1);
            queue.add(tree.lchild.get(cur));
            queue.add(tree.rchild.get(cur));
            treeTokens.add(0, Tokens.packSigned(tree.splitval.get(cur)));
        }
        var n = property.size();
        treeProperty = new int[n];
        treeValue = new int[n];
        treeLeft = new int[n];
        treeRight = new int[n];
        treeMultiplier = new int[n];
        for (var i = 0; i < n; i++) {
            treeProperty[i] = property.get(i);
            treeValue[i] = value.get(i);
            treeLeft[i] = left.get(i);
            treeRight[i] = right.get(i);
            treeMultiplier[i] = multiplier.get(i);
        }
        numLeaves = leaves;
    }

    private void tokenize(EntropyEncoder.Params params) {
        var tokens = new Tokens[streams.size()];
        if (treeProperty == null) {
            for (var i = 0; i < tokens.length; i++) {
                streamTokens.add(new Tokens(8));
            }
            return;
        }
        ParallelBands.run(tokens.length, i -> {
            var stream = new Tokens(1024);
            tokenizeStream(streams.get(i), stream);
            tokens[i] = stream;
        });
        streamTokens.addAll(List.of(tokens));
        treeCode = EntropyEncoder.build(treeTokens, 6, EntropyEncoder.Params.BEST);
        code = EntropyEncoder.build(streamTokens, numLeaves, params);
    }

    private void tokenizeStream(Stream stream, Tokens tokens) {
        var channels = stream.channels;
        var count = stream.codedCount();
        var usesWp = false;
        var maxPrev = 0;
        for (var i = 0; i < treeProperty.length; i++) {
            if (treeProperty[i] == 15 || (treeProperty[i] < 0 && treeRight[i] == Predictors.WEIGHTED)) {
                usesWp = true;
            }
            if (treeProperty[i] >= 16) {
                maxPrev = Math.max(maxPrev, (treeProperty[i] - 16) / 4 + 1);
            }
        }
        for (var i = 0; i < count; i++) {
            var channel = channels.get(i);
            if (channel.width == 0 || channel.height == 0) {
                continue;
            }
            var prev = new ArrayList<int[]>();
            for (var j = i - 1; j >= 0 && prev.size() < maxPrev; j--) {
                var other = channels.get(j);
                if (other.width != 0 && other.height != 0 && other.sameGeometry(channel)) {
                    prev.add(other.data);
                }
            }
            tokenizeChannel(channel, i, stream.id, prev, usesWp, tokens);
        }
    }

    private void tokenizeChannel(ModularChannel channel, int index, int streamId, List<int[]> prev, boolean usesWp,
                                 Tokens tokens) {
        var width = channel.width;
        var height = channel.height;
        var data = channel.data;
        var node = 0;
        while (treeProperty[node] >= 0 && (treeProperty[node] == 0 || treeProperty[node] == 1)) {
            var v = treeProperty[node] == 0 ? index : streamId;
            node = v > treeValue[node] ? treeLeft[node] : treeRight[node];
        }
        var root = node;
        if (treeProperty[root] < 0 && treeRight[root] != Predictors.WEIGHTED) {
            var ctx = treeLeft[root];
            var predictor = treeRight[root];
            var offset = treeValue[root];
            var multiplier = treeMultiplier[root];
            for (var y = 0; y < height; y++) {
                var row = y * width;
                for (var x = 0; x < width; x++) {
                    var idx = row + x;
                    var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                    var n = y > 0 ? data[idx - width] : w;
                    var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                    var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                    var nn = y > 1 ? data[idx - 2 * width] : n;
                    var ww = x > 1 ? data[idx - 2] : w;
                    var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                    var pred = Predictors.predict(predictor, n, w, nw, ne, nn, ww, nee);
                    tokens.addSigned(ctx, (data[idx] - pred - offset) / multiplier);
                }
            }
            return;
        }
        var props = new int[16 + 4 * prev.size()];
        props[0] = index;
        props[1] = streamId;
        var weighted = usesWp ? new WeightedPredictor(WpHeader.DEFAULT, width) : null;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            var prevGrad = 0;
            props[2] = y;
            for (var x = 0; x < width; x++) {
                var idx = row + x;
                var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                var n = y > 0 ? data[idx - width] : w;
                var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                var nn = y > 1 ? data[idx - 2 * width] : n;
                var ww = x > 1 ? data[idx - 2] : w;
                var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                if (weighted != null) {
                    weighted.predict(n, nw, ne, w, nn);
                    props[15] = weighted.maxError;
                }
                var grad = w - nw + n;
                props[3] = x;
                props[4] = Math.abs(n);
                props[5] = Math.abs(w);
                props[6] = n;
                props[7] = w;
                props[8] = w - prevGrad;
                props[9] = grad;
                props[10] = w - nw;
                props[11] = nw - n;
                props[12] = n - ne;
                props[13] = n - nn;
                props[14] = w - ww;
                for (var k = 0; k < prev.size(); k++) {
                    referenceProperties(prev.get(k), idx, x, y, width, props, 16 + 4 * k);
                }
                node = root;
                while (treeProperty[node] >= 0) {
                    var p = treeProperty[node];
                    node = props[p] > treeValue[node] ? treeLeft[node] : treeRight[node];
                }
                var predictor = treeRight[node];
                int pred;
                if (predictor == Predictors.WEIGHTED) {
                    pred = (int) ((weighted.prediction + 3) >> 3);
                } else {
                    pred = Predictors.predict(predictor, n, w, nw, ne, nn, ww, nee);
                }
                tokens.addSigned(treeLeft[node], (data[idx] - pred - treeValue[node]) / treeMultiplier[node]);
                if (weighted != null) {
                    weighted.record(data[idx]);
                }
                prevGrad = grad;
            }
        }
    }

    static void referenceProperties(int[] channel, int idx, int x, int y, int width, int[] props, int base) {
        var c = channel[idx];
        int g;
        if (x == 0 && y == 0) {
            g = 0;
        } else if (x == 0) {
            g = channel[idx - width];
        } else if (y == 0) {
            g = channel[idx - 1];
        } else {
            g = Predictors.gradient(channel[idx - width], channel[idx - 1], channel[idx - width - 1]);
        }
        props[base] = Math.abs(c);
        props[base + 1] = c;
        props[base + 2] = (int) Math.abs((long) c - g);
        props[base + 3] = c - g;
    }

    /**
     * A meta-adaptive tree under construction, in the encoder's node order.
     */
    static final class Tree {
        final IntList property = new IntList();

        final IntList splitval = new IntList();

        final IntList lchild = new IntList();

        final IntList rchild = new IntList();

        final IntList predictor = new IntList();

        final IntList offset = new IntList();

        final IntList multiplier = new IntList();

        static Tree leaf(int predictor) {
            var t = new Tree();
            t.add(-1, 0, 0, 0, predictor, 0, 1);
            return t;
        }

        int size() {
            return property.size();
        }

        void add(int p, int v, int l, int r, int pred, int off, int mul) {
            property.add(p);
            splitval.add(v);
            lchild.add(l);
            rchild.add(r);
            predictor.add(pred);
            offset.add(off);
            multiplier.add(mul);
        }

        void setSplit(int pos, int p, int v, int l, int r) {
            property.set(pos, p);
            splitval.set(pos, v);
            lchild.set(pos, l);
            rchild.set(pos, r);
        }
    }

    /**
     * A growable list of ints.
     */
    static final class IntList {
        private int[] values = new int[16];

        private int size;

        void add(int v) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, size * 2);
            }
            values[size++] = v;
        }

        int get(int i) {
            return values[i];
        }

        void set(int i, int v) {
            values[i] = v;
        }

        int size() {
            return size;
        }
    }
}
