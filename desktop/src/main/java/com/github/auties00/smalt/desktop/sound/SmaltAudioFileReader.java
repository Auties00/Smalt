package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads compressed audio files for Java Sound through Smalt's containers and the codec artifacts
 * present at run time.
 *
 * <p>The container is recognized from the content, never from a file name: every container
 * {@link SmaltAudioFormats#fileTypeOf} names a type for (Ogg, FLAC, MPEG audio, ADTS, LOAS and ADIF,
 * MPEG-4, Matroska/WebM, AMR, AC-3 and E-AC-3, DTS, CAF, Wave64, AVI, MPEG transport and program
 * streams, ASF, FLV, MXF, DV, WavPack, Monkey's Audio, Musepack, DSF and DSDIFF), whenever the codec of
 * its first audio track is decodable ({@code smalt-audio} for Opus, Vorbis, FLAC, MP3 and the lossless
 * codecs, {@code smalt-mpeg} for AAC, AMR, AC-3 and E-AC-3), so a video file is read for its audio. Of
 * the WAVE, AIFF and AU files, only the variants the JDK's readers decline are read, and the others are
 * left to them: RF64 and BW64 files, WAVE files of compressed codings (ADPCM, MP3, GSM and the others the
 * codec artifacts decode), AIFF-C files of a compression type other than {@code NONE} and {@code ulaw}
 * (such as {@code sowt}, {@code twos}, {@code fl32}, {@code fl64}, {@code alaw} and {@code ima4}), and AU
 * files of other encodings. The {@link AudioFileFormat} reports the decoded sample rate and channel count
 * and the {@code "duration"} property in microseconds when the container declares it.
 *
 * <p>{@link #getAudioInputStream(File)} returns a stream in the codec's encoding
 * ({@link SmaltAudioFormats#encodingOf(Codec)}); convert it to PCM with
 * {@link AudioSystem#getAudioInputStream(AudioFormat.Encoding, AudioInputStream)}, which reaches
 * {@link SmaltFormatConversionProvider}:
 * {@snippet :
 * try (var encoded = AudioSystem.getAudioInputStream(new File("song.mp3"));
 *      var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
 *     // pcm is 16-bit little-endian PCM at the file's rate and channel count
 * }
 * }
 * A track whose codec is PCM is returned as PCM directly: integer samples at their stored size (8-bit
 * unsigned samples as {@link AudioFormat.Encoding#PCM_UNSIGNED}, 64-bit ones at 32 bits), float samples at
 * 32 bits, and G.711 samples as {@link AudioFormat.Encoding#ULAW} or {@link AudioFormat.Encoding#ALAW}.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation reads a {@link File} (and a {@code file:} {@link URL}) in place,
 * and reads any other {@link InputStream} or {@link URL} to its end into memory once its leading
 * bytes are recognized, because the demuxers need random access. An {@link InputStream} without
 * {@link InputStream#markSupported()} is declined as unsupported, so the other providers still see
 * it unread.
 */
public final class SmaltAudioFileReader extends AudioFileReader {
    /**
     * The PCM codecs of floating-point samples.
     */
    private static final Set<Codec> FLOAT_PCM = EnumSet.of(Codec.PCM_F32LE, Codec.PCM_F32BE, Codec.PCM_F64LE,
            Codec.PCM_F64BE);

    /**
     * How many leading bytes container detection inspects after any ID3v2 tag.
     */
    private static final int HEADER_LENGTH = 4096;

    /**
     * The largest ID3v2 tag skipped while recognizing a stream, in bytes after its header, as
     * {@link Containers} skips.
     */
    private static final int MAX_ID3_LENGTH = 64 * 1024 * 1024;

    /**
     * Constructs the reader.
     */
    public SmaltAudioFileReader() {
    }

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(stream, "stream");
        recognize(stream);
        stream.mark(Integer.MAX_VALUE);
        try {
            return describe(MediaSource.of(stream.readAllBytes()));
        } finally {
            stream.reset();
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(url, "url");
        var file = fileOf(url);
        if (file != null) {
            return getAudioFileFormat(file);
        }
        try (var stream = new BufferedInputStream(url.openStream())) {
            recognize(stream);
            return describe(MediaSource.of(stream.readAllBytes()));
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(file, "file");
        try (var source = open(file.toPath())) {
            return describe(source);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A stream this reader declines after reading it is reset to the position it was given at.
     */
    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(stream, "stream");
        recognize(stream);
        stream.mark(Integer.MAX_VALUE);
        try {
            return stream(MediaSource.of(stream.readAllBytes()));
        } catch (UnsupportedAudioFileException exception) {
            stream.reset();
            throw exception;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(url, "url");
        var file = fileOf(url);
        if (file != null) {
            return getAudioInputStream(file);
        }
        try (var stream = new BufferedInputStream(url.openStream())) {
            recognize(stream);
            return stream(MediaSource.of(stream.readAllBytes()));
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        Objects.requireNonNull(file, "file");
        return stream(open(file.toPath()));
    }

    /**
     * Opens a file as a source.
     *
     * @param path the file
     * @return the source, which the caller closes
     * @throws IOException if the file cannot be opened
     */
    private static MediaSource open(Path path) throws IOException {
        try {
            return MediaSource.of(path);
        } catch (MediaException.Io exception) {
            throw exception.getCause();
        }
    }

    /**
     * Describes a file.
     *
     * @param source the file; not closed
     * @return the file format
     * @throws UnsupportedAudioFileException if the file is not one this reader handles
     * @throws IOException if reading fails
     */
    private static AudioFileFormat describe(MediaSource source) throws UnsupportedAudioFileException, IOException {
        try (var reader = openReader(source)) {
            return fileFormat(source, reader);
        }
    }

    /**
     * Opens a file as a stream, closing the source when it is not one this reader handles.
     *
     * @param source the file; owned by the returned stream
     * @return the stream
     * @throws UnsupportedAudioFileException if the file is not one this reader handles
     * @throws IOException if reading fails
     */
    private static AudioInputStream stream(MediaSource source) throws UnsupportedAudioFileException, IOException {
        AudioReader reader = null;
        try {
            reader = openReader(source);
            var fileFormat = fileFormat(source, reader);
            var format = fileFormat.getFormat();
            if (reader.track().codec().isPcm()) {
                var decoded = new DecodingInputStream(reader, format, source::close);
                reader = null;
                return new AudioInputStream(decoded, format, AudioSystem.NOT_SPECIFIED);
            }
            var stream = new EncodedAudioInputStream(source, format, reader);
            reader = null;
            return stream;
        } catch (UnsupportedAudioFileException | IOException | RuntimeException exception) {
            if (reader != null) {
                reader.close();
            }
            source.close();
            throw exception;
        }
    }

    /**
     * Recognizes the container of a file and opens its first audio track.
     *
     * @param source the file; not closed
     * @return the reader, positioned at the start
     * @throws UnsupportedAudioFileException if the container is not one this reader handles, the file is
     * one the JDK's readers read, or its audio is missing, not decodable or corrupt
     * @throws IOException if reading fails
     */
    private static AudioReader openReader(MediaSource source) throws UnsupportedAudioFileException, IOException {
        try {
            var container = Containers.detect(source).filter(SmaltAudioFileReader::handles);
            if (container.isEmpty()) {
                throw new UnsupportedAudioFileException("not a file the Smalt audio reader handles");
            }
            if (JdkSampledFiles.readsNatively(source, container.get())) {
                throw new UnsupportedAudioFileException("a file the JDK's readers read");
            }
            return AudioReader.open(source);
        } catch (MediaException.Io exception) {
            throw exception.getCause();
        } catch (MediaException exception) {
            var failure = new UnsupportedAudioFileException(exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * Returns whether this reader handles a container.
     *
     * @param container the detected container format
     * @return {@code true} unless the container holds no audio
     */
    private static boolean handles(ContainerFormat container) {
        return SmaltAudioFormats.fileTypeOf(container, Codec.UNKNOWN_AUDIO).isPresent();
    }

    /**
     * Describes the file an open reader reads.
     *
     * @param source the file
     * @param reader the open reader, positioned at the start
     * @return the file format, whose audio format is the codec's encoding at the decoded rate and
     * channel count, or the PCM format of a PCM track
     */
    private static AudioFileFormat fileFormat(MediaSource source, AudioReader reader) {
        var codec = reader.track().codec();
        var rate = reader.sampleRate();
        var layout = reader.layout();
        AudioFormat format;
        if (codec.isPcm()) {
            format = switch (codec) {
                case PCM_MULAW -> SampledAudio.format(AudioFormat.Encoding.ULAW, rate, 8, layout, false);
                case PCM_ALAW -> SampledAudio.format(AudioFormat.Encoding.ALAW, rate, 8, layout, false);
                case PCM_U8 -> SampledAudio.format(AudioFormat.Encoding.PCM_UNSIGNED, rate, 8, layout, false);
                default -> {
                    var floating = FLOAT_PCM.contains(codec);
                    var stored = reader.track().bitsPerSample();
                    var bits = floating ? 32 : stored >= 8 && stored <= 32 ? stored : stored > 32 ? 32 : 16;
                    yield SampledAudio.format(floating ? AudioFormat.Encoding.PCM_FLOAT
                            : AudioFormat.Encoding.PCM_SIGNED, rate, bits, layout, false);
                }
            };
        } else {
            format = new AudioFormat(SmaltAudioFormats.encodingOf(codec), rate, AudioSystem.NOT_SPECIFIED,
                    layout.channels(), AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, false);
        }
        var type = reader.format() == ContainerFormat.AIFF ? JdkSampledFiles.aiffType(source)
                : SmaltAudioFormats.fileTypeOf(reader.format(), codec).orElseThrow();
        var duration = reader.durationMicros();
        var properties = duration > 0 ? Map.<String, Object>of("duration", duration) : Map.<String, Object>of();
        return new AudioFileFormat(type, format, AudioSystem.NOT_SPECIFIED, properties);
    }

    /**
     * Recognizes the leading bytes of a stream and restores its position.
     *
     * @param stream the input, whose position is marked and reset
     * @throws UnsupportedAudioFileException if the stream does not support marks, so that the next
     * provider can try it, or the bytes are not a container
     * this reader handles
     * @throws IOException if reading fails
     */
    private static void recognize(InputStream stream) throws UnsupportedAudioFileException, IOException {
        if (!stream.markSupported()) {
            throw new UnsupportedAudioFileException("the Smalt audio reader recognizes only streams that support mark");
        }
        stream.mark(HEADER_LENGTH + MAX_ID3_LENGTH + Id3v2Tag.HEADER_SIZE);
        byte[] header;
        try {
            header = stream.readNBytes(Id3v2Tag.HEADER_SIZE);
            var tagSize = Id3v2Tag.size(header, 0);
            if (tagSize >= 0) {
                var tagRest = tagSize - Id3v2Tag.HEADER_SIZE;
                if (tagRest > MAX_ID3_LENGTH) {
                    throw new UnsupportedAudioFileException("the ID3 tag is too large to recognize the stream");
                }
                var rest = stream.readNBytes((int) tagRest + HEADER_LENGTH);
                header = Arrays.copyOf(header, Id3v2Tag.HEADER_SIZE + rest.length);
                System.arraycopy(rest, 0, header, Id3v2Tag.HEADER_SIZE, rest.length);
            } else {
                var rest = stream.readNBytes(HEADER_LENGTH - header.length);
                header = Arrays.copyOf(header, header.length + rest.length);
                System.arraycopy(rest, 0, header, header.length - rest.length, rest.length);
            }
        } finally {
            stream.reset();
        }
        boolean handled;
        try {
            var leading = MediaSource.of(header);
            var container = Containers.detect(leading).filter(SmaltAudioFileReader::handles);
            handled = container.isPresent() && !JdkSampledFiles.readsNatively(leading, container.get());
        } catch (MediaException exception) {
            handled = false;
        }
        if (!handled) {
            throw new UnsupportedAudioFileException("not a stream the Smalt audio reader handles");
        }
    }

    /**
     * Returns the file a {@code file:} URL names.
     *
     * @param url the URL
     * @return the file, or {@code null} for any other URL
     */
    private static File fileOf(URL url) {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            return Path.of(url.toURI()).toFile();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }
}
