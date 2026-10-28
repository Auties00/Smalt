package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Decodes the spectral band replication (and, for a single channel element, parametric
 * stereo) of one AAC channel element: it parses the element's {@code sbr_extension_data} and
 * turns the element's core samples into output samples at the output rate.
 *
 * <p>An element always runs its QMF banks, so its output keeps the same delay whether or not a
 * frame carries extension data: frames before the first header, frames without extension data
 * and frames whose extension data is corrupt are upsampled (or, in the downsampled mode, passed
 * through the banks) without a high band. Corrupt extension data never fails the decode; the
 * element ignores it until the next valid payload.
 *
 * <p>The low delay spectral band replication of AAC-ELD runs low delay QMF banks with one QMF slot per
 * time slot, reads the low delay time grid, and takes its first header from the configuration; it has
 * no parametric stereo.
 *
 * <p>Instances are not thread-safe.
 */
public final class SbrElement {
    /**
     * The extension identifier of parametric stereo data in {@code sbr_extended_data}.
     */
    private static final int EXTENSION_PS = 2;

    /**
     * The number of core samples per frame.
     */
    private final int frameLength;

    /**
     * The output sampling rate of the SBR tool, twice the core rate, which selects the
     * frequency tables.
     */
    private final int sbrRate;

    /**
     * The number of SBR time slots per frame: 16 or 15, for the low delay tool also the frame's
     * number of QMF slots.
     */
    private final int timeSlots;

    /**
     * Whether the element runs the low delay spectral band replication of AAC-ELD.
     */
    private final boolean lowDelay;

    /**
     * The channels of the element, one or two.
     */
    private final SbrChannel[] channels;

    /**
     * The frame data of each channel.
     */
    private final SbrFrameData[] data;

    /**
     * The parametric stereo decoder, or {@code null} when the element does not decode it.
     */
    private final PsDecoder stereo;

    /**
     * The synthesis bank of the parametric stereo right channel, or {@code null}.
     */
    private final QmfSynthesis stereoSynthesis;

    /**
     * The current header.
     */
    private final SbrHeader header = new SbrHeader();

    /**
     * The header being read.
     */
    private final SbrHeader incoming = new SbrHeader();

    /**
     * The frequency tables of the current header.
     */
    private final SbrFrequencies tables = new SbrFrequencies();

    /**
     * Whether a header was received.
     */
    private boolean headerReceived;

    /**
     * Whether the current header describes valid frequency tables.
     */
    private boolean tablesValid;

    /**
     * Whether the current frame carries valid extension data.
     */
    private boolean frameData;

    /**
     * Whether the last decoded frame generated a high band.
     */
    private boolean lastActive;

    /**
     * Whether any frame carried extension data.
     */
    private boolean seen;

    /**
     * The subband matrix of each output channel, real parts, 64 subbands per slot plus six
     * slots of look-ahead.
     */
    private final float[][] matrixRe;

    /**
     * The subband matrix of each output channel, imaginary parts.
     */
    private final float[][] matrixIm;

    /**
     * The buffer holding the extension payload, bit-aligned.
     */
    private byte[] payload = new byte[64];

    /**
     * The reader over {@link #payload}, restarted for every payload.
     */
    private final AacBitReader payloadReader = new AacBitReader(payload);

    /**
     * The number of bits the last valid payload took.
     */
    private long consumed;

    /**
     * Whether the last frame synthesized each channel from its own subband matrix, without parametric stereo.
     */
    private boolean plainSynthesis;

    /**
     * Creates the decoder of one channel element.
     *
     * @param channelCount the element's channels: 1 for a single channel or LFE element, 2
     * for a channel pair
     * @param coreRate the core sampling rate
     * @param frameLength the number of core samples per frame, 1024 or 960
     * @param downsampled whether the output stays at the core rate (32-band synthesis)
     * instead of twice it
     * @param parametricStereo whether a single channel element decodes parametric stereo into
     * two output channels
     */
    public SbrElement(int channelCount, int coreRate, int frameLength, boolean downsampled,
                      boolean parametricStereo) {
        this(channelCount, coreRate, frameLength, downsampled, parametricStereo, false);
    }

    /**
     * Creates the decoder of one channel element, optionally with the low delay spectral band
     * replication of AAC-ELD.
     *
     * @param channelCount the element's channels: 1 for a single channel or LFE element, 2
     * for a channel pair
     * @param coreRate the core sampling rate
     * @param frameLength the number of core samples per frame: 1024 or 960, or 512 or 480 for the low
     * delay tool
     * @param downsampled whether the output stays at the core rate (32-band synthesis)
     * instead of twice it
     * @param parametricStereo whether a single channel element decodes parametric stereo into
     * two output channels; ignored by the low delay tool
     * @param lowDelay whether the element runs the low delay tool
     */
    public SbrElement(int channelCount, int coreRate, int frameLength, boolean downsampled,
                      boolean parametricStereo, boolean lowDelay) {
        this.frameLength = frameLength;
        this.sbrRate = 2 * coreRate;
        this.lowDelay = lowDelay;
        this.timeSlots = lowDelay ? frameLength / 32 : frameLength / 64;
        var bands = downsampled ? 32 : 64;
        this.channels = new SbrChannel[channelCount];
        this.data = new SbrFrameData[channelCount];
        for (var c = 0; c < channelCount; c++) {
            channels[c] = new SbrChannel(frameLength, bands, SbrKernels.INSTANCE, lowDelay);
            data[c] = new SbrFrameData();
        }
        var stereoOutput = channelCount == 1 && parametricStereo && !lowDelay;
        this.stereo = stereoOutput ? new PsDecoder() : null;
        this.stereoSynthesis = stereoOutput ? new QmfSynthesis(bands, SbrKernels.INSTANCE) : null;
        var outputs = stereoOutput ? 2 : channelCount;
        var size = (frameLength / 32 + SbrChannel.HIGH_LEAD) * 64;
        this.matrixRe = new float[outputs][size];
        this.matrixIm = new float[outputs][size];
    }

    /**
     * Returns the number of output samples per channel and frame.
     *
     * @return twice the frame length, or the frame length in the downsampled mode
     */
    public int outputLength() {
        return channels[0].synthesis.bands() * (frameLength / 32);
    }

    /**
     * Returns whether any frame carried extension data.
     *
     * @return {@code true} once the element received spectral band replication data
     */
    public boolean seen() {
        return seen;
    }

    /**
     * Takes the header of the element from a configuration, as AAC-ELD carries the first header of
     * each element in its {@code ld_sbr_header}.
     *
     * @param config the configuration bytes
     * @param bitOffset the bit offset of the {@code sbr_header}
     */
    public void configure(byte[] config, int bitOffset) {
        var reader = new AacBitReader(config);
        reader.skip(bitOffset);
        incoming.read(reader);
        header.copy(incoming);
        tablesValid = tables.update(header, sbrRate);
        for (var channel : channels) {
            channel.reset();
        }
        headerReceived = true;
    }

    /**
     * Parses the low delay extension payload of an AAC-ELD frame, which follows the channel elements
     * without a length.
     *
     * <p>The payload is read from the bits {@code reader} has left, up to {@code available}; the reader
     * advances past the payload, or by {@code available} bits when the payload does not parse, which
     * leaves the frame without extension data.
     *
     * @param reader the reader positioned at the payload
     * @param available the number of bits the payload may take
     * @param crc whether the payload starts with a CRC
     */
    public void parseLowDelay(AacBitReader reader, long available, boolean crc) {
        var start = reader.bitPosition();
        parse(reader, available, crc);
        if (frameData) {
            reader.seek(start + consumed);
        }
    }

    /**
     * Parses one extension payload, the content of a fill element of type
     * {@code EXT_SBR_DATA} or {@code EXT_SBR_DATA_CRC} after its type.
     *
     * <p>The payload is copied out of {@code reader}, which always advances by {@code bits}. A
     * payload that does not parse leaves the frame without extension data, so that the frame
     * decodes with the high band of the core alone, as a corrupt extension must not fail the
     * core.
     *
     * @param reader the reader positioned at the payload
     * @param bits the number of payload bits
     * @param crc whether the payload starts with a CRC
     */
    public void parse(AacBitReader reader, long bits, boolean crc) {
        var bytes = (int) ((bits + 7) >>> 3);
        if (payload.length < bytes) {
            payload = new byte[bytes];
        }
        for (var i = 0; i < bytes; i++) {
            var take = (int) Math.min(8, bits - 8L * i);
            payload[i] = (byte) (reader.read(take) << (8 - take));
        }
        seen = true;
        frameData = false;
        try {
            payloadReader.reset(payload, 0, bytes);
            frameData = read(payloadReader, bits, crc);
        } catch (MediaException exception) {
            frameData = false;
        }
        if (!frameData && stereo != null) {
            stereo.discard();
        }
    }

    /**
     * Reads the extension payload.
     *
     * @param reader the payload reader
     * @param bits the number of payload bits
     * @param crc whether the payload starts with a CRC
     * @return {@code true} when the payload holds valid frame data
     * @throws MediaException.Malformed if the payload is corrupt
     */
    private boolean read(AacBitReader reader, long bits, boolean crc) {
        if (crc) {
            reader.read(10);
        }
        var headerFlag = reader.readFlag();
        if (headerFlag) {
            incoming.read(reader);
            if (!headerReceived || !incoming.sameTables(header)) {
                header.copy(incoming);
                tablesValid = tables.update(header, sbrRate);
                for (var channel : channels) {
                    channel.reset();
                }
            } else {
                header.copy(incoming);
            }
            headerReceived = true;
        }
        if (!headerReceived || !tablesValid) {
            return false;
        }
        if (channels.length == 1) {
            readSingle(reader);
        } else {
            readPair(reader);
        }
        if (reader.bitPosition() > bits) {
            throw new MediaException.Malformed("SBR extension payload overrun");
        }
        consumed = reader.bitPosition();
        return true;
    }

    /**
     * Reads {@code sbr_single_channel_element}.
     *
     * @param reader the payload reader
     */
    private void readSingle(AacBitReader reader) {
        if (reader.readFlag()) {
            reader.read(4);
        }
        var channel = data[0];
        readGrid(channel, reader);
        channel.readDirections(reader);
        channel.readInverseFiltering(reader, tables.noiseBands);
        channel.readEnvelope(reader, tables, header.ampResolution, false);
        channel.readNoise(reader, tables.noiseBands, false);
        channel.readHarmonics(reader, tables.highBands);
        readExtended(reader);
    }

    /**
     * Reads {@code sbr_channel_pair_element}.
     *
     * @param reader the payload reader
     */
    private void readPair(AacBitReader reader) {
        if (reader.readFlag()) {
            reader.read(8);
        }
        var left = data[0];
        var right = data[1];
        if (reader.readFlag()) {
            readGrid(left, reader);
            right.copyGrid(left);
            left.readDirections(reader);
            right.readDirections(reader);
            left.readInverseFiltering(reader, tables.noiseBands);
            right.copyInverseFiltering(left);
            left.readEnvelope(reader, tables, header.ampResolution, false);
            left.readNoise(reader, tables.noiseBands, false);
            right.readEnvelope(reader, tables, header.ampResolution, true);
            right.readNoise(reader, tables.noiseBands, true);
        } else {
            readGrid(left, reader);
            readGrid(right, reader);
            left.readDirections(reader);
            right.readDirections(reader);
            left.readInverseFiltering(reader, tables.noiseBands);
            right.readInverseFiltering(reader, tables.noiseBands);
            left.readEnvelope(reader, tables, header.ampResolution, false);
            right.readEnvelope(reader, tables, header.ampResolution, false);
            left.readNoise(reader, tables.noiseBands, false);
            right.readNoise(reader, tables.noiseBands, false);
        }
        left.readHarmonics(reader, tables.highBands);
        right.readHarmonics(reader, tables.highBands);
        readExtended(reader);
    }

    /**
     * Reads the time grid of a channel: {@code sbr_grid}, or {@code sbr_ld_grid} for the low delay tool.
     *
     * @param channel the channel's frame data
     * @param reader the payload reader
     */
    private void readGrid(SbrFrameData channel, AacBitReader reader) {
        if (lowDelay) {
            channel.readLowDelayGrid(reader, timeSlots);
        } else {
            channel.readGrid(reader, timeSlots);
        }
    }

    /**
     * Reads {@code sbr_extended_data}, handing parametric stereo data to the decoder and
     * skipping every other extension.
     *
     * @param reader the payload reader
     */
    private void readExtended(AacBitReader reader) {
        if (!reader.readFlag()) {
            return;
        }
        var count = reader.read(4);
        if (count == 15) {
            count += reader.read(8);
        }
        var left = 8L * count;
        var end = reader.bitPosition() + left;
        while (left > 7) {
            var id = reader.read(2);
            left -= 2;
            if (id == EXTENSION_PS && stereo != null) {
                left -= stereo.read(reader, left);
                if (left < 0) {
                    stereo.discard();
                    throw new MediaException.Malformed("parametric stereo data overrun");
                }
            } else {
                reader.skip(left);
                left = 0;
            }
        }
        var rest = end - reader.bitPosition();
        if (rest < 0) {
            throw new MediaException.Malformed("SBR extended data overrun");
        }
        reader.skip(rest);
    }

    /**
     * Prepares the next frame to conceal a lost one: when {@code audible} is set and the last frame had a high
     * band, the frame repeats the last envelope, 6 dB lower when {@code attenuate} is set, with the last noise
     * floors and inverse filtering; otherwise the frame has no high band.
     *
     * @param audible whether the concealed frame is heard
     * @param attenuate whether the envelope falls by 6 dB
     */
    public void conceal(boolean audible, boolean attenuate) {
        frameData = false;
        if (!audible || !headerReceived || !tablesValid || !lastActive) {
            return;
        }
        var coupled = channels.length == 2 && data[1].balance;
        for (var c = 0; c < channels.length; c++) {
            data[c].conceal(channels[c], tables, timeSlots, coupled && c == 1, attenuate);
        }
        frameData = true;
    }

    /**
     * Decodes one frame of the element.
     *
     * @param core the core samples of each of the element's channels
     * @param out the destination of each output channel: one per element channel, or two
     * for a single channel element with parametric stereo
     * @param stereoActive whether a single channel element with parametric stereo outputs two
     * channels in this frame
     * @param gains the dynamic range control gains of each of the element's channels, 64 per
     * slot for the frame's slots and six slots of look-ahead, or {@code null} for none
     */
    public void decode(float[][] core, float[][] out, boolean stereoActive, float[][] gains) {
        for (var c = 0; c < channels.length; c++) {
            channels[c].analyze(core[c]);
        }
        var active = frameData && headerReceived && tablesValid;
        if (active) {
            for (var c = 0; c < channels.length; c++) {
                active &= channels[c].resolve(data[c], tables);
            }
        }
        if (active) {
            dequantize();
            for (var c = 0; c < channels.length; c++) {
                channels[c].generate(data[c], tables, header);
            }
        }
        var slots = frameLength / 32;
        var withStereo = stereo != null && stereoActive && stereo.seen();
        for (var c = 0; c < channels.length; c++) {
            if (withStereo) {
                channels[c].assemble(active, tables.kx, matrixRe[c], matrixIm[c], SbrChannel.HIGH_LEAD);
            } else {
                channels[c].assemble(active, tables.kx, channels[c].highRe, channels[c].highIm, 0);
            }
        }
        if (gains != null) {
            for (var c = 0; c < channels.length; c++) {
                if (withStereo) {
                    scale(matrixRe[c], matrixIm[c], gains[c], slots, SbrChannel.HIGH_LEAD);
                } else {
                    scale(channels[c].highRe, channels[c].highIm, gains[c], slots, 0);
                }
            }
        }
        plainSynthesis = !withStereo;
        if (withStereo) {
            var usb = active ? tables.kx + tables.m : 32;
            stereo.process(matrixRe[0], matrixIm[0], matrixRe[1], matrixIm[1], slots, usb);
            channels[0].synthesis.synthesize(matrixRe[0], matrixIm[0], slots, out[0], 0);
            stereoSynthesis.synthesize(matrixRe[1], matrixIm[1], slots, out[1], 0);
        } else {
            for (var c = 0; c < channels.length; c++) {
                channels[c].synthesis.synthesize(channels[c].highRe, channels[c].highIm, slots, out[c], 0);
            }
            if (stereo != null && stereoActive) {
                System.arraycopy(out[0], 0, out[1], 0, outputLength());
            }
        }
        for (var channel : channels) {
            channel.finish(active, tables.kx, tables.kx + tables.m);
        }
        lastActive = active;
        frameData = false;
    }

    /**
     * {@return whether the last frame synthesized every channel from its own subband matrix, so
     * {@link #subbandsReal(int)} and {@link #subbandsImag(int)} hold the frame, which is the case without
     * parametric stereo}
     */
    public boolean subbandsAvailable() {
        return plainSynthesis;
    }

    /**
     * Returns the real parts of the subband matrix the last frame synthesized for a channel of the element.
     *
     * <p>The matrix holds 64 subbands per slot, after the dynamic range control gains, in the scale of the
     * synthesis input (a full-scale 16-bit signal spans {@code [-32768, 32768]}); it is valid until the next
     * frame and must not be modified.
     *
     * @param channel the channel of the element
     * @return the matrix, the frame's slots first
     */
    public float[] subbandsReal(int channel) {
        return channels[channel].highRe;
    }

    /**
     * Returns the imaginary parts of the subband matrix the last frame synthesized for a channel of the element.
     *
     * @param channel the channel of the element
     * @return the matrix, the frame's slots first
     * @see #subbandsReal(int)
     */
    public float[] subbandsImag(int channel) {
        return channels[channel].highIm;
    }

    /**
     * Scales a subband matrix by the dynamic range control gains, ISO/IEC 14496-3 subclause
     * 4.5.2.7.5.
     *
     * @param re the real parts, 64 subbands per slot
     * @param im the imaginary parts
     * @param gains the gain of each subband of each slot
     * @param slots the number of slots of the frame
     * @param lookahead the number of following slots whose five lowest subbands are scaled too
     */
    private static void scale(float[] re, float[] im, float[] gains, int slots, int lookahead) {
        for (var slot = 0; slot < slots + lookahead; slot++) {
            var bands = slot < slots ? 64 : 5;
            var row = slot * 64;
            for (var k = 0; k < bands; k++) {
                re[row + k] *= gains[row + k];
                im[row + k] *= gains[row + k];
            }
        }
    }

    /**
     * Dequantizes the resolved scalefactors of every channel into envelope energies and noise
     * floor ratios as ISO/IEC 14496-3 specifies, combining the level and balance
     * channels of a coupled pair.
     */
    private void dequantize() {
        if (channels.length == 2 && data[1].balance) {
            var level = data[0];
            var balance = data[1];
            var a = level.ampResolution == 0 ? 2.0 : 1.0;
            var pan = a == 2 ? 24 : 12;
            for (var l = 0; l < level.envelopes; l++) {
                var bands = tables.bands(level.resolution[l]);
                for (var k = 0; k < bands; k++) {
                    var base = 64 * StrictMath.pow(2, level.envelope[l][k] / a + 1);
                    var right = balance.envelope[l][k];
                    channels[0].envelopeEnergy[l][k] = base / (1 + StrictMath.pow(2, (pan - right) / a));
                    channels[1].envelopeEnergy[l][k] = base / (1 + StrictMath.pow(2, (right - pan) / a));
                }
            }
            for (var l = 0; l < level.noiseEnvelopes; l++) {
                for (var k = 0; k < tables.noiseBands; k++) {
                    var base = StrictMath.pow(2, 6 - level.noise[l][k] + 1);
                    var right = balance.noise[l][k];
                    channels[0].noiseFloor[l][k] = base / (1 + StrictMath.pow(2, 12 - right));
                    channels[1].noiseFloor[l][k] = base / (1 + StrictMath.pow(2, right - 12));
                }
            }
            return;
        }
        for (var c = 0; c < channels.length; c++) {
            var frame = data[c];
            var a = frame.ampResolution == 0 ? 2.0 : 1.0;
            for (var l = 0; l < frame.envelopes; l++) {
                var bands = tables.bands(frame.resolution[l]);
                for (var k = 0; k < bands; k++) {
                    channels[c].envelopeEnergy[l][k] = 64 * StrictMath.pow(2, frame.envelope[l][k] / a);
                }
            }
            for (var l = 0; l < frame.noiseEnvelopes; l++) {
                for (var k = 0; k < tables.noiseBands; k++) {
                    channels[c].noiseFloor[l][k] = StrictMath.pow(2, 6 - frame.noise[l][k]);
                }
            }
        }
    }
}
