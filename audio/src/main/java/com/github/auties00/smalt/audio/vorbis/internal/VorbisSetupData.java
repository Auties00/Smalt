/* Ports libvorbis 1.3.7 (lib/modes, lib/books, lib/masking.h).
   Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;

/**
 * Holds the encoder setup templates, codebooks and psychoacoustic tables of libvorbis 1.3.7,
 * read once from the {@code vorbis-setup.bin.gz} resource.
 *
 * <p>The resource is a little-endian serialization of libvorbis's {@code setup_list} (17
 * templates, one per sampling rate family and coupling), the 800 codebooks they reference, the
 * {@code _psy_info_template}, the threshold-in-quiet table {@code ATH}, the tone masking curves
 * {@code tonemasks} and the window tables of {@code window.c}, written by
 * {@code tools/codegen/vorbis/dump.c}; edit and rerun that program, never the resource.
 * The data includes the aoTuV tunings merged into libvorbis.
 */
public final class VorbisSetupData {
    /**
     * The magic number that starts the resource.
     */
    private static final int MAGIC = 0x56534D44;

    /**
     * The tone masking curve length {@code EHMER_MAX}.
     */
    public static final int EHMER_MAX = 56;

    /**
     * The codebooks, indexed by the ids the templates use.
     */
    public final StaticCodebook[] books;

    /**
     * The templates, in libvorbis's search order.
     */
    public final Template[] templates;

    /**
     * The base psychoacoustic parameters every block starts from.
     */
    public final PsyInfo psyTemplate;

    /**
     * The threshold in quiet in eighth octaves from 15 Hz, in decibels.
     */
    public final float[] ath;

    /**
     * The tone masking curves, indexed {@code [band][level][point]}.
     */
    public final float[][][] toneMasks;

    /**
     * The rising halves of the Vorbis windows of block sizes 64 to 8192, as libvorbis tabulates
     * them ({@code vwin64} to {@code vwin8192}).
     */
    public final float[][] windows;

    /**
     * One encoder template: the settings of every quality step of a sampling rate family.
     */
    public static final class Template {
        /**
         * The number of quality steps; the interpolated tables hold one more entry.
         */
        public int mappings;

        /**
         * The approximate bitrate of each step per channel, or {@code null}.
         */
        public double[] rateMapping;

        /**
         * The quality value of each step.
         */
        public double[] qualityMapping;

        /**
         * The only channel count the template serves, or {@code -1} for any.
         */
        public int couplingRestriction;

        /**
         * The lowest sampling rate the template serves.
         */
        public int sampleRateMin;

        /**
         * The highest sampling rate the template serves.
         */
        public int sampleRateMax;

        /**
         * The short block size of each step.
         */
        public int[] blockSizeShort;

        /**
         * The long block size of each step.
         */
        public int[] blockSizeLong;

        /**
         * The tone master attenuations of each step: three curves, then the center boost and the
         * decay.
         */
        public float[][] toneMasterAttenuation;

        /**
         * The largest tone curve level of each step.
         */
        public int[] tone0dB;

        /**
         * The tone suppression of each step.
         */
        public int[] toneSuppress;

        /**
         * The per-band tone adjustment of impulse, long and other blocks, indexed
         * {@code [kind][step][band]}; a kind may be {@code null}.
         */
        public int[][][] toneAdjust;

        /**
         * The noise guards of the four block types: low, high, fixed.
         */
        public int[][] noiseGuards;

        /**
         * The noise bias of impulse, padding, transition and long blocks, indexed
         * {@code [kind][step][curve][band]}; a kind may be {@code null}.
         */
        public int[][][][] noiseBias;

        /**
         * The noise suppression of each step.
         */
        public int[] noiseSuppress;

        /**
         * The noise compander tables.
         */
        public int[][] noiseCompand;

        /**
         * The compander index of each step for short blocks.
         */
        public double[] compandShortMapping;

        /**
         * The compander index of each step for long blocks, or {@code null}.
         */
        public double[] compandLongMapping;

        /**
         * The noise normalization start of each step, for short and long blocks.
         */
        public int[][] normalStart;

        /**
         * The noise normalization partition of each step, for short and long blocks.
         */
        public int[][] normalPartition;

        /**
         * The noise normalization threshold of each step.
         */
        public double[] normalThreshold;

        /**
         * The floating threshold in quiet of each step.
         */
        public int[] athFloat;

        /**
         * The absolute threshold in quiet of each step.
         */
        public int[] athAbsolute;

        /**
         * The lowpass of each step, in kilohertz.
         */
        public double[] lowpass;

        /**
         * The global psychoacoustic parameter sets.
         */
        public PsyGlobal[] globalParams;

        /**
         * The global parameter index of each step.
         */
        public double[] globalMapping;

        /**
         * The stereo modes of each step, or {@code null}: pre-point amplitudes, post-point
         * amplitudes, point kHz and lowpass kHz of each blob.
         */
        public StereoMode[] stereoModes;

        /**
         * The floor index of each floor slot and step, indexed {@code [slot][step]}.
         */
        public int[][] floorMappingList;

        /**
         * The floors the template uses, by floor index.
         */
        public Floor1Info[] floorParams;

        /**
         * The book ids of each floor, by floor index.
         */
        public int[][] floorBooks;

        /**
         * The mapping and residue templates of each step.
         */
        public MapTemplate[] maps;

        /**
         * Creates an empty template.
         */
        Template() {
        }
    }

    /**
     * The stereo settings of one step.
     *
     * @param prePoint the pre-point amplitude index of each blob
     * @param postPoint the post-point amplitude index of each blob
     * @param khz the point stereo frequency of each blob
     * @param lowpassKhz the lowpass of each blob
     */
    public record StereoMode(int[] prePoint, int[] postPoint, float[] khz, float[] lowpassKhz) {
    }

    /**
     * The mappings and residues of one step.
     *
     * @param modes the number of modes (1 when short and long blocks have one size)
     * @param mappings the mapping of each mode
     * @param residues the residue templates the mappings reference
     */
    public record MapTemplate(int modes, Mapping0Info[] mappings, ResidueTemplate[] residues) {
    }

    /**
     * One residue template.
     *
     * @param type the residue type, 1 or 2
     * @param limitType 0 lowpass limited, 1 point stereo limited, 2 LFE
     * @param grouping the partition size
     * @param residue the residue configuration
     * @param auxBook the class book, or {@code -1}
     * @param books the stage books of each class, {@code [class][stage]}, {@code -1} for none
     */
    public record ResidueTemplate(int type, int limitType, int grouping, Residue0Info residue, int auxBook,
                                  int[][] books) {
    }

    /**
     * Parses the resource.
     *
     * @param data the uncompressed resource
     */
    private VorbisSetupData(byte[] data) {
        var in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != MAGIC) {
            throw new IllegalStateException("corrupt Vorbis setup data");
        }
        var bookCount = in.getInt();
        books = new StaticCodebook[bookCount];
        for (var i = 0; i < bookCount; i++) {
            var dim = in.getInt();
            var entries = in.getInt();
            var mapType = in.getInt();
            var quantMin = in.getInt();
            var quantDelta = in.getInt();
            var quantBits = in.getInt();
            var sequence = in.getInt() != 0;
            var quantCount = in.getInt();
            var lengths = new byte[entries];
            in.get(lengths);
            var quant = new int[quantCount];
            for (var k = 0; k < quantCount; k++) {
                quant[k] = in.getInt();
            }
            books[i] = new StaticCodebook(dim, entries, lengths, mapType, quantMin, quantDelta, quantBits, sequence, quant);
        }
        var count = in.getInt();
        templates = new Template[count];
        for (var t = 0; t < count; t++) {
            templates[t] = template(in);
        }
        psyTemplate = psyInfo(in);
        var athCount = in.getInt();
        ath = floats(in, athCount);
        toneMasks = new float[PsyInfo.BANDS][6][];
        for (var i = 0; i < PsyInfo.BANDS; i++) {
            for (var j = 0; j < 6; j++) {
                toneMasks[i][j] = floats(in, EHMER_MAX);
            }
        }
        windows = new float[8][];
        for (var w = 0; w < 8; w++) {
            windows[w] = floats(in, 32 << w);
        }
    }

    /**
     * Returns the data, loading it on first use.
     *
     * @return the data
     * @throws UncheckedIOException if the resource cannot be read
     */
    public static VorbisSetupData get() {
        return Holder.INSTANCE;
    }

    /**
     * Defers loading the data until {@link #get()} is first called.
     */
    private static final class Holder {
        /**
         * The loaded data.
         */
        static final VorbisSetupData INSTANCE = load();

        /**
         * Prevents instantiation.
         */
        private Holder() {
            throw new AssertionError();
        }
    }

    /**
     * Reads and parses the resource.
     *
     * @return the data
     */
    private static VorbisSetupData load() {
        try (InputStream stream = VorbisSetupData.class.getResourceAsStream("vorbis-setup.bin.gz")) {
            if (stream == null) {
                throw new IllegalStateException("missing Vorbis setup data");
            }
            try (var gzip = new GZIPInputStream(stream)) {
                return new VorbisSetupData(gzip.readAllBytes());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Reads one template.
     *
     * @param in the reader
     * @return the template
     */
    private static Template template(ByteBuffer in) {
        var t = new Template();
        var m = t.mappings = in.getInt();
        t.rateMapping = in.getInt() != 0 ? doubles(in, m + 1) : null;
        t.qualityMapping = doubles(in, m + 1);
        t.couplingRestriction = in.getInt();
        t.sampleRateMin = in.getInt();
        t.sampleRateMax = in.getInt();
        t.blockSizeShort = ints(in, m);
        t.blockSizeLong = ints(in, m);
        t.toneMasterAttenuation = new float[m + 1][5];
        for (var i = 0; i <= m; i++) {
            for (var k = 0; k < 3; k++) {
                t.toneMasterAttenuation[i][k] = in.getInt();
            }
            t.toneMasterAttenuation[i][3] = in.getFloat();
            t.toneMasterAttenuation[i][4] = in.getFloat();
        }
        t.tone0dB = ints(in, m + 1);
        t.toneSuppress = ints(in, m + 1);
        t.toneAdjust = new int[3][][];
        for (var k = 0; k < 3; k++) {
            if (in.getInt() != 0) {
                t.toneAdjust[k] = new int[m + 1][];
                for (var i = 0; i <= m; i++) {
                    t.toneAdjust[k][i] = ints(in, PsyInfo.BANDS);
                }
            }
        }
        t.noiseGuards = new int[4][];
        for (var i = 0; i < 4; i++) {
            t.noiseGuards[i] = ints(in, 3);
        }
        t.noiseBias = new int[4][][][];
        for (var k = 0; k < 4; k++) {
            if (in.getInt() != 0) {
                t.noiseBias[k] = new int[m + 1][PsyInfo.NOISE_CURVES][];
                for (var i = 0; i <= m; i++) {
                    for (var j = 0; j < PsyInfo.NOISE_CURVES; j++) {
                        t.noiseBias[k][i][j] = ints(in, 17);
                    }
                }
            }
        }
        t.noiseSuppress = ints(in, m + 1);
        var compands = in.getInt();
        t.noiseCompand = new int[compands][];
        for (var i = 0; i < compands; i++) {
            t.noiseCompand[i] = ints(in, PsyInfo.COMPAND_LEVELS);
        }
        t.compandShortMapping = doubles(in, m + 1);
        t.compandLongMapping = in.getInt() != 0 ? doubles(in, m + 1) : null;
        t.normalStart = new int[2][];
        for (var k = 0; k < 2; k++) {
            t.normalStart[k] = in.getInt() != 0 ? ints(in, m) : null;
        }
        t.normalPartition = new int[2][];
        for (var k = 0; k < 2; k++) {
            t.normalPartition[k] = in.getInt() != 0 ? ints(in, m) : null;
        }
        t.normalThreshold = doubles(in, m);
        t.athFloat = ints(in, m + 1);
        t.athAbsolute = ints(in, m + 1);
        t.lowpass = doubles(in, m + 1);
        var globals = in.getInt();
        t.globalParams = new PsyGlobal[globals];
        for (var i = 0; i < globals; i++) {
            t.globalParams[i] = psyGlobal(in);
        }
        t.globalMapping = doubles(in, m + 1);
        if (in.getInt() != 0) {
            t.stereoModes = new StereoMode[m + 1];
            for (var i = 0; i <= m; i++) {
                t.stereoModes[i] = new StereoMode(ints(in, PsyGlobal.PACKET_BLOBS), ints(in, PsyGlobal.PACKET_BLOBS),
                        floats(in, PsyGlobal.PACKET_BLOBS), floats(in, PsyGlobal.PACKET_BLOBS));
            }
        }
        var floorMappings = in.getInt();
        t.floorMappingList = new int[floorMappings][];
        for (var k = 0; k < floorMappings; k++) {
            t.floorMappingList[k] = ints(in, m);
        }
        var floors = in.getInt();
        t.floorParams = new Floor1Info[64];
        t.floorBooks = new int[64][];
        for (var f = 0; f < floors; f++) {
            var index = in.getInt();
            t.floorParams[index] = floor1(in);
            t.floorBooks[index] = ints(in, in.getInt());
        }
        t.maps = new MapTemplate[m];
        for (var i = 0; i < m; i++) {
            var modes = in.getInt();
            var mappings = new Mapping0Info[modes];
            for (var k = 0; k < modes; k++) {
                mappings[k] = mapping0(in);
            }
            var residues = new ResidueTemplate[in.getInt()];
            for (var r = 0; r < residues.length; r++) {
                var type = in.getInt();
                var limit = in.getInt();
                var grouping = in.getInt();
                var residue = residue0(in);
                var aux = in.getInt();
                in.getInt(); // the managed-mode class book, unused in quality mode
                var base = new int[12][];
                for (var c = 0; c < 12; c++) {
                    base[c] = ints(in, 4);
                }
                in.position(in.position() + 12 * 4 * Integer.BYTES); // the managed-mode stage books
                residues[r] = new ResidueTemplate(type, limit, grouping, residue, aux, base);
            }
            t.maps[i] = new MapTemplate(modes, mappings, residues);
        }
        return t;
    }

    /**
     * Reads a floor type 1 configuration.
     *
     * @param in the reader
     * @return the configuration
     */
    private static Floor1Info floor1(ByteBuffer in) {
        var f = new Floor1Info();
        f.partitions = in.getInt();
        read(in, f.partitionClass);
        read(in, f.classDim);
        read(in, f.classSubs);
        read(in, f.classBook);
        for (var i = 0; i < 16; i++) {
            read(in, f.classSubbook[i]);
        }
        f.mult = in.getInt();
        read(in, f.postList);
        f.maxOver = in.getFloat();
        f.maxUnder = in.getFloat();
        f.maxError = in.getFloat();
        f.twoFitWeight = in.getFloat();
        f.twoFitAttenuation = in.getFloat();
        f.n = in.getInt();
        return f;
    }

    /**
     * Reads a mapping type 0 configuration.
     *
     * @param in the reader
     * @return the configuration
     */
    private static Mapping0Info mapping0(ByteBuffer in) {
        var m = new Mapping0Info();
        m.submaps = in.getInt();
        read(in, m.channelMux);
        read(in, m.floorSubmap);
        read(in, m.residueSubmap);
        m.couplingSteps = in.getInt();
        read(in, m.couplingMagnitude);
        read(in, m.couplingAngle);
        return m;
    }

    /**
     * Reads a residue configuration.
     *
     * @param in the reader
     * @return the configuration
     */
    private static Residue0Info residue0(ByteBuffer in) {
        var r = new Residue0Info();
        r.begin = in.getInt();
        r.end = in.getInt();
        r.grouping = in.getInt();
        r.partitions = in.getInt();
        r.partVals = in.getInt();
        r.groupBook = in.getInt();
        read(in, r.secondStages);
        read(in, r.bookList);
        read(in, r.classMetric1);
        read(in, r.classMetric2);
        return r;
    }

    /**
     * Reads global psychoacoustic parameters.
     *
     * @param in the reader
     * @return the parameters
     */
    private static PsyGlobal psyGlobal(ByteBuffer in) {
        var g = new PsyGlobal();
        g.eighthOctaveLines = in.getInt();
        read(in, g.preEchoThreshold);
        read(in, g.postEchoThreshold);
        g.stretchPenalty = in.getFloat();
        g.preEchoMinEnergy = in.getFloat();
        g.ampmaxAttenuationPerSecond = in.getFloat();
        read(in, g.couplingKhz);
        read(in, g.couplingPointLimit[0]);
        read(in, g.couplingPointLimit[1]);
        read(in, g.couplingPrePointAmp);
        read(in, g.couplingPostPointAmp);
        read(in, g.slidingLowpass[0]);
        read(in, g.slidingLowpass[1]);
        return g;
    }

    /**
     * Reads per-block psychoacoustic parameters.
     *
     * @param in the reader
     * @return the parameters
     */
    private static PsyInfo psyInfo(ByteBuffer in) {
        var p = new PsyInfo();
        p.blockFlag = in.getInt();
        p.athAdjustAttenuation = in.getFloat();
        p.athMaxAttenuation = in.getFloat();
        read(in, p.toneMasterAttenuation);
        p.toneCenterBoost = in.getFloat();
        p.toneDecay = in.getFloat();
        p.toneAbsoluteLimit = in.getFloat();
        read(in, p.toneAttenuation);
        in.getInt(); // noisemaskp, which libvorbis never reads
        p.noiseMaxSuppression = in.getFloat();
        p.noiseWindowLow = in.getFloat();
        p.noiseWindowHigh = in.getFloat();
        p.noiseWindowLowMin = in.getInt();
        p.noiseWindowHighMin = in.getInt();
        p.noiseWindowFixed = in.getInt();
        for (var j = 0; j < PsyInfo.NOISE_CURVES; j++) {
            read(in, p.noiseOffset[j]);
        }
        read(in, p.noiseCompand);
        p.maxCurveDb = in.getFloat();
        p.normalP = in.getInt();
        p.normalStart = in.getInt();
        p.normalPartition = in.getInt();
        p.normalThreshold = in.getDouble();
        return p;
    }

    /**
     * Reads integers into an array.
     *
     * @param in the reader
     * @param target the array
     */
    private static void read(ByteBuffer in, int[] target) {
        for (var i = 0; i < target.length; i++) {
            target[i] = in.getInt();
        }
    }

    /**
     * Reads floats into an array.
     *
     * @param in the reader
     * @param target the array
     */
    private static void read(ByteBuffer in, float[] target) {
        for (var i = 0; i < target.length; i++) {
            target[i] = in.getFloat();
        }
    }

    /**
     * Reads integers.
     *
     * @param in the reader
     * @param count the number of values
     * @return the values
     */
    private static int[] ints(ByteBuffer in, int count) {
        var values = new int[count];
        read(in, values);
        return values;
    }

    /**
     * Reads floats.
     *
     * @param in the reader
     * @param count the number of values
     * @return the values
     */
    private static float[] floats(ByteBuffer in, int count) {
        var values = new float[count];
        read(in, values);
        return values;
    }

    /**
     * Reads doubles.
     *
     * @param in the reader
     * @param count the number of values
     * @return the values
     */
    private static double[] doubles(ByteBuffer in, int count) {
        var values = new double[count];
        for (var i = 0; i < count; i++) {
            values[i] = in.getDouble();
        }
        return values;
    }
}
