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
     * The number of SBR time slots per frame, 16 or 15.
     */
    private final int timeSlots;

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
        this.frameLength = frameLength;
        this.sbrRate = 2 * coreRate;
        this.timeSlots = frameLength / 64;
        var bands = downsampled ? 32 : 64;
        this.channels = new SbrChannel[channelCount];
        this.data = new SbrFrameData[channelCount];
        for (var c = 0; c < channelCount; c++) {
            channels[c] = new SbrChannel(frameLength, bands, SbrKernels.INSTANCE);
            data[c] = new SbrFrameData();
        }
        var stereoOutput = channelCount == 1 && parametricStereo;
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
            frameData = read(new AacBitReader(payload, 0, bytes), bits, crc);
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
        if (reader.readFlag()) {
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
        channel.readGrid(reader, timeSlots);
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
            left.readGrid(reader, timeSlots);
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
            left.readGrid(reader, timeSlots);
            right.readGrid(reader, timeSlots);
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
     * Decodes one frame of the element.
     *
     * @param core the core samples of each of the element's channels
     * @param out the destination of each output channel: one per element channel, or two
     * for a single channel element with parametric stereo
     * @param stereoActive whether a single channel element with parametric stereo outputs two
     * channels in this frame
     */
    public void decode(float[][] core, float[][] out, boolean stereoActive) {
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
        frameData = false;
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
                    var base = 64 * Math.pow(2, level.envelope[l][k] / a + 1);
                    var right = balance.envelope[l][k];
                    channels[0].envelopeEnergy[l][k] = base / (1 + Math.pow(2, (pan - right) / a));
                    channels[1].envelopeEnergy[l][k] = base / (1 + Math.pow(2, (right - pan) / a));
                }
            }
            for (var l = 0; l < level.noiseEnvelopes; l++) {
                for (var k = 0; k < tables.noiseBands; k++) {
                    var base = Math.pow(2, 6 - level.noise[l][k] + 1);
                    var right = balance.noise[l][k];
                    channels[0].noiseFloor[l][k] = base / (1 + Math.pow(2, 12 - right));
                    channels[1].noiseFloor[l][k] = base / (1 + Math.pow(2, right - 12));
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
                    channels[c].envelopeEnergy[l][k] = 64 * Math.pow(2, frame.envelope[l][k] / a);
                }
            }
            for (var l = 0; l < frame.noiseEnvelopes; l++) {
                for (var k = 0; k < tables.noiseBands; k++) {
                    channels[c].noiseFloor[l][k] = Math.pow(2, 6 - frame.noise[l][k]);
                }
            }
        }
    }
}
