package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.MediaSink;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.FormatConversionProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes the compressed streams of {@link SmaltAudioFileReader} to PCM, and encodes PCM to compressed
 * streams, for Java Sound.
 *
 * <p>Decoding: the source encodings are those of {@link SmaltAudioFormats#decodableEncodings()}; the
 * target encodings are {@link AudioFormat.Encoding#PCM_SIGNED}, {@link AudioFormat.Encoding#PCM_UNSIGNED},
 * {@link AudioFormat.Encoding#PCM_FLOAT}, {@link AudioFormat.Encoding#ULAW} and
 * {@link AudioFormat.Encoding#ALAW}, in any sample size and byte order {@link SampledAudio} supports. A
 * conversion to an encoding alone produces 16-bit little-endian samples
 * ({@link AudioFormat.Encoding#PCM_FLOAT}: 32-bit; {@link AudioFormat.Encoding#PCM_UNSIGNED} and the G.711
 * encodings: 8-bit) at the stream's rate and channel count. A conversion to a full format may also change
 * the sample rate and the channel count: Smalt's {@link AudioReader} resamples and remixes while decoding.
 * The decoded stream's length is {@link AudioSystem#NOT_SPECIFIED}: it ends when the decoder does.
 *
 * <p>Encoding: the source encodings are the PCM and G.711 encodings {@link SampledAudio} reads, at a
 * positive whole sample rate; the target encodings are those of
 * {@link SmaltAudioFormats#encodableEncodings()}. The encoded stream's bytes are a complete file of the
 * codec, written as they are read: Opus and Vorbis in Ogg, native FLAC (without the sample count and
 * MD5 signature its header holds when written to a file), MP3, AAC in ADTS framing, AMR-NB and AMR-WB in
 * an AMR file, and AC-3 and E-AC-3 elementary streams. Its format is the codec's encoding at the coded
 * sample rate and channel count, and its length is {@link AudioSystem#NOT_SPECIFIED}. A conversion to an
 * encoding alone codes the stream's rate and channel count, or the nearest the codec can code (48 kHz,
 * stereo; AMR codes mono at 8 or 16 kHz); a conversion to a full format codes the rate and channel count it
 * names. Such a stream decodes again through this provider, and {@link SmaltAudioFileWriter} copies it
 * into a file of its type unchanged.
 *
 * <p>Closing a converted stream closes its source stream.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation decodes a stream created by {@link SmaltAudioFileReader} straight
 * from the file it reads; any other stream with a Smalt encoding is read to its end into memory
 * first, because the demuxers need random access.
 */
public final class SmaltFormatConversionProvider extends FormatConversionProvider {
    /**
     * The encodings compressed streams decode to.
     */
    private static final AudioFormat.Encoding[] PCM = {AudioFormat.Encoding.PCM_SIGNED,
            AudioFormat.Encoding.PCM_UNSIGNED, AudioFormat.Encoding.PCM_FLOAT, AudioFormat.Encoding.ULAW,
            AudioFormat.Encoding.ALAW};

    /**
     * Constructs the provider.
     */
    public SmaltFormatConversionProvider() {
    }

    @Override
    public AudioFormat.Encoding[] getSourceEncodings() {
        var encodings = new LinkedHashSet<>(SmaltAudioFormats.decodableEncodings());
        if (!SmaltAudioFormats.encodableEncodings().isEmpty()) {
            encodings.addAll(List.of(PCM));
        }
        return encodings.toArray(AudioFormat.Encoding[]::new);
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings() {
        var encodings = new LinkedHashSet<AudioFormat.Encoding>();
        if (!SmaltAudioFormats.decodableEncodings().isEmpty()) {
            encodings.addAll(List.of(PCM));
        }
        encodings.addAll(SmaltAudioFormats.encodableEncodings());
        return encodings.toArray(AudioFormat.Encoding[]::new);
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        if (isCompressedSource(sourceFormat)) {
            return PCM.clone();
        }
        if (isPcmSource(sourceFormat)) {
            return SmaltAudioFormats.encodableEncodings().toArray(AudioFormat.Encoding[]::new);
        }
        return new AudioFormat.Encoding[0];
    }

    @Override
    public boolean isConversionSupported(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        if (isCompressedSource(sourceFormat)) {
            return isPcm(targetEncoding);
        }
        return isPcmSource(sourceFormat) && encodable(targetEncoding).isPresent();
    }

    @Override
    public boolean isConversionSupported(AudioFormat targetFormat, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetFormat, "targetFormat");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        if (isCompressedSource(sourceFormat)) {
            try {
                resolve(targetFormat, sourceFormat);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return isPcmSource(sourceFormat) && codedFormat(targetFormat, sourceFormat).isPresent();
    }

    /**
     * {@inheritDoc}
     *
     * <p>For a compressed source the formats are at the source's sample rate and channel count, in every
     * sample size and byte order of the encoding. For a PCM source the only format is the encoding at the
     * sample rate and channel count the codec codes the source in.
     */
    @Override
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        if (isPcmSource(sourceFormat)) {
            var target = new AudioFormat(targetEncoding, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED,
                    AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, false);
            return codedFormat(target, sourceFormat).stream().toArray(AudioFormat[]::new);
        }
        if (!isCompressedSource(sourceFormat) || !isPcm(targetEncoding)) {
            return new AudioFormat[0];
        }
        var rate = sourceFormat.getSampleRate();
        var channels = sourceFormat.getChannels();
        var formats = new ArrayList<AudioFormat>();
        for (var bits : sizes(targetEncoding)) {
            for (var bigEndian : bits == 8 ? new boolean[]{false} : new boolean[]{false, true}) {
                formats.add(new AudioFormat(targetEncoding, rate, bits, channels,
                        channels == AudioSystem.NOT_SPECIFIED ? AudioSystem.NOT_SPECIFIED
                                : channels * SampledAudio.containerBytes(bits),
                        rate, bigEndian));
            }
        }
        return formats.toArray(AudioFormat[]::new);
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat.Encoding targetEncoding, AudioInputStream sourceStream) {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        Objects.requireNonNull(sourceStream, "sourceStream");
        if (!isConversionSupported(targetEncoding, sourceStream.getFormat())) {
            throw new IllegalArgumentException("unsupported conversion from " + sourceStream.getFormat() + " to "
                    + targetEncoding);
        }
        if (isPcmSource(sourceStream.getFormat())) {
            return encode(targetEncoding, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, sourceStream);
        }
        var bits = sizes(targetEncoding).getFirst();
        var target = new AudioFormat(targetEncoding, AudioSystem.NOT_SPECIFIED, bits, AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, false);
        return getAudioInputStream(target, sourceStream);
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat, AudioInputStream sourceStream) {
        Objects.requireNonNull(targetFormat, "targetFormat");
        Objects.requireNonNull(sourceStream, "sourceStream");
        var sourceFormat = sourceStream.getFormat();
        if (isPcmSource(sourceFormat)) {
            if (encodable(targetFormat.getEncoding()).isEmpty()) {
                throw new IllegalArgumentException("unsupported conversion from " + sourceFormat + " to "
                        + targetFormat);
            }
            return encode(targetFormat.getEncoding(), rateOf(targetFormat), targetFormat.getChannels(), sourceStream);
        }
        if (!isCompressedSource(sourceFormat)) {
            throw new IllegalArgumentException("unsupported source format " + sourceFormat);
        }
        var target = resolve(targetFormat, sourceFormat);
        var rate = Math.round(target.getSampleRate());
        var channels = target.getChannels();
        var resample = rate != Math.round(sourceFormat.getSampleRate());
        var remix = channels != sourceFormat.getChannels();
        var builder = AudioReaderOptions.builder();
        if (resample) {
            builder.sampleRate(rate);
        }
        if (remix) {
            builder.layout(ChannelLayout.defaultFor(channels));
        }
        var options = builder.build();
        try {
            AudioReader reader;
            if (sourceStream instanceof EncodedAudioInputStream encoded) {
                reader = encoded.takeReader();
                if (reader != null && (resample || remix)) {
                    reader.close();
                    reader = null;
                }
                if (reader == null) {
                    reader = AudioReader.open(encoded.source(), options);
                }
            } else {
                reader = AudioReader.open(MediaSource.of(sourceStream.readAllBytes()), options);
            }
            return new AudioInputStream(new DecodingInputStream(reader, target, sourceStream), target,
                    AudioSystem.NOT_SPECIFIED);
        } catch (MediaException exception) {
            throw new IllegalArgumentException("cannot decode the stream: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Opens a stream that encodes a PCM stream.
     *
     * @param encoding the compressed encoding
     * @param rate the coded sample rate, or {@link AudioSystem#NOT_SPECIFIED}
     * @param channels the coded channel count, or {@link AudioSystem#NOT_SPECIFIED}
     * @param source the PCM stream
     * @return the encoded stream
     * @throws IllegalArgumentException if the codec cannot code the stream at that rate and channel count
     */
    private static AudioInputStream encode(AudioFormat.Encoding encoding, int rate, int channels,
                                           AudioInputStream source) {
        var target = encodable(encoding).orElseThrow();
        try {
            return EncodingAudioInputStream.open(source, target, rate, channels);
        } catch (MediaException exception) {
            throw new IllegalArgumentException("cannot encode the stream: " + exception.getMessage(), exception);
        }
    }

    /**
     * Returns the format a PCM source is coded in for a requested compressed format.
     *
     * @param target the requested format, whose rate and channel count may be unspecified
     * @param source the PCM format
     * @return the coded format, or empty when the encoding is not encodable or the codec rejects the
     * format
     */
    private static Optional<AudioFormat> codedFormat(AudioFormat target, AudioFormat source) {
        var file = encodable(target.getEncoding());
        if (file.isEmpty()) {
            return Optional.empty();
        }
        var probe = MediaSink.memory();
        try (var encoder = file.get().open(source, rateOf(target), target.getChannels(), probe)) {
            var track = encoder.writer().track();
            return Optional.of(new AudioFormat(target.getEncoding(), track.sampleRate(), AudioSystem.NOT_SPECIFIED,
                    track.layout().orElseThrow().channels(), AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED,
                    false));
        } catch (IllegalArgumentException | MediaException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the sample rate a format asks for.
     *
     * @param format the requested format
     * @return the rate, or {@link AudioSystem#NOT_SPECIFIED} when unspecified
     * @throws IllegalArgumentException if the rate is specified and not a positive whole number
     */
    private static int rateOf(AudioFormat format) {
        var rate = format.getSampleRate();
        if (rate == AudioSystem.NOT_SPECIFIED) {
            return AudioSystem.NOT_SPECIFIED;
        }
        if (rate <= 0 || rate != Math.rint(rate)) {
            throw new IllegalArgumentException("unsupported sample rate " + rate);
        }
        return Math.round(rate);
    }

    /**
     * Returns the codec and container a compressed encoding is encoded to.
     *
     * @param encoding the requested encoding
     * @return the target, or empty when the encoding is not one of
     * {@link SmaltAudioFormats#encodableEncodings()}
     */
    private static Optional<EncodedFiles.Target> encodable(AudioFormat.Encoding encoding) {
        return SmaltAudioFormats.codecOf(encoding).flatMap(codec -> EncodedFiles.streamContainerOf(codec)
                .flatMap(container -> EncodedFiles.target(container, codec)));
    }

    /**
     * Completes a target format with the source's values and checks it.
     *
     * @param target the requested format; unspecified rate, channels and sample size are taken
     * from the source or the encoding's default size
     * @param source the compressed format
     * @return the complete target format
     * @throws IllegalArgumentException if the target is not a supported PCM format
     */
    private static AudioFormat resolve(AudioFormat target, AudioFormat source) {
        var encoding = target.getEncoding();
        if (!isPcm(encoding)) {
            throw new IllegalArgumentException("unsupported target encoding " + encoding);
        }
        var rate = target.getSampleRate() == AudioSystem.NOT_SPECIFIED ? source.getSampleRate() : target.getSampleRate();
        if (rate <= 0 || rate != Math.rint(rate)) {
            throw new IllegalArgumentException("unsupported sample rate " + rate);
        }
        var channels = target.getChannels() == AudioSystem.NOT_SPECIFIED ? source.getChannels() : target.getChannels();
        var bits = target.getSampleSizeInBits() == AudioSystem.NOT_SPECIFIED
                ? sizes(encoding).getFirst()
                : target.getSampleSizeInBits();
        if (channels <= 0 || bits <= 0) {
            throw new IllegalArgumentException("unsupported channel count " + channels + " or sample size " + bits);
        }
        if (target.getFrameRate() != AudioSystem.NOT_SPECIFIED && target.getFrameRate() != rate) {
            throw new IllegalArgumentException("the frame rate must equal the sample rate");
        }
        var resolved = new AudioFormat(encoding, rate, bits, channels, channels * SampledAudio.containerBytes(bits),
                rate, target.isBigEndian());
        if (!SampledAudio.isSupported(resolved)
                || (target.getFrameSize() != AudioSystem.NOT_SPECIFIED && target.getFrameSize() != resolved.getFrameSize())) {
            throw new IllegalArgumentException("unsupported PCM format " + target);
        }
        return resolved;
    }

    /**
     * Returns the sample sizes of a decoding target encoding, the default first.
     *
     * @param encoding a PCM or G.711 encoding
     * @return the sizes in bits
     */
    private static List<Integer> sizes(AudioFormat.Encoding encoding) {
        if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            return List.of(32, 64, 16);
        }
        if (AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            return List.of(8, 16, 24, 32);
        }
        if (AudioFormat.Encoding.ULAW.equals(encoding) || AudioFormat.Encoding.ALAW.equals(encoding)) {
            return List.of(8);
        }
        return List.of(16, 24, 32, 8);
    }

    /**
     * Returns whether a format is a compressed format this provider decodes.
     *
     * @param format the source audio format
     * @return {@code true} when its encoding names a codec the codec artifacts present at run
     * time decode
     */
    private static boolean isCompressedSource(AudioFormat format) {
        return SmaltAudioFormats.codecOf(format.getEncoding()).filter(AudioDecoders::isSupported).isPresent();
    }

    /**
     * Returns whether a format is a PCM format this provider encodes.
     *
     * @param format the source audio format
     * @return {@code true} for a format {@link SampledAudio} reads at a positive whole sample rate
     */
    private static boolean isPcmSource(AudioFormat format) {
        return SampledAudio.isSupported(format) && SmaltAudioFileWriter.isWholeRate(format);
    }

    /**
     * Returns whether an encoding is a PCM or G.711 encoding.
     *
     * @param encoding the encoding to check
     * @return {@code true} for the encodings compressed streams decode to
     */
    private static boolean isPcm(AudioFormat.Encoding encoding) {
        for (var pcm : PCM) {
            if (pcm.equals(encoding)) {
                return true;
            }
        }
        return false;
    }
}
