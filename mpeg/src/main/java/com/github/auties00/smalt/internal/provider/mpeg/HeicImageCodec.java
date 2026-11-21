package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.image.heic.internal.HeicFiles;
import com.github.auties00.smalt.image.heic.internal.StillDecoder;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifImageCodec;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.region.RegionOutsideImageException;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provides the HEIC decoder to the HEIF decoders of the other artifacts, for the images coded with HEVC, AVC,
 * JPEG, JPEG 2000 or as uncompressed samples.
 */
public final class HeicImageCodec implements HeifImageCodec {
    /**
     * Constructs the codec; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HeicImageCodec() {
    }

    @Override
    public ImageFormat format() {
        return ImageFormat.HEIC;
    }

    @Override
    public ImageInfo probe(MediaSource source, MediaLimits limits) {
        return guarded(() -> HeicFiles.probe(source, limits));
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return HeicDecoder.decode(source, options);
    }

    @Override
    public AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        return HeicDecoder.openAnimation(source, options);
    }

    @Override
    public PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        return HeicDecoder.openPages(source, options);
    }

    @Override
    public Optional<GainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options) {
        return HeicDecoder.decodeGainMap(source, options);
    }

    @Override
    public List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
        return HeicDecoder.auxiliaryImages(source, limits);
    }

    @Override
    public DecodedImage decodeAuxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
        return HeicDecoder.decodeAuxiliaryImage(source, index, options);
    }

    @Override
    public boolean showsSequence(HeifFile file) {
        return HeicFiles.showsSequence(file);
    }

    @Override
    public void requireDecodable(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        guarded(() -> {
            HeicFiles.requireDecodable(item);
            return null;
        });
    }

    @Override
    public ImageInfo probe(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        return guarded(() -> HeicFiles.probeItem(source, file, item, limits));
    }

    @Override
    public DecodedImage decode(MediaSource source, HeifFile file, HeifItem item, ImageDecoderOptions options) {
        return guarded(() -> HeicFiles.decodeItem(source, file, item, options));
    }

    @Override
    public Picture reconstructInput(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                                    boolean highBitDepth, int depth) {
        return StillDecoder.reconstructInput(source, file, item, limits, highBitDepth, depth);
    }

    /**
     * Runs one operation on the shared pool and translates its failures as {@link HeicDecoder} does.
     *
     * @param operation computes the result
     * @param <T> the type of the result
     * @return the result of the operation
     */
    private static <T> T guarded(Supplier<T> operation) {
        try (var _ = ParallelBands.operation()) {
            return operation.get();
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding a HEIC image");
        } catch (RegionOutsideImageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC file", exception);
        }
    }
}
