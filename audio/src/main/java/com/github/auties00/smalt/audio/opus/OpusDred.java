/* Ports libopus 1.5.2 (src/opus_decoder.c: opus_dred_parse, opus_dred_process). Copyright Xiph.Org Foundation and
   Amazon.com, Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.dnn.Dred;

import java.util.Objects;

/**
 * Holds the Deep REDundancy (DRED) of an Opus packet: a neural, low-bitrate description of up to about one second
 * of speech that precedes the packet, which an encoder with a {@linkplain OpusEncoderOptions#dredDurationMillis() DRED
 * duration} adds to the packet's padding as an extension.
 *
 * <p>A receiver that lost packets parses the redundancy of the first packet that arrives after the gap with
 * {@link #parse}, then rebuilds each lost span with {@link OpusDecoder#recover(OpusDred, int, float[], int, int)},
 * which decodes it with the deep PLC of a decoder whose {@linkplain OpusDecoderOptions#complexity() complexity} is at
 * least 5. The bitstream is the experimental DRED of libopus 1.5.2 (extension 126, version 10): packets from other
 * DRED versions parse as carrying none.
 *
 * {@snippet :
 * var dred = new OpusDred();
 * // packets n + 1 .. n + k were lost; packet n + k + 1 arrived
 * var available = dred.parse(packet, 0, packet.length, 48000, 48000);
 * for (var lost = k; lost >= 1; lost--) {
 *     var offset = lost * 960;          // start of the lost 20 ms packet, before the received one
 *     if (offset <= available) {
 *         decoder.recover(dred, offset, pcm, 0, 960);
 *     } else {
 *         decoder.conceal(pcm, 0, 960);
 *     }
 * }
 * }
 *
 * <p>The neural decoder of the redundancy loads on the first parse that finds some. Instances are reusable and not
 * thread-safe.
 */
public final class OpusDred {
    /**
     * The parsed redundancy.
     */
    private final Dred dred = new Dred();

    /**
     * The network that turns latents into features, created on the first parse that finds redundancy.
     */
    private Dred.RdovaeDecoder decoder;

    /**
     * Receives the end of the redundancy from the parser.
     */
    private final int[] end = new int[1];

    /**
     * The number of samples of redundancy of the last parse.
     */
    private int available;

    /**
     * The sampling rate of the sample counts of the last parse.
     */
    private int sampleRate = 48000;

    /**
     * Constructs a holder without redundancy.
     */
    public OpusDred() {
    }

    /**
     * Parses and decodes the redundancy of a packet, replacing what the holder held.
     *
     * <p>Only the redundancy needed to cover {@code maxSamples} before the packet is decoded; less may be available
     * when the encoder sent less, or none when the packet has no DRED extension. The packet itself is not decoded:
     * pass it to a decoder afterwards.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @param maxSamples the largest span before the packet worth recovering, in samples at {@code sampleRate}, at
     * least 0
     * @param sampleRate the rate the sample counts refer to, normally the decoder's output rate: 8000, 12000, 16000,
     * 24000 or 48000 Hz
     * @return the number of samples before the start of the packet that the redundancy covers, 0 when it has none
     * @throws IllegalArgumentException if {@code length} is zero, {@code maxSamples} is negative or the rate is not
     * supported
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if the packet framing or its extensions are invalid
     */
    public int parse(byte[] packet, int offset, int length, int maxSamples, int sampleRate) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkFromIndexSize(offset, length, packet.length);
        if (length == 0) {
            throw new IllegalArgumentException("empty Opus packet");
        }
        if (maxSamples < 0) {
            throw new IllegalArgumentException("negative maximum DRED span: " + maxSamples);
        }
        OpusDecoderOptions.checkSampleRate("DRED sample rate", sampleRate);
        int result;
        try {
            result = dred.parse(null, packet, offset, length, maxSamples, sampleRate, end, true);
            if (result >= 0 && dred.isParsed()) {
                if (decoder == null) {
                    decoder = new Dred.RdovaeDecoder();
                }
                dred.process(decoder);
            }
        } catch (RuntimeException exception) {
            dred.clear();
            available = 0;
            throw new MediaException.Malformed("corrupt Opus DRED extension", exception);
        }
        if (result < 0) {
            dred.clear();
            available = 0;
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(result));
        }
        this.sampleRate = sampleRate;
        this.available = result;
        return result;
    }

    /**
     * {@return the number of samples of redundancy of the last parse, at its sampling rate; 0 when the holder holds
     * none}
     */
    public int availableSamples() {
        return available;
    }

    /**
     * {@return the number of samples at the end of the covered span, just before the packet, for which the encoder
     * sent no redundancy because they were silent, at the sampling rate of the last parse}
     */
    public int endSamples() {
        return dred.isProcessed() ? end[0] : 0;
    }

    /**
     * {@return the sampling rate the sample counts of the last parse refer to}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return whether the holder holds redundancy}
     */
    public boolean isEmpty() {
        return !dred.isProcessed();
    }

    /**
     * Drops the redundancy.
     */
    public void clear() {
        dred.clear();
        available = 0;
    }

    /**
     * {@return the parsed redundancy}
     */
    Dred internal() {
        return dred;
    }
}
