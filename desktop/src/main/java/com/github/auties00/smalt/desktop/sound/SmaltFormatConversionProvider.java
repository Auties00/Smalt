package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.FormatConversionProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decodes the compressed streams of {@link SmaltAudioFileReader} to PCM for Java Sound.
 *
 * <p>The source encodings are those of {@link SmaltAudioFormats#decodableEncodings()}; the target
 * encodings are {@link AudioFormat.Encoding#PCM_SIGNED}, {@link AudioFormat.Encoding#PCM_UNSIGNED}
 * and {@link AudioFormat.Encoding#PCM_FLOAT}, in any sample size and byte order
 * {@link SampledAudio} supports. A conversion to an encoding alone produces 16-bit little-endian
 * samples ({@link AudioFormat.Encoding#PCM_FLOAT}: 32-bit; {@link AudioFormat.Encoding#PCM_UNSIGNED}:
 * 8-bit) at the stream's rate and channel count. A conversion to a full format may also change the
 * sample rate and the channel count: Smalt's {@link AudioReader} resamples and remixes while
 * decoding.
 *
 * <p>The decoded stream's length is {@link AudioSystem#NOT_SPECIFIED}: it ends when the decoder
 * does. Closing it closes the compressed stream.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation decodes a stream created by {@link SmaltAudioFileReader} straight
 * from the file it reads; any other stream with a Smalt encoding is read to its end into memory
 * first, because the demuxers need random access.
 */
public final class SmaltFormatConversionProvider extends FormatConversionProvider {
    /**
     * The target encodings.
     */
    private static final AudioFormat.Encoding[] TARGETS = {AudioFormat.Encoding.PCM_SIGNED,
            AudioFormat.Encoding.PCM_UNSIGNED, AudioFormat.Encoding.PCM_FLOAT};

    /**
     * Constructs the provider.
     */
    public SmaltFormatConversionProvider() {
    }

    @Override
    public AudioFormat.Encoding[] getSourceEncodings() {
        return SmaltAudioFormats.decodableEncodings().toArray(AudioFormat.Encoding[]::new);
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings() {
        return TARGETS.clone();
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        return isSource(sourceFormat) ? TARGETS.clone() : new AudioFormat.Encoding[0];
    }

    @Override
    public boolean isConversionSupported(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        return isSource(sourceFormat) && isTarget(targetEncoding);
    }

    @Override
    public boolean isConversionSupported(AudioFormat targetFormat, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetFormat, "targetFormat");
        if (!isSource(sourceFormat)) {
            return false;
        }
        try {
            resolve(targetFormat, sourceFormat);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The formats are at the source's sample rate and channel count, in every sample size and
     * byte order of the encoding.
     */
    @Override
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        if (!isSource(sourceFormat) || !isTarget(targetEncoding)) {
            return new AudioFormat[0];
        }
        var rate = sourceFormat.getSampleRate();
        var channels = sourceFormat.getChannels();
        var formats = new ArrayList<AudioFormat>();
        for (var bits : sizes(targetEncoding)) {
            for (var bigEndian : bits == 8 ? new boolean[]{false} : new boolean[]{false, true}) {
                formats.add(new AudioFormat(targetEncoding, rate, bits, channels,
                        channels == AudioSystem.NOT_SPECIFIED ? AudioSystem.NOT_SPECIFIED : channels * bits / 8,
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
        if (!isSource(sourceFormat)) {
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
        if (!isTarget(encoding)) {
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
        if (channels <= 0) {
            throw new IllegalArgumentException("unsupported channel count " + channels);
        }
        if (target.getFrameRate() != AudioSystem.NOT_SPECIFIED && target.getFrameRate() != rate) {
            throw new IllegalArgumentException("the frame rate must equal the sample rate");
        }
        var resolved = new AudioFormat(encoding, rate, bits, channels, channels * bits / 8, rate, target.isBigEndian());
        if (!SampledAudio.isSupported(resolved)
                || (target.getFrameSize() != AudioSystem.NOT_SPECIFIED && target.getFrameSize() != resolved.getFrameSize())) {
            throw new IllegalArgumentException("unsupported PCM format " + target);
        }
        return resolved;
    }

    /**
     * Returns the sample sizes of a target encoding, the default first.
     *
     * @param encoding a target encoding
     * @return the sizes in bits
     */
    private static List<Integer> sizes(AudioFormat.Encoding encoding) {
        if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            return List.of(32, 64);
        }
        if (AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            return List.of(8, 16, 24, 32);
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
    private static boolean isSource(AudioFormat format) {
        Objects.requireNonNull(format, "format");
        return SmaltAudioFormats.codecOf(format.getEncoding()).filter(AudioDecoders::isSupported).isPresent();
    }

    /**
     * Returns whether an encoding is a target encoding.
     *
     * @param encoding the requested encoding
     * @return {@code true} for the PCM encodings
     */
    private static boolean isTarget(AudioFormat.Encoding encoding) {
        for (var target : TARGETS) {
            if (target.equals(encoding)) {
                return true;
            }
        }
        return false;
    }
}
