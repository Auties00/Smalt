/* Ports the HRTF data sets of sac_hrtf.c of the mp4spatialdec module of the ISO/IEC 23003-3 reference software.
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). tools/testdata/mps-oracle/hrtftable.py writes the resource. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.audio.aac.AacBinauralHrtf;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * One data set of head related transfer functions, by direction: left front, right front, center, left surround and
 * right surround. Instances are immutable after loading and shared.
 */
final class MpsHrtf {
    /**
     * The number of directions of a set.
     */
    static final int DIRECTIONS = 5;
    /**
     * The number of parameter bands of a set.
     */
    static final int BANDS = 28;

    /**
     * Defers reading the resource until a binaural rendering needs it.
     */
    private static final class Sets {
        static final MpsHrtf[] VALUES = load();
    }

    /**
     * The length of the impulse responses, in samples.
     */
    final int impulseLength;
    /**
     * The left ear power of each direction and band.
     */
    final float[][] powerLeft = new float[DIRECTIONS][BANDS];
    /**
     * The right ear power of each direction and band.
     */
    final float[][] powerRight = new float[DIRECTIONS][BANDS];
    /**
     * The coherence between the ears of each direction and band.
     */
    final float[][] icc = new float[DIRECTIONS][BANDS];
    /**
     * The phase difference between the ears of each direction and band, in radians.
     */
    final float[][] ipd = new float[DIRECTIONS][BANDS];
    /**
     * The left ear impulse response of each direction.
     */
    final float[][] impulseLeft;
    /**
     * The right ear impulse response of each direction.
     */
    final float[][] impulseRight;
    /**
     * The left ear reverb response, empty for a set without reverb.
     */
    final float[] reverbLeft;
    /**
     * The right ear reverb response, empty for a set without reverb.
     */
    final float[] reverbRight;
    /**
     * The QMF domain impulse responses computed so far, by number of QMF subbands.
     */
    final ConcurrentHashMap<Integer, MpsHrtfFilterbank.Masks> masks = new ConcurrentHashMap<>();

    private MpsHrtf(DataInputStream in) throws IOException {
        this.impulseLength = in.readInt();
        var reverbLength = in.readInt();
        this.impulseLeft = new float[DIRECTIONS][impulseLength];
        this.impulseRight = new float[DIRECTIONS][impulseLength];
        for (var direction = 0; direction < DIRECTIONS; direction++) {
            read(in, powerLeft[direction]);
            read(in, powerRight[direction]);
            read(in, icc[direction]);
            read(in, ipd[direction]);
            read(in, impulseLeft[direction]);
            read(in, impulseRight[direction]);
        }
        this.reverbLeft = new float[reverbLength];
        this.reverbRight = new float[reverbLength];
        read(in, reverbLeft);
        read(in, reverbRight);
    }

    private static void read(DataInputStream in, float[] values) throws IOException {
        for (var i = 0; i < values.length; i++) {
            values[i] = in.readFloat();
        }
    }

    private static MpsHrtf[] load() {
        try (var raw = MpsHrtf.class.getResourceAsStream("mps-hrtf.bin.gz")) {
            if (raw == null) {
                throw new IllegalStateException("mps-hrtf.bin.gz is missing");
            }
            var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
            var sets = new MpsHrtf[AacBinauralHrtf.values().length];
            for (var i = 0; i < sets.length; i++) {
                sets[i] = new MpsHrtf(in);
            }
            return sets;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * {@return the data of a set}
     *
     * @param set one of the sets the resource holds
     */
    static MpsHrtf of(AacBinauralHrtf set) {
        return Sets.VALUES[set.ordinal()];
    }
}
