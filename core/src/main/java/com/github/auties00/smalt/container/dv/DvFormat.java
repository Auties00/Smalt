package com.github.auties00.smalt.container.dv;

import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;

/**
 * Enumerates the DV formats of a raw DV stream: the 25 Mb/s consumer DV of IEC 61834-2, the 25 and 50 Mb/s
 * DV-based formats of SMPTE ST 314 (DVCPRO, DVCPRO50) and the 100 Mb/s DVCPRO HD of SMPTE ST 370.
 *
 * <p>A format fixes the coded picture size, the frame rate, the scan and the audio a frame can carry. Every frame of
 * a format has the same size in bytes, so its bitrate is constant. The 720-line formats are described as files carry
 * them: one packet per progressive frame, two consecutive packets forming the four DIF channels of SMPTE ST 370.
 */
public enum DvFormat {
    /**
     * IEC 61834 525/60 DV: 720 by 480, 30000/1001 interlaced frames per second, 4:1:1, 25 Mb/s; audio at 48, 44.1 or
     * 32 kHz, or four channels at 32 kHz in 12 bits.
     */
    DV_525(DifProfile.DV_525),

    /**
     * SMPTE ST 314 525/60 DVCPRO: the picture of {@link #DV_525} at 25 Mb/s, with two channels of 48 kHz audio.
     */
    DVCPRO_525(DifProfile.DVCPRO_525),

    /**
     * IEC 61834 625/50 DV: 720 by 576, 25 interlaced frames per second, 4:2:0, 25 Mb/s; audio at 48, 44.1 or 32 kHz,
     * or four channels at 32 kHz in 12 bits.
     */
    DV_625(DifProfile.DV_625),

    /**
     * SMPTE ST 314 625/50 DVCPRO: 720 by 576, 25 interlaced frames per second, 4:1:1, 25 Mb/s, with two channels of
     * 48 kHz audio.
     */
    DVCPRO_625(DifProfile.DVCPRO_625),

    /**
     * SMPTE ST 314 525/60 DVCPRO50: 720 by 480, 30000/1001 interlaced frames per second, 4:2:2, 50 Mb/s, with four
     * channels of 48 kHz audio.
     */
    DVCPRO50_525(DifProfile.DVCPRO50_525),

    /**
     * SMPTE ST 314 625/50 DVCPRO50: 720 by 576, 25 interlaced frames per second, 4:2:2, 50 Mb/s, with four channels of
     * 48 kHz audio.
     */
    DVCPRO50_625(DifProfile.DVCPRO50_625),

    /**
     * SMPTE ST 370 1080/60i DVCPRO HD: 1280 by 1080 samples displayed at 16:9, 30000/1001 interlaced frames per
     * second, 4:2:2, 100 Mb/s, with eight channels of 48 kHz audio.
     */
    DVCPRO_HD_1080I60(DifProfile.DVCPRO_HD_1080I60),

    /**
     * SMPTE ST 370 1080/50i DVCPRO HD: 1440 by 1080 samples displayed at 16:9, 25 interlaced frames per second, 4:2:2,
     * 100 Mb/s, with eight channels of 48 kHz audio.
     */
    DVCPRO_HD_1080I50(DifProfile.DVCPRO_HD_1080I50),

    /**
     * SMPTE ST 370 720/60p DVCPRO HD: 960 by 720 samples displayed at 16:9, 60000/1001 progressive frames per second,
     * 4:2:2, 100 Mb/s, with eight channels of 48 kHz audio, four in the even frames and four in the odd ones.
     */
    DVCPRO_HD_720P60(DifProfile.DVCPRO_HD_720P60),

    /**
     * SMPTE ST 370 720/50p DVCPRO HD: 960 by 720 samples displayed at 16:9, 50 progressive frames per second, 4:2:2,
     * 100 Mb/s, with eight channels of 48 kHz audio, four in the even frames and four in the odd ones.
     */
    DVCPRO_HD_720P50(DifProfile.DVCPRO_HD_720P50);

    private final DifProfile profile;

    DvFormat(DifProfile profile) {
        this.profile = profile;
    }

    /**
     * {@return the coded picture width in luma samples}
     */
    public int width() {
        return profile.width();
    }

    /**
     * {@return the coded picture height in lines}
     */
    public int height() {
        return profile.height();
    }

    /**
     * {@return the frame rate: 30000/1001 or 25 for the interlaced formats, 60000/1001 or 50 for the 720-line ones}
     */
    public Rational frameRate() {
        return profile.frameRate();
    }

    /**
     * {@return the scan of the frames: {@link Interlacing#BOTTOM_FIELD_FIRST} for the 525 and 625-line formats,
     * {@link Interlacing#TOP_FIELD_FIRST} for the 1080-line ones and {@link Interlacing#PROGRESSIVE} for the 720-line
     * ones}
     */
    public Interlacing interlacing() {
        if (profile.progressive()) {
            return Interlacing.PROGRESSIVE;
        }
        return profile.highDefinition() ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST;
    }

    /**
     * Returns the sample aspect ratio of the coded picture.
     *
     * @param wide whether the picture is displayed at 16:9 rather than 4:3; the DVCPRO HD formats always display at
     * 16:9 and ignore it
     * @return the ratio of the width of a sample to its height, for example 8/9 for 4:3 {@link #DV_525}
     */
    public Rational sampleAspectRatio(boolean wide) {
        return profile.sampleAspectRatio(wide);
    }

    /**
     * {@return whether the format is a DVCPRO HD format, displayed at 16:9 only}
     */
    public boolean highDefinition() {
        return profile.highDefinition();
    }

    /**
     * {@return whether the format belongs to the 50 Hz systems (625/50, 1080/50i and 720/50p)}
     */
    public boolean fiftyHz() {
        return profile.fiftyHz();
    }

    /**
     * {@return the size of a frame in bytes, from 120000 for {@link #DV_525} to 576000 for
     * {@link #DVCPRO_HD_1080I50}}
     */
    public int frameSize() {
        return profile.frameSize();
    }

    /**
     * {@return the nominal video bitrate in bits per second: 25, 50 or 100 Mb/s}
     */
    public long bitrate() {
        return 25_000_000L * (profile.highDefinition() ? 4 : profile.channels());
    }

    /**
     * {@return whether the format is an IEC 61834 consumer format, whose audio may also be sampled at 44.1 or 32 kHz
     * and may carry four channels at 32 kHz in 12 bits}
     */
    public boolean consumer() {
        return profile.applicationId() == 0;
    }

    /**
     * Returns the most audio channels the stream carries at a sampling frequency.
     *
     * <p>At 48 kHz every format carries two channels per 25 Mb/s: 2, 4 or 8. A consumer format also carries two
     * channels at 44.1 kHz and four at 32 kHz (two pairs coded in 12 bits). Channels come in pairs, and a raw DV stream
     * carries no channel layout: a reader sees one stereo track per pair.
     *
     * @param sampleRate the sampling frequency in hertz
     * @return the channel count, 0 when the format carries no audio at that frequency
     */
    public int maxAudioChannels(int sampleRate) {
        return switch (sampleRate) {
            case 48_000 -> 2 * (profile.progressive() ? 4 : profile.channels());
            case 44_100 -> consumer() ? 2 : 0;
            case 32_000 -> consumer() ? 4 : 0;
            default -> 0;
        };
    }
}
