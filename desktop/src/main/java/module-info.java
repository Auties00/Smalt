/**
 * Desktop integration of Smalt: ImageIO readers and writers and Java Sound providers backed by the
 * Smalt codec registries.
 *
 * <p>With this module present, {@code javax.imageio.ImageIO} reads every image format the Smalt
 * codec artifacts present at run time decode and the JDK does not (WebP still and animated, APNG,
 * ICO and CUR, animated cursors (ANI), JPEG 2000 under the names {@code jpeg2000} and {@code jp2},
 * and AVIF or HEIC once an artifact decodes them), reads TIFF under the format name {@code smalt-tiff}
 * with every page as an image index and BMP under the format name {@code smalt-bmp} with every bitmap
 * of an OS/2 bitmap array as an image index, and writes WebP, ICO, JPEG 2000, APNG (format name {@code apng}) and,
 * under the format names {@code smalt-gif}, {@code smalt-tiff} and {@code smalt-bmp}, GIF with
 * Smalt's quantizing encoder, TIFF with Smalt's LZW encoder and BMP with Smalt's encoder, with their
 * metadata, 16-bit images, rasters and (TIFF pages, icon entries, animations) sequences, and
 * {@code javax.sound.sampled.AudioSystem} reads Ogg (Opus, Vorbis, FLAC), FLAC, MP3, AAC, M4A,
 * Matroska, AC-3, E-AC-3, DTS, CAF, Wave64, WavPack and the other audio containers, the audio of video
 * files, and the WAVE, AIFF-C and AU variants the JDK's readers decline, decodes it to PCM, encodes PCM
 * to the compressed codecs, and writes Ogg Opus and Vorbis, FLAC, MP3, M4A and ADTS AAC, AMR, AC-3 and
 * E-AC-3 files. The module names no codec: add the codec artifacts ({@code smalt-image},
 * {@code smalt-audio}, {@code smalt-mpeg}, {@code smalt-video}) for the formats you want.
 * {@code BufferedImages} and {@code SampledAudio} convert between Smalt's pictures and samples and their
 * AWT and Java Sound counterparts.
 *
 * @provides javax.imageio.spi.ImageReaderSpi the readers of the formats the codec artifacts decode
 * and the JDK does not, of TIFF as {@code smalt-tiff} and of BMP as {@code smalt-bmp}
 * @provides javax.imageio.spi.ImageWriterSpi WebP, AVIF, HEIC, APNG, GIF ({@code smalt-gif}), ICO,
 * JPEG 2000, TIFF ({@code smalt-tiff}) and BMP ({@code smalt-bmp})
 * @provides javax.sound.sampled.spi.AudioFileReader the audio files and the audio of the video files the
 * codec artifacts decode, and the WAVE, AIFF-C and AU variants the JDK's readers decline
 * @provides javax.sound.sampled.spi.FormatConversionProvider decoding of those streams to PCM, and
 * encoding of PCM to Opus, Vorbis, FLAC, MP3, AAC, AMR, AC-3 and E-AC-3
 * @provides javax.sound.sampled.spi.AudioFileWriter Ogg Opus and Vorbis, FLAC, MP3, M4A and ADTS AAC, AMR,
 * AC-3 and E-AC-3
 */
module com.github.auties00.smalt.desktop {
    // Smalt core: the model and the codec registries
    requires transitive com.github.auties00.smalt;

    // ImageIO and Java Sound
    requires transitive java.desktop;

    // Providers and converters
    exports com.github.auties00.smalt.desktop.imageio;
    exports com.github.auties00.smalt.desktop.sound;

    // The ImageIO plug-ins
    provides javax.imageio.spi.ImageReaderSpi with
            com.github.auties00.smalt.desktop.imageio.SmaltImageReaderSpi,
            com.github.auties00.smalt.desktop.imageio.TiffImageReaderSpi,
            com.github.auties00.smalt.desktop.imageio.BmpImageReaderSpi;
    provides javax.imageio.spi.ImageWriterSpi with
            com.github.auties00.smalt.desktop.imageio.WebpImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.JxlImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.AvifImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.HeicImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.ApngImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.GifImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.IcoImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.Jp2ImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.TiffImageWriterSpi,
            com.github.auties00.smalt.desktop.imageio.BmpImageWriterSpi;

    // The Java Sound providers
    provides javax.sound.sampled.spi.AudioFileReader with
            com.github.auties00.smalt.desktop.sound.SmaltAudioFileReader;
    provides javax.sound.sampled.spi.FormatConversionProvider with
            com.github.auties00.smalt.desktop.sound.SmaltFormatConversionProvider;
    provides javax.sound.sampled.spi.AudioFileWriter with
            com.github.auties00.smalt.desktop.sound.SmaltAudioFileWriter;
}
