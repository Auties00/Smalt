package com.github.auties00.smalt.desktop.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.desktop.imageio.SmaltImageReaderSpi;
import com.github.auties00.smalt.desktop.sound.SampledAudio;
import com.github.auties00.smalt.desktop.sound.SmaltAudioFileReader;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Fuzz targets of the desktop providers: the ImageIO reader (images, rasters, band selections and thumbnails), the
 * EXIF thumbnail parser through the thumbnails of a WebP that carries the input as its EXIF block, the Java Sound
 * reader and decoder, and the sample conversions of {@link SampledAudio}.
 *
 * <p>The ImageIO and Java Sound entry points report a {@link MediaException} as the cause of an {@link IIOException},
 * an {@link IOException} or an {@link UnsupportedAudioFileException}; the targets unwrap it so that {@link Fuzz#check}
 * sees it, and treat a failure without one as a rejection.
 */
class DesktopFuzzTest {
    /** The most pixels an image read by the target may declare, as {@link Fuzz#LIMITS} allows. */
    private static final long MAX_PIXELS = 1L << 20;

    /** The most PCM bytes the sound target decodes. */
    private static final int MAX_PCM_BYTES = 1 << 20;

    interface Action {
        void run() throws Exception;
    }

    /** Runs a step, unwrapping the {@link MediaException} an ImageIO or Java Sound failure carries. */
    static boolean step(FuzzRun run, String name, Action action) {
        return run.step(name, () -> {
            try {
                action.run();
            } catch (IIOException | UnsupportedAudioFileException exception) {
                throw rejection(name, exception);
            } catch (IOException exception) {
                throw rejection(name, exception);
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Malformed(name + " rejected the input: " + exception.getMessage(), exception);
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new UncheckedIOException(new IOException(exception));
            }
        });
    }

    private static MediaException rejection(String name, Exception exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MediaException media) {
                return media;
            }
        }
        return new MediaException.Malformed(name + " declined the input: " + exception.getMessage());
    }

    private static void readImages(FuzzRun run, byte[] data, boolean thumbnailsOnly) throws IOException {
        ImageReader reader = new SmaltImageReaderSpi().createReaderInstance(null);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input);
            var pixels = new long[1];
            if (!step(run, "size", () -> pixels[0] = (long) reader.getWidth(0) * reader.getHeight(0))) {
                return;
            }
            if (pixels[0] > MAX_PIXELS) {
                run.skip("the image declares more than " + MAX_PIXELS + " pixels");
                return;
            }
            var thumbnails = new int[1];
            step(run, "thumbnails", () -> thumbnails[0] = reader.getNumThumbnails(0));
            for (var index = 0; index < thumbnails[0]; index++) {
                var thumbnail = index;
                step(run, "thumbnail", () -> {
                    if ((long) reader.getThumbnailWidth(0, thumbnail) * reader.getThumbnailHeight(0, thumbnail)
                            <= MAX_PIXELS) {
                        reader.readThumbnail(0, thumbnail);
                        run.output();
                    }
                });
            }
            if (thumbnailsOnly) {
                return;
            }
            step(run, "read", () -> {
                reader.read(0);
                run.output();
            });
            if (run.sampled(data, 0, 2)) {
                step(run, "raster", () -> {
                    var param = reader.getDefaultReadParam();
                    param.setSourceBands(new int[]{0});
                    param.setSourceSubsampling(2, 2, 0, 0);
                    reader.readRaster(0, param);
                    run.output();
                });
            }
            if (run.sampled(data, 1, 2)) {
                step(run, "metadata", () -> reader.getImageMetadata(0));
            }
        } finally {
            reader.dispose();
        }
    }

    @FuzzTest
    void imageio(byte[] data) throws IOException {
        var run = Fuzz.start("imageio");
        var source = MediaSource.of(data);
        if (!run.step("probe", () -> Images.probe(source))) {
            return;
        }
        readImages(run, data, false);
    }

    @FuzzTest
    void exifThumbnail(byte[] data) throws IOException {
        var run = Fuzz.start("exifThumbnail");
        if (data.length < 8 || !(data[0] == 'I' && data[1] == 'I' || data[0] == 'M' && data[1] == 'M')) {
            run.skip("not a TIFF structure");
            return;
        }
        var picture = RgbaPicture.allocate(4, 4, false);
        var file = Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.lossless().toBuilder().exif(data)
                .build());
        readImages(run, file, true);
    }

    @FuzzTest
    void sound(byte[] data) {
        var run = Fuzz.start("sound");
        var reader = new SmaltAudioFileReader();
        step(run, "decode", () -> {
            try (var encoded = reader.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)))) {
                var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded);
                var buffer = new byte[8192];
                var total = 0;
                for (var read = pcm.read(buffer); read >= 0 && total < MAX_PCM_BYTES; read = pcm.read(buffer)) {
                    total += read;
                }
                run.output();
            }
        });
    }

    @FuzzTest
    void sampled(byte[] data) {
        var run = Fuzz.start("sampled");
        if (data.length < 2) {
            run.skip("no format byte");
            return;
        }
        var choice = data[0] & 0xFF;
        var encodings = new AudioFormat.Encoding[]{AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED,
                AudioFormat.Encoding.PCM_FLOAT, AudioFormat.Encoding.ULAW, AudioFormat.Encoding.ALAW};
        var encoding = encodings[choice % encodings.length];
        var bits = switch (choice % encodings.length) {
            case 2 -> new int[]{16, 32, 64}[(choice >> 3) % 3];
            case 3, 4 -> 8;
            default -> 8 + (choice >> 3) % 25;
        };
        var channels = 1 + (data[1] & 3);
        var format = new AudioFormat(encoding, 8000, bits, channels, channels * ((bits + 7) / 8), 8000,
                (choice & 4) != 0);
        var bytes = Arrays.copyOfRange(data, 2, data.length);
        run.step("convert", () -> {
            var frames = bytes.length / format.getFrameSize();
            var planes = new float[channels][frames];
            SampledAudio.read(bytes, 0, frames, format, planes, 0);
            var again = new byte[frames * format.getFrameSize()];
            SampledAudio.write(planes, 0, frames, format, again, 0);
            run.output();
        });
    }
}
