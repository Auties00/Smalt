package com.github.auties00.smalt.container.mp4;

/**
 * Defines the four-character codes of the ISO base media, QuickTime and iTunes boxes, sample
 * entries and handler types this package reads or writes.
 *
 * <p>Each code is the big-endian 32-bit value of its four ASCII characters, so it can label a
 * {@code switch} case. The codes come from ISO/IEC 14496-12 (base media), 14496-14 (MP4), 14496-15
 * (AVC and HEVC storage), the Opus, FLAC, VP and AV1 ISO base media bindings, and Apple's QuickTime
 * File Format specification.
 */
final class BoxTypes {
    /**
     * The file type box ({@code ftyp}).
     */
    static final int FTYP = 0x66747970;

    /**
     * The segment type box ({@code styp}).
     */
    static final int STYP = 0x73747970;

    /**
     * The movie box ({@code moov}).
     */
    static final int MOOV = 0x6D6F6F76;

    /**
     * The media data box ({@code mdat}).
     */
    static final int MDAT = 0x6D646174;

    /**
     * The movie fragment box ({@code moof}).
     */
    static final int MOOF = 0x6D6F6F66;

    /**
     * The movie fragment random access box ({@code mfra}).
     */
    static final int MFRA = 0x6D667261;

    /**
     * The free space box ({@code free}).
     */
    static final int FREE = 0x66726565;

    /**
     * The segment index box ({@code sidx}).
     */
    static final int SIDX = 0x73696478;

    /**
     * The movie header box ({@code mvhd}).
     */
    static final int MVHD = 0x6D766864;

    /**
     * The track box ({@code trak}).
     */
    static final int TRAK = 0x7472616B;

    /**
     * The track header box ({@code tkhd}).
     */
    static final int TKHD = 0x746B6864;

    /**
     * The edit box ({@code edts}).
     */
    static final int EDTS = 0x65647473;

    /**
     * The edit list box ({@code elst}).
     */
    static final int ELST = 0x656C7374;

    /**
     * The media box ({@code mdia}).
     */
    static final int MDIA = 0x6D646961;

    /**
     * The media header box ({@code mdhd}).
     */
    static final int MDHD = 0x6D646864;

    /**
     * The handler reference box ({@code hdlr}).
     */
    static final int HDLR = 0x68646C72;

    /**
     * The media information box ({@code minf}).
     */
    static final int MINF = 0x6D696E66;

    /**
     * The sample table box ({@code stbl}).
     */
    static final int STBL = 0x7374626C;

    /**
     * The sample description box ({@code stsd}).
     */
    static final int STSD = 0x73747364;

    /**
     * The decoding time to sample box ({@code stts}).
     */
    static final int STTS = 0x73747473;

    /**
     * The composition time to sample box ({@code ctts}).
     */
    static final int CTTS = 0x63747473;

    /**
     * The sync sample box ({@code stss}).
     */
    static final int STSS = 0x73747373;

    /**
     * The QuickTime partial sync sample atom ({@code stps}).
     */
    static final int STPS = 0x73747073;

    /**
     * The independent and disposable samples box ({@code sdtp}).
     */
    static final int SDTP = 0x73647470;

    /**
     * The sample size box ({@code stsz}).
     */
    static final int STSZ = 0x7374737A;

    /**
     * The compact sample size box ({@code stz2}).
     */
    static final int STZ2 = 0x73747A32;

    /**
     * The sample to chunk box ({@code stsc}).
     */
    static final int STSC = 0x73747363;

    /**
     * The 32-bit chunk offset box ({@code stco}).
     */
    static final int STCO = 0x7374636F;

    /**
     * The 64-bit chunk offset box ({@code co64}).
     */
    static final int CO64 = 0x636F3634;

    /**
     * The movie extends box ({@code mvex}).
     */
    static final int MVEX = 0x6D766578;

    /**
     * The movie extends header box ({@code mehd}).
     */
    static final int MEHD = 0x6D656864;

    /**
     * The track extends box ({@code trex}).
     */
    static final int TREX = 0x74726578;

    /**
     * The movie fragment header box ({@code mfhd}).
     */
    static final int MFHD = 0x6D666864;

    /**
     * The track fragment box ({@code traf}).
     */
    static final int TRAF = 0x74726166;

    /**
     * The track fragment header box ({@code tfhd}).
     */
    static final int TFHD = 0x74666864;

    /**
     * The track fragment decode time box ({@code tfdt}).
     */
    static final int TFDT = 0x74666474;

    /**
     * The track fragment run box ({@code trun}).
     */
    static final int TRUN = 0x7472756E;

    /**
     * The user data box ({@code udta}).
     */
    static final int UDTA = 0x75647461;

    /**
     * The metadata box ({@code meta}).
     */
    static final int META = 0x6D657461;

    /**
     * The iTunes metadata item list ({@code ilst}).
     */
    static final int ILST = 0x696C7374;

    /**
     * The QuickTime metadata keys atom ({@code keys}).
     */
    static final int KEYS = 0x6B657973;

    /**
     * The iTunes metadata value atom ({@code data}).
     */
    static final int DATA = 0x64617461;

    /**
     * The iTunes freeform namespace atom ({@code mean}).
     */
    static final int MEAN = 0x6D65616E;

    /**
     * The iTunes freeform name atom ({@code name}).
     */
    static final int NAME = 0x6E616D65;

    /**
     * The iTunes freeform metadata item ({@code ----}).
     */
    static final int FREEFORM = 0x2D2D2D2D;

    /**
     * The H.264 sample entry with in-band-free parameter sets ({@code avc1}).
     */
    static final int AVC1 = 0x61766331;

    /**
     * The H.264 sample entry with in-band parameter sets ({@code avc3}).
     */
    static final int AVC3 = 0x61766333;

    /**
     * The AVC decoder configuration box ({@code avcC}).
     */
    static final int AVCC = 0x61766343;

    /**
     * The HEVC sample entry with out-of-band parameter sets ({@code hvc1}).
     */
    static final int HVC1 = 0x68766331;

    /**
     * The HEVC sample entry with in-band parameter sets ({@code hev1}).
     */
    static final int HEV1 = 0x68657631;

    /**
     * The Dolby Vision HEVC sample entry ({@code dvh1}).
     */
    static final int DVH1 = 0x64766831;

    /**
     * The Dolby Vision HEVC sample entry with in-band parameter sets ({@code dvhe}).
     */
    static final int DVHE = 0x64766865;

    /**
     * The HEVC decoder configuration box ({@code hvcC}).
     */
    static final int HVCC = 0x68766343;

    /**
     * The VP8 sample entry ({@code vp08}).
     */
    static final int VP08 = 0x76703038;

    /**
     * The VP9 sample entry ({@code vp09}).
     */
    static final int VP09 = 0x76703039;

    /**
     * The VP codec configuration box ({@code vpcC}).
     */
    static final int VPCC = 0x76706343;

    /**
     * The AV1 sample entry ({@code av01}).
     */
    static final int AV01 = 0x61763031;

    /**
     * The AV1 codec configuration box ({@code av1C}).
     */
    static final int AV1C = 0x61763143;

    /**
     * The MPEG-4 visual sample entry ({@code mp4v}).
     */
    static final int MP4V = 0x6D703476;

    /**
     * The 3GPP H.263 sample entry ({@code s263}).
     */
    static final int S263 = 0x73323633;

    /**
     * The H.263 sample entry ({@code h263}).
     */
    static final int H263 = 0x68323633;

    /**
     * The Motion JPEG sample entry ({@code jpeg}).
     */
    static final int JPEG = 0x6A706567;

    /**
     * The Motion JPEG format A sample entry ({@code mjpa}).
     */
    static final int MJPA = 0x6D6A7061;

    /**
     * The Motion JPEG format B sample entry ({@code mjpb}).
     */
    static final int MJPB = 0x6D6A7062;

    /**
     * The Motion JPEG 2000 sample entry ({@code mjp2}, ISO/IEC 15444-3), also the brand of a Motion JPEG 2000 file.
     */
    static final int MJP2 = 0x6D6A7032;

    /**
     * The simple profile brand of a Motion JPEG 2000 file ({@code mj2s}).
     */
    static final int MJ2S = 0x6D6A3273;

    /**
     * The JPEG 2000 signature box ({@code jP  }), the first box of a Motion JPEG 2000 file.
     */
    static final int JP_SIGNATURE = 0x6A502020;

    /**
     * The JP2 Header box of a Motion JPEG 2000 sample entry ({@code jp2h}).
     */
    static final int JP2H = 0x6A703268;

    /**
     * The subsampling box of a Motion JPEG 2000 sample entry ({@code jsub}).
     */
    static final int JSUB = 0x6A737562;

    /**
     * The original format box of a Motion JPEG 2000 sample entry ({@code orfo}).
     */
    static final int ORFO = 0x6F72666F;

    /**
     * The ProRes 422 HQ sample entry ({@code apch}).
     */
    static final int APCH = 0x61706368;

    /**
     * The ProRes 422 sample entry ({@code apcn}).
     */
    static final int APCN = 0x6170636E;

    /**
     * The ProRes 422 LT sample entry ({@code apcs}).
     */
    static final int APCS = 0x61706373;

    /**
     * The ProRes 422 Proxy sample entry ({@code apco}).
     */
    static final int APCO = 0x6170636F;

    /**
     * The ProRes 4444 sample entry ({@code ap4h}).
     */
    static final int AP4H = 0x61703468;

    /**
     * The ProRes 4444 XQ sample entry ({@code ap4x}).
     */
    static final int AP4X = 0x61703478;

    /**
     * The encrypted video sample entry ({@code encv}).
     */
    static final int ENCV = 0x656E6376;

    /**
     * The encrypted audio sample entry ({@code enca}).
     */
    static final int ENCA = 0x656E6361;

    /**
     * The color information box ({@code colr}).
     */
    static final int COLR = 0x636F6C72;

    /**
     * The mastering display color volume box ({@code mdcv}).
     */
    static final int MDCV = 0x6D646376;

    /**
     * The content light level box ({@code clli}).
     */
    static final int CLLI = 0x636C6C69;

    /**
     * The ISO color type with range flag ({@code nclx}).
     */
    static final int NCLX = 0x6E636C78;

    /**
     * The QuickTime color type without range flag ({@code nclc}).
     */
    static final int NCLC = 0x6E636C63;

    /**
     * The MPEG-4 audio sample entry ({@code mp4a}).
     */
    static final int MP4A = 0x6D703461;

    /**
     * The elementary stream descriptor box ({@code esds}).
     */
    static final int ESDS = 0x65736473;

    /**
     * The QuickTime sound extension atom ({@code wave}).
     */
    static final int WAVE = 0x77617665;

    /**
     * The QuickTime endianness atom ({@code enda}).
     */
    static final int ENDA = 0x656E6461;

    /**
     * The QuickTime channel layout atom ({@code chan}).
     */
    static final int CHAN = 0x6368616E;

    /**
     * The ISO channel layout box ({@code chnl}).
     */
    static final int CHNL = 0x63686E6C;

    /**
     * The Opus sample entry ({@code Opus}).
     */
    static final int OPUS = 0x4F707573;

    /**
     * The Opus specific box ({@code dOps}).
     */
    static final int DOPS = 0x644F7073;

    /**
     * The FLAC sample entry ({@code fLaC}).
     */
    static final int FLAC = 0x664C6143;

    /**
     * The FLAC specific box ({@code dfLa}).
     */
    static final int DFLA = 0x64664C61;

    /**
     * The ALAC sample entry and specific box ({@code alac}).
     */
    static final int ALAC = 0x616C6163;

    /**
     * The AMR narrowband sample entry ({@code samr}).
     */
    static final int SAMR = 0x73616D72;

    /**
     * The AMR wideband sample entry ({@code sawb}).
     */
    static final int SAWB = 0x73617762;

    /**
     * The AMR decoder configuration box of the {@code samr} and {@code sawb} sample entries
     * ({@code damr}).
     */
    static final int DAMR = 0x64616D72;

    /**
     * The AC-3 sample entry ({@code ac-3}).
     */
    static final int AC3 = 0x61632D33;

    /**
     * The E-AC-3 sample entry ({@code ec-3}).
     */
    static final int EC3 = 0x65632D33;

    /**
     * The AC-3 specific box of the {@code ac-3} sample entry ({@code dac3}).
     */
    static final int DAC3 = 0x64616333;

    /**
     * The E-AC-3 specific box of the {@code ec-3} sample entry ({@code dec3}).
     */
    static final int DEC3 = 0x64656333;

    /**
     * The MP3 sample entry ({@code .mp3}).
     */
    static final int MP3 = 0x2E6D7033;

    /**
     * The QuickTime MPEG layer 3 sample entry ({@code ms\0U}).
     */
    static final int MS_MP3 = 0x6D730055;

    /**
     * The ISO integer PCM sample entry ({@code ipcm}).
     */
    static final int IPCM = 0x6970636D;

    /**
     * The ISO floating-point PCM sample entry ({@code fpcm}).
     */
    static final int FPCM = 0x6670636D;

    /**
     * The PCM configuration box ({@code pcmC}).
     */
    static final int PCMC = 0x70636D43;

    /**
     * The QuickTime linear PCM sample entry ({@code lpcm}).
     */
    static final int LPCM = 0x6C70636D;

    /**
     * The QuickTime big-endian signed PCM sample entry ({@code twos}).
     */
    static final int TWOS = 0x74776F73;

    /**
     * The QuickTime little-endian signed PCM sample entry ({@code sowt}).
     */
    static final int SOWT = 0x736F7774;

    /**
     * The QuickTime 24-bit PCM sample entry ({@code in24}).
     */
    static final int IN24 = 0x696E3234;

    /**
     * The QuickTime 32-bit PCM sample entry ({@code in32}).
     */
    static final int IN32 = 0x696E3332;

    /**
     * The QuickTime 32-bit float PCM sample entry ({@code fl32}).
     */
    static final int FL32 = 0x666C3332;

    /**
     * The QuickTime 64-bit float PCM sample entry ({@code fl64}).
     */
    static final int FL64 = 0x666C3634;

    /**
     * The QuickTime unsigned 8-bit PCM sample entry ({@code raw }).
     */
    static final int RAW = 0x72617720;

    /**
     * The QuickTime uncompressed PCM sample entry ({@code NONE}).
     */
    static final int NONE = 0x4E4F4E45;

    /**
     * The mu-law sample entry ({@code ulaw}).
     */
    static final int ULAW = 0x756C6177;

    /**
     * The A-law sample entry ({@code alaw}).
     */
    static final int ALAW = 0x616C6177;

    /**
     * The video handler type ({@code vide}).
     */
    static final int VIDE = 0x76696465;

    /**
     * The audio handler type ({@code soun}).
     */
    static final int SOUN = 0x736F756E;

    /**
     * The image sequence handler type of HEIF ({@code pict}).
     */
    static final int PICT = 0x70696374;

    /**
     * The auxiliary video handler type of HEIF, used by alpha and depth sequences ({@code auxv}).
     */
    static final int AUXV = 0x61757876;

    /**
     * The text handler type ({@code text}).
     */
    static final int TEXT = 0x74657874;

    /**
     * The subtitle handler type ({@code sbtl}).
     */
    static final int SBTL = 0x7362746C;

    /**
     * The ISO subtitle handler type ({@code subt}).
     */
    static final int SUBT = 0x73756274;

    /**
     * The subtitle media header box ({@code sthd}).
     */
    static final int STHD = 0x73746864;

    /**
     * The video media header box ({@code vmhd}).
     */
    static final int VMHD = 0x766D6864;

    /**
     * The sound media header box ({@code smhd}).
     */
    static final int SMHD = 0x736D6864;

    /**
     * The null media header box ({@code nmhd}).
     */
    static final int NMHD = 0x6E6D6864;

    /**
     * The data information box ({@code dinf}).
     */
    static final int DINF = 0x64696E66;

    /**
     * The data reference box ({@code dref}).
     */
    static final int DREF = 0x64726566;

    /**
     * The data entry URL box ({@code url }).
     */
    static final int URL = 0x75726C20;

    /**
     * The ISO base media brand ({@code isom}).
     */
    static final int ISOM = 0x69736F6D;

    /**
     * The ISO base media version 2 brand ({@code iso2}).
     */
    static final int ISO2 = 0x69736F32;

    /**
     * The MP4 version 1 brand ({@code mp41}).
     */
    static final int MP41 = 0x6D703431;

    /**
     * The MP4 version 2 brand ({@code mp42}).
     */
    static final int MP42 = 0x6D703432;

    /**
     * The iTunes audio brand ({@code M4A }).
     */
    static final int M4A = 0x4D344120;

    /**
     * The QuickTime brand ({@code qt  }).
     */
    static final int QT = 0x71742020;

    /**
     * The Avid DNxHD sample entry ({@code AVdn}).
     */
    static final int AVDN = 0x4156646E;

    /**
     * The Avid DNxHR sample entry ({@code AVdh}).
     */
    static final int AVDH = 0x41566468;

    /**
     * The QuickTime field handling box ({@code fiel}).
     */
    static final int FIEL = 0x6669656C;

    /**
     * The pixel aspect ratio box ({@code pasp}).
     */
    static final int PASP = 0x70617370;

    /**
     * The sample to group box ({@code sbgp}).
     */
    static final int SBGP = 0x73626770;

    /**
     * The random access point sample grouping type ({@code rap }).
     */
    static final int RAP = 0x72617020;

    /**
     * The sync sample grouping type of ISO/IEC 14496-15 ({@code sync}).
     */
    static final int SYNC = 0x73796E63;

    private BoxTypes() {
        throw new AssertionError();
    }

    /**
     * Returns whether a code consists of printable ASCII characters, as every real box type does.
     *
     * @param code the big-endian 32-bit value
     * @return {@code true} when each byte is printable ASCII or the copyright sign used by iTunes
     * item names
     */
    static boolean isPlausible(int code) {
        for (var shift = 24; shift >= 0; shift -= 8) {
            var value = (code >>> shift) & 0xFF;
            if ((value < 0x20 || value >= 0x7F) && value != 0xA9) {
                return false;
            }
        }
        return true;
    }
}
