package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.List;

/**
 * Resolves the settings of a transcode into a raw DV stream ({@link OutputContainer#DV}): the DV format, and the video
 * and audio settings that format imposes, as {@link VideoTranscodeOptions#dvFormat()} describes them.
 */
final class DvOutput {
    /**
     * The frame rates of the 50 Hz systems, halved or doubled into the range of 17.5 to 35 frames per second.
     */
    private static final double[] FIFTY_HZ = {25};

    /**
     * The frame rates of the 60 Hz systems, halved or doubled into the range of 17.5 to 35 frames per second.
     */
    private static final double[] SIXTY_HZ = {24000 / 1001.0, 24, 30000 / 1001.0, 30};

    private DvOutput() {
        throw new AssertionError();
    }

    /**
     * Returns the DV format a video stream is encoded in.
     *
     * @param settings the video settings
     * @param track the input track
     * @param speed the playback speed of the transcode
     * @return the format the settings name, else the one picked from the displayed picture and the output rate
     */
    static DvFormat resolve(VideoTranscodeOptions settings, VideoTrack track, Rational speed) {
        if (settings.dvFormat().isPresent()) {
            return settings.dvFormat().get();
        }
        var swaps = track.orientation().swapsDimensions();
        var height = settings.crop().map(region -> region.height()).orElse(swaps ? track.width() : track.height());
        Rational rate = settings.frameRate().orElse(null);
        if (rate == null && track.frameRate().isPresent()) {
            var source = track.frameRate().get();
            rate = new Rational(Math.multiplyExact(source.numerator(), speed.numerator()),
                    Math.multiplyExact(source.denominator(), speed.denominator()));
        }
        var fifty = settings.pulldown() != Pulldown.TELECINE && rate != null && fiftyHz(rate.toDouble());
        if (height >= 900) {
            return fifty ? DvFormat.DVCPRO_HD_1080I50 : DvFormat.DVCPRO_HD_1080I60;
        }
        if (height >= 650) {
            return fifty ? DvFormat.DVCPRO_HD_720P50 : DvFormat.DVCPRO_HD_720P60;
        }
        return fifty ? DvFormat.DV_625 : DvFormat.DV_525;
    }

    /**
     * {@return whether a frame rate, halved or doubled into the range of 17.5 to 35 frames per second, is nearer to
     * 25 frames per second than to a rate of the 60 Hz systems}
     *
     * @param rate the rate in frames per second; a rate that is not positive and finite is a 60 Hz one
     */
    static boolean fiftyHz(double rate) {
        if (!(rate > 0) || Double.isInfinite(rate)) {
            return false;
        }
        while (rate >= 35) {
            rate /= 2;
        }
        while (rate < 17.5) {
            rate *= 2;
        }
        return nearest(rate, FIFTY_HZ) < nearest(rate, SIXTY_HZ);
    }

    /**
     * {@return the smallest distance of a rate to one of several, on a logarithmic scale}
     *
     * @param rate the rate in frames per second
     * @param rates the rates compared
     */
    private static double nearest(double rate, double[] rates) {
        var best = Double.POSITIVE_INFINITY;
        for (var candidate : rates) {
            best = Math.min(best, Math.abs(StrictMath.log(rate / candidate)));
        }
        return best;
    }

    /**
     * Returns the DV format of the frames of a DV track, from its picture size and frame rate.
     *
     * @param track a DV track
     * @return the IEC 61834 or DVCPRO HD format of the size, or {@code null} when the size is not one of a DV format
     */
    static DvFormat formatOf(VideoTrack track) {
        var fifty = track.frameRate().map(rate -> fiftyHz(rate.toDouble())).orElse(false);
        return switch (track.width() + "x" + track.height()) {
            case "720x480" -> DvFormat.DV_525;
            case "720x576" -> DvFormat.DV_625;
            case "1280x1080" -> DvFormat.DVCPRO_HD_1080I60;
            case "1440x1080" -> DvFormat.DVCPRO_HD_1080I50;
            case "960x720" -> fifty ? DvFormat.DVCPRO_HD_720P50 : DvFormat.DVCPRO_HD_720P60;
            default -> null;
        };
    }

    /**
     * {@return whether the picture size of a DV track is shared by several formats, which only its frames tell apart:
     * 720 by 480 (DV, DVCPRO, DVCPRO50 525/60) and 720 by 576 (the 625/50 ones)}
     *
     * @param format the format {@link #formatOf(VideoTrack)} gave
     */
    static boolean ambiguous(DvFormat format) {
        return format != null && !format.highDefinition();
    }

    /**
     * Returns the DV format of one DIF frame, from its header block and VAUX source pack.
     *
     * @param frame a packet of a DV track
     * @return the format, or {@code null} when the data is not a DIF frame of a known format
     */
    static DvFormat formatOf(byte[] frame) {
        var profile = DifProfile.detect(frame, 0, frame.length);
        if (profile == null || frame.length < profile.frameSize()) {
            return null;
        }
        return DvFormat.valueOf(profile.name());
    }

    /**
     * {@return the color of the pictures of a DV format: BT.709 for DVCPRO HD, BT.601 otherwise, limited range}
     *
     * @param format the DV format written
     */
    static ColorInfo color(DvFormat format) {
        return format.highDefinition() ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
    }

    /**
     * Returns the video settings of a stream encoded in a DV format.
     *
     * @param settings the caller's settings
     * @param format the DV format
     * @return the settings with the format's frame rate and color
     * @throws IllegalArgumentException if the settings ask for a picture size, a frame rate, a color or a bit depth
     * other than the format's, or for telecine into a format other than an interlaced 60 Hz one
     */
    static VideoTranscodeOptions video(VideoTranscodeOptions settings, DvFormat format) {
        if (settings.width().isPresent() || settings.height().isPresent() || settings.maxLongEdge().isPresent()
                || settings.maxShortEdge().isPresent()) {
            throw new IllegalArgumentException(format + " fixes the picture size at " + format.width() + "x"
                    + format.height() + ": the options cannot set a box or an edge limit");
        }
        var rate = format.frameRate();
        if (settings.pulldown() == Pulldown.TELECINE) {
            if (format.interlacing() == Interlacing.PROGRESSIVE || format.fiftyHz()) {
                throw new IllegalArgumentException("telecine makes interlaced 60 Hz video, which " + format
                        + " is not");
            }
            rate = new Rational(rate.numerator() * 4, rate.denominator() * 5).reduce();
        }
        if (settings.frameRate().isPresent() && !same(settings.frameRate().get(), rate)) {
            throw new IllegalArgumentException(format + " is coded at " + format.frameRate()
                    + " frames per second, and the options ask for " + settings.frameRate().get());
        }
        if (settings.maxFrameRate().isPresent() && settings.maxFrameRate().get().toDouble() < rate.toDouble()) {
            throw new IllegalArgumentException(format + " is coded at " + format.frameRate()
                    + " frames per second, above the options' limit of " + settings.maxFrameRate().get());
        }
        var color = color(format);
        if (settings.color().isPresent() && !settings.color().get().equals(color)) {
            throw new IllegalArgumentException(format + " codes " + color + ", and the options ask for "
                    + settings.color().get());
        }
        if (settings.bitDepth().isPresent() && settings.bitDepth().getAsInt() != 8) {
            throw new IllegalArgumentException(format + " codes 8-bit samples, and the options ask for "
                    + settings.bitDepth().getAsInt());
        }
        return settings.toBuilder().frameRate(rate).noMaxFrameRate().color(color).build();
    }

    /**
     * {@return whether two rates are equal as fractions}
     *
     * @param a a rate
     * @param b another rate
     */
    private static boolean same(Rational a, Rational b) {
        return Math.multiplyExact(a.numerator(), b.denominator()) == Math.multiplyExact(b.numerator(), a.denominator());
    }

    /**
     * Returns the audio settings of the streams written into a DV format.
     *
     * @param settings the caller's settings
     * @param format the DV format, or {@code null} when it is not known (a copied stream of an unrecognized size),
     * in which case the audio is coded at 48 kHz in stereo pairs
     * @param tracks the selected audio tracks
     * @return the settings with the output rate and channel limit
     * @throws IllegalArgumentException if the format carries no audio at the requested rate, or not the requested
     * channels or tracks
     */
    static AudioTranscodeOptions audio(AudioTranscodeOptions settings, DvFormat format, List<AudioTrack> tracks) {
        if (tracks.isEmpty()) {
            return settings;
        }
        var requestedRate = settings.sampleRate().orElse(0);
        var name = format == null ? "DV" : format.toString();
        if (requestedRate != 0 && capacity(format, requestedRate) == 0) {
            throw new IllegalArgumentException(name + " carries no audio sampled at " + requestedRate + " Hz");
        }
        var layoutChannels = settings.layout().map(ChannelLayout::channels).orElse(0);
        var limit = settings.maxChannels().orElse(Integer.MAX_VALUE);
        int channels;
        int rate;
        if (tracks.size() > 1) {
            if (layoutChannels > 2) {
                throw new IllegalArgumentException(name + " carries several audio tracks as one stereo pair each, not "
                        + layoutChannels + " channels each");
            }
            channels = Math.min(2, limit);
            rate = requestedRate != 0 ? requestedRate
                    : 2 * tracks.size() > capacity(format, 48_000) && capacity(format, 32_000) > 0 ? 32_000 : 48_000;
            if (2 * tracks.size() > capacity(format, rate)) {
                throw new IllegalArgumentException(name + " carries " + capacity(format, rate) / 2
                        + " audio pairs at " + rate + " Hz, not " + tracks.size() + " tracks");
            }
        } else {
            var source = tracks.getFirst().layout().map(ChannelLayout::channels).orElse(2);
            var consumer = format != null && format.consumer();
            var asked = layoutChannels > 0 || settings.maxChannels().isPresent() || requestedRate == 32_000;
            var most = consumer && !asked ? 2 : requestedRate != 0 ? capacity(format, requestedRate)
                    : Math.max(capacity(format, 48_000), capacity(format, 32_000));
            if (format != null && format.interlacing() == Interlacing.PROGRESSIVE) {
                most = Math.min(most, 4);
            }
            channels = layoutChannels > 0 ? layoutChannels : Math.min(Math.min(source, limit), most);
            rate = requestedRate != 0 ? requestedRate : channels > capacity(format, 48_000) ? 32_000 : 48_000;
            if (channels > capacity(format, rate) || channels > most) {
                throw new IllegalArgumentException(name + " carries " + Math.min(capacity(format, rate), most)
                        + " audio channels in one track at " + rate + " Hz, not " + channels);
            }
        }
        var builder = settings.toBuilder().sampleRate(rate);
        if (layoutChannels == 0) {
            builder.maxChannels(channels);
        }
        return builder.build();
    }

    /**
     * {@return the channels a format carries at a rate, as {@link DvFormat#maxAudioChannels(int)} gives them; for an
     * unknown format two at 48 kHz and none otherwise}
     *
     * @param format the format, or {@code null} when unknown
     * @param rate the sampling frequency in hertz
     */
    private static int capacity(DvFormat format, int rate) {
        return format != null ? format.maxAudioChannels(rate) : rate == 48_000 ? 2 : 0;
    }
}
