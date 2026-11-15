package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demuxes every ISO base media file of the MPEG file format conformance suite (ISO/IEC 14496-32, the
 * MPEGGroup/FileFormatConformance repository: ISOBMFF, MP4, 3GP, the NAL unit file format of ISO/IEC 14496-15 for
 * AVC, SVC, MVC, HEVC, L-HEVC and VVC, Common Encryption, compressed boxes, event messages and the files under
 * consideration) and checks it against two parsers it shares nothing with:
 * <ul>
 *   <li>the GPAC box dump the suite publishes with each file ({@link GpacDump}): every sample of every track, its size,
 *       decoding duration, composition offset and, where the dump states it, whether it is a sync sample;</li>
 *   <li>ffprobe, packet by packet (sizes, data, key frames and timestamps), except for the files ffprobe cannot read.
 *       The timestamps of audio differ by the edit-list media time or codec delay that ffprobe subtracts (see the
 *       demuxer's timing conventions), and the files whose timing or sample flags ffmpeg reads otherwise than the
 *       dump declares are compared with an offset, or without key frames, as {@link #EXPECTATIONS} explains.</li>
 * </ul>
 * A file whose content the demuxer rejects by contract (encrypted tracks, samples in other files) must be rejected as
 * unsupported, naming the feature. The HEIF files of the suite are images, which the HEIF tests read.
 */
@DisplayName("MPEG file format conformance (ISO/IEC 14496-32)")
class FileFormatConformanceTest {
    /** The suite's files, by their path under data/file_features of the repository. */
    static final List<String> FILES = List.of(
            "published/3gp/female_amr67DTX_hinted.3gp",
            "published/3gp/female_amr67_hinted.3gp",
            "published/3gp/male_amr122.3gp",
            "published/3gp/male_amr122DTX.3gp",
            "published/3gp/pdin_example.3gp",
            "published/3gp/rs_example_r1.3gp",
            "published/green/meta_2500000bps_0.mp4m",
            "published/green/video_2500000bps_0.mp4",
            "published/isobmff/01_simple.mp4",
            "published/isobmff/02_dref_edts_img.mp4",
            "published/isobmff/03_hinted.mp4",
            "published/isobmff/04_bifs_video.mp4",
            "published/isobmff/05_bifs_video_protected_v2.mp4",
            "published/isobmff/06_bifs.mp4",
            "published/isobmff/07_bifs_sprite.mp4",
            "published/isobmff/08_bifs_carousel_v2.mp4",
            "published/isobmff/09_text.mp4",
            "published/isobmff/10_fragments.mp4",
            "published/isobmff/12_metas_v2.mp4",
            "published/isobmff/13_long.mp4",
            "published/isobmff/14_large.mp4.zip",
            "published/isobmff/16_vtt.mp4",
            "published/isobmff/17_negative_ctso.mp4",
            "published/isobmff/18_pssh_v2.mp4",
            "published/isobmff/19_ttml.mp4",
            "published/isobmff/20_stxt.mp4",
            "published/isobmff/21_segment.mp4",
            "published/isobmff/22_tx3g.mp4",
            "published/isobmff/FX-VY-9436R.3_qhd-variant.mp4",
            "published/isobmff/FX-VY-9436R.3_qhd.mp4",
            "published/isobmff/LargerThan4GB.mp4.zip",
            "published/isobmff/a1-foreman-QCIF.mp4",
            "published/isobmff/a10-foreman_QCIF-raw.mp4",
            "published/isobmff/a2-foreman-QCIF-hinted.mp4",
            "published/isobmff/a3-tone-protected.mp4",
            "published/isobmff/a3b-tone-deprot.mp4",
            "published/isobmff/a4-tone-fragmented.mp4",
            "published/isobmff/a5-foreman-AVC.mp4",
            "published/isobmff/a6_tone_multifile.mp4",
            "published/isobmff/a7-tone-oddities.mp4",
            "published/isobmff/a8-foreman_QCIF_edit.mp4",
            "published/isobmff/a9-aac-samplegroups-edit.mp4",
            "published/isobmff/compact-no-code-fec-1.iso3",
            "published/isobmff/compact-no-code-fec-2.iso3",
            "published/isobmff/f1.mp4",
            "published/isobmff/f2.mp4",
            "published/isobmff/fragment-random-access-1+AF8-rev1.mp4",
            "published/isobmff/fragment_random_access-2.mp4",
            "published/isobmff/mbms-fec.iso3",
            "published/isobmff/restricted.mp4",
            "published/isobmff/rtp_rtcp_reception_hint_tracks_v2.mp4",
            "published/isobmff/sg-tl-st.mp4",
            "published/isobmff/timed-metadata.mp4",
            "published/maf/paaf/01_output_a.paf",
            "published/maf/vsaf/1.mp4",
            "published/mpeg-audio-conformance/ac01.mp4",
            "published/mpeg-audio-conformance/sls2100_aot02_048_16.mp4",
            "published/nalu/hevc/aggr_hvc1.mp4",
            "published/nalu/hevc/alst_hvc1.mp4",
            "published/nalu/hevc/hev1_clg1_header.mp4",
            "published/nalu/hevc/hev1_clg1_segment.m4s",
            "published/nalu/hevc/hev1_only.mp4",
            "published/nalu/hevc/hevc_hev1_hev2_extractors.mp4",
            "published/nalu/hevc/hevc_hev1_hev2_implicit.mp4",
            "published/nalu/hevc/hevc_hvc1_hvc2_extractors.mp4",
            "published/nalu/hevc/hevc_hvc1_hvc2_implicit.mp4",
            "published/nalu/hevc/hevc_tiles_multiple_tracks.mp4",
            "published/nalu/hevc/hevc_tiles_multiple_tracks_empty_base.mp4",
            "published/nalu/hevc/hevc_tiles_single_track_nalm.mp4",
            "published/nalu/hevc/hevc_tiles_single_track_nalm_all_intra.mp4",
            "published/nalu/hevc/hevc_tiles_single_track_nalm_rle.mp4",
            "published/nalu/hevc/hevc_tiles_single_track_trif_full_picture.mp4",
            "published/nalu/hevc/hvc1_only.mp4",
            "published/nalu/hevc/hvc2_extractors.mp4",
            "published/nalu/hevc/subs_slice_hvc1.mp4",
            "published/nalu/hevc/subs_tile_hvc1.mp4",
            "published/nalu/hevc/trgr_hvc1.mp4",
            "published/nalu/l-hevc/lhevc_avc1_lhe1.mp4",
            "published/nalu/l-hevc/lhevc_avc1_lhv1.mp4",
            "published/nalu/l-hevc/lhevc_avc3_lhe1.mp4",
            "published/nalu/l-hevc/lhevc_avc3_lhv1.mp4",
            "published/nalu/l-hevc/mhvc_hev1_hev2_multiple_tracks_extractors.mp4",
            "published/nalu/l-hevc/mhvc_hev1_lhe1_multiple_tracks_implicit.mp4",
            "published/nalu/l-hevc/mhvc_hev1_single_track.mp4",
            "published/nalu/l-hevc/mhvc_hev2_single_track.mp4",
            "published/nalu/l-hevc/mhvc_hvc1_hvc2_multiple_tracks_extractors.mp4",
            "published/nalu/l-hevc/mhvc_hvc1_lhv1_multiple_tracks_implicit.mp4",
            "published/nalu/l-hevc/mhvc_hvc1_single_track.mp4",
            "published/nalu/l-hevc/mhvc_hvc2_single_track.mp4",
            "published/nalu/l-hevc/shvc_hev1_hev2_multiple_tracks_extractors.mp4",
            "published/nalu/l-hevc/shvc_hev1_lhe1_multiple_tracks_implicit.mp4",
            "published/nalu/l-hevc/shvc_hev1_single_track.mp4",
            "published/nalu/l-hevc/shvc_hev2_single_track.mp4",
            "published/nalu/l-hevc/shvc_hvc1_hvc2_multiple_tracks_extractors.mp4",
            "published/nalu/l-hevc/shvc_hvc1_lhv1_multiple_tracks_implicit.mp4",
            "published/nalu/l-hevc/shvc_hvc1_single_track.mp4",
            "published/nalu/l-hevc/shvc_hvc2_single_track.mp4",
            "published/nalu/mvc/DDF_10s_25fps-dynamic.mp4",
            "published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R1_seg-0005.3gs",
            "published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R2_seg-0005.3gs",
            "published/nalu/mvc/DDF_10s_25fps.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R1-0001.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R1-0002.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R1-0003.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R2-0001.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R2-0002.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R2-0003.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R3-0001.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R3-0002.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep-R3-0003.mp4",
            "published/nalu/svc/mp4-live-LastTime-depRep.mp4",
            "published/uvvu/Solekai002_1280_23_1x1_v7clear.uvvu",
            "published/uvvu/Solekai007_1920_29_1x1_v7clear.uvu",
            "under_consideration/cenc/audio_cbcs.mp4",
            "under_consideration/cenc/audio_cbcs_const_iv.mp4",
            "under_consideration/cenc/audio_cenc_128iv.mp4",
            "under_consideration/cenc/video_cbc1.mp4",
            "under_consideration/cenc/video_cbcs.mp4",
            "under_consideration/cenc/video_cbcs_const_iv.mp4",
            "under_consideration/cenc/video_cbcs_const_iv_roll.mp4",
            "under_consideration/cenc/video_cbcs_const_iv_roll_frag1s.mp4",
            "under_consideration/cenc/video_cbcs_mkey_const_iv_subs.mp4",
            "under_consideration/cenc/video_cenc_128iv.mp4",
            "under_consideration/cenc/video_cenc_64iv.mp4",
            "under_consideration/cenc/video_cenc_mkey_base.mp4",
            "under_consideration/cenc/video_cenc_mkey_roll.mp4",
            "under_consideration/cenc/video_cenc_mkey_subs.mp4",
            "under_consideration/cenc/video_cenc_roll.mp4",
            "under_consideration/cenc/video_cenc_roll_frag1s.mp4",
            "under_consideration/cenc/video_cens_128iv.mp4",
            "under_consideration/cenc/video_cens_64iv.mp4",
            "under_consideration/compressed_boxes/comp_all_otyp.mp4",
            "under_consideration/compressed_boxes/comp_moof_nobrand.mp4",
            "under_consideration/compressed_boxes/comp_moof_otyp.mp4",
            "under_consideration/compressed_boxes/comp_moof_sidx_otyp.mp4",
            "under_consideration/compressed_boxes/comp_moof_sidx_ssix_otyp.mp4",
            "under_consideration/compressed_boxes/comp_moov_isoc.mp4",
            "under_consideration/compressed_boxes/comp_moov_otyp.mp4",
            "under_consideration/event_message/IF1_V2_captures/audio-und-128000.isma",
            "under_consideration/event_message/IF1_V2_captures/data-2-Data3_3.mp4",
            "under_consideration/event_message/IF1_V2_captures/video-720x480-500000.ismv",
            "under_consideration/event_message/out_avail_track.cmfm",
            "under_consideration/event_message/out_overlap.cmfm",
            "under_consideration/isobmff_nalu/isobmff_updates/sample_group_default_desc.mp4",
            "under_consideration/isobmff_nalu/isobmff_updates/sample_group_default_desc_frag.mp4",
            "under_consideration/isobmff_nalu/isobmff_updates/sample_group_static_group.mp4",
            "under_consideration/isobmff_nalu/isobmff_updates/sample_group_static_group_frag.mp4",
            "under_consideration/isobmff_nalu/isobmff_updates/sample_group_static_mapping.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_hev1_hev2_extractors_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_hev1_hev2_implicit_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_hvc1_hvc2_extractors_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_hvc1_hvc2_implicit_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_tiles_multiple_tracks_empty_base_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_tiles_multiple_tracks_v2.mp4",
            "under_consideration/isobmff_nalu/nalu_updates/hevc_tiles_single_track_trif_full_picture_v2.mp4",
            "under_consideration/mebx/test_mebx_me4c.mp4",
            "under_consideration/vvc/RA_BodeMuseum_dcfi_1920x1080_qp37.mp4",
            "under_consideration/vvc/RA_BodeMuseum_inband_ps_1280x720_qp37.mp4",
            "under_consideration/vvc/RA_BodeMuseum_mixed_res_qp37.mp4",
            "under_consideration/vvc/RA_BodeMuseum_outband_ps_1920x1080_qp37.mp4",
            "under_consideration/vvc/RA_BodeMuseum_pase_unused_1280x720_qp37.mp4",
            "under_consideration/vvc/RA_BodeMuseum_pase_used_1920x1080_and_1280x720_qp37.mp4",
            "under_consideration/vvc/vvc_basic_track.mp4",
            "under_consideration/vvc/vvc_mixed_nal_subpicture_tracks.mp4",
            "under_consideration/vvc/vvc_subpicture_tracks.mp4",
            "under_consideration/vvc/vvc_subpicture_tracks_spor_ordered.mp4",
            "under_consideration/vvc/vvc_substitute_subpicture_single_sample_track.mp4",
            "under_consideration/vvc/vvc_substitute_subpictures_all_frames.mp4",
            "under_consideration/vvc/vvc_substitute_subpictures_single_sample_track.mp4",
            "under_consideration/vvc/vvc_substitute_subpictures_track.mp4"
    );

    /** What a file other than one that demuxes like both oracles must do. */
    sealed interface Expectation {
    }

    /** The file is rejected with an unsupported-format failure for the named feature. */
    record Unsupported(String format) implements Expectation {
    }

    /** The file is rejected as malformed, with a message containing the text: the demuxer's limit for this file. */
    record Rejected(String message) implements Expectation {
    }

    /**
     * Some samples of the file lie past its end: the demuxer returns the samples before the first of them (ffprobe
     * stops earlier, at the first sample of any track past the end), and then fails as malformed.
     */
    record Truncated() implements Expectation {
    }

    /**
     * The file has compressed boxes ({@code !mov}, {@code !mof}, {@code !six}, {@code !ssx}), which neither ffprobe
     * nor the GPAC dump reads: the file must demux like ffprobe reads a copy whose compressed boxes are replaced by
     * the boxes they hold, the file in which its offsets count.
     */
    record Compressed() implements Expectation {
    }

    /**
     * The samples are in a file of the suite that a data reference names: without a resolver the file is
     * unsupported, and with one over a directory holding both files its samples must match the GPAC dump and, put
     * end to end, be the referenced file (ffprobe does not follow {@code url } references).
     */
    record External(String sibling) implements Expectation {
    }

    /** ffprobe cannot read the file: only the GPAC dump is compared. */
    record GpacOnly() implements Expectation {
    }

    /** The file is compared with ffprobe with this timestamp offset of ours against ffprobe's, and maybe without key
     * frames. */
    record Quirk(ToLongFunction<Track> offset, boolean keyframes) implements Expectation {
    }

    /** The file holds raw video, which ffprobe cannot describe, as frames of a fixed size. */
    record RawVideo(int frameSize) implements Expectation {
    }

    /**
     * The file has layered H.264 tracks (ISO/IEC 14496-15 clauses 7 and 8: a scalable or multiview layer in a track
     * whose {@code tref}/{@code scal} box names the tracks it extends), whose packets the demuxer rebuilds into whole
     * access units where ffprobe returns the stored samples. The file is compared with ffprobe as {@link Quirk} does,
     * against ffprobe's packets with the packets of every layered track rebuilt here, independently of the demuxer,
     * from the bytes ffprobe returns and the file's own boxes: for the {@code svc1}, {@code mvc1} and {@code mvc3}
     * entries the latest samples at or before the packet's decoding time of the referenced tracks followed by the
     * sample (implicit reconstruction), otherwise the sample with every aggregator (NAL unit type 30) replaced by the
     * units it holds and every extractor (type 31) by the bytes it names, nothing when the referenced sample is absent.
     * ffprobe names no codec for the {@code mvc1} to {@code mvc4} entries, which are H.264.
     */
    record Layered(ToLongFunction<Track> offset, boolean keyframes) implements Expectation {
    }

    static final Map<String, Expectation> EXPECTATIONS = Map.ofEntries(
            Map.entry("published/isobmff/02_dref_edts_img.mp4", new Unsupported("mp4 external data reference")),
            Map.entry("published/isobmff/04_bifs_video.mp4", new Unsupported("mp4 external data reference")),
            // the samples are in myData.dat, which the suite publishes next to the file; the other two reference
            // files the suite does not publish
            Map.entry("published/isobmff/a6_tone_multifile.mp4", new External("myData.dat")),
            Map.entry("published/isobmff/a3-tone-protected.mp4", new Unsupported("mp4 encryption")),
            Map.entry("published/isobmff/f2.mp4", new Unsupported("mp4 encryption")),
            Map.entry("published/isobmff/05_bifs_video_protected_v2.mp4", new Unsupported("mp4 encryption")),
            Map.entry("published/isobmff/18_pssh_v2.mp4", new Unsupported("mp4 encryption")),
            Map.entry("published/isobmff/FX-VY-9436R.3_qhd.mp4", new Unsupported("mp4 encryption")),
            // TODO: read the FEC reservoir and FEC hint tracks of 3GPP MBMS files, whose mdhd timescale is 0
            Map.entry("published/isobmff/compact-no-code-fec-1.iso3", new Rejected("has timescale 0")),
            Map.entry("published/isobmff/compact-no-code-fec-2.iso3", new Rejected("has timescale 0")),
            Map.entry("published/isobmff/mbms-fec.iso3", new Rejected("has timescale 0")),
            // a file of items (an MPEG-A photo player application format file): no movie, which item readers read
            Map.entry("published/maf/paaf/01_output_a.paf", new Rejected("no moov box")),
            Map.entry("published/green/video_2500000bps_0.mp4", new Truncated()),
            Map.entry("published/maf/vsaf/1.mp4", new Truncated()),
            // ffmpeg reads neither the meta boxes, nor WebVTT in a text handler, nor the L-HEVC sample entries,
            // nor scalable AAC and SLS layers as separate tracks: "error reading header"
            Map.entry("published/isobmff/12_metas_v2.mp4", new GpacOnly()),
            Map.entry("published/isobmff/16_vtt.mp4", new GpacOnly()),
            Map.entry("published/isobmff/FX-VY-9436R.3_qhd-variant.mp4", new GpacOnly()),
            Map.entry("published/mpeg-audio-conformance/ac01.mp4", new GpacOnly()),
            Map.entry("published/mpeg-audio-conformance/sls2100_aot02_048_16.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/mhvc_hev1_hev2_multiple_tracks_extractors.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/mhvc_hev1_single_track.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/mhvc_hev2_single_track.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/shvc_hev1_hev2_multiple_tracks_extractors.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/shvc_hev1_single_track.mp4", new GpacOnly()),
            Map.entry("published/nalu/l-hevc/shvc_hev2_single_track.mp4", new GpacOnly()),
            Map.entry("under_consideration/event_message/out_avail_track.cmfm", new GpacOnly()),

            // ffmpeg ignores the lone zero-length edit of this fragmented segment, where we apply its media time of 8
            Map.entry("published/isobmff/21_segment.mp4", new Quirk(track -> -8, true)),
            // ffmpeg moves an edit start that falls inside a sample (media time 15085, samples every 100) to the next
            // sample boundary, where we apply the exact media time
            Map.entry("published/isobmff/a8-foreman_QCIF_edit.mp4", new Quirk(track -> 15, true)),
            // ffmpeg starts the second (implicit reconstruction) track one sample earlier than its edit's media time of
            // 17000 and its first composition time of 0 place it; the GPAC dump confirms our timing
            Map.entry("published/nalu/hevc/hevc_hev1_hev2_implicit.mp4", new Quirk(FileFormatConformanceTest::second, true)),
            Map.entry("published/nalu/hevc/hevc_hvc1_hvc2_implicit.mp4", new Quirk(FileFormatConformanceTest::second, true)),
            Map.entry("under_consideration/isobmff_nalu/nalu_updates/hevc_hev1_hev2_implicit_v2.mp4",
                    new Quirk(FileFormatConformanceTest::second, true)),
            Map.entry("under_consideration/isobmff_nalu/nalu_updates/hevc_hvc1_hvc2_implicit_v2.mp4",
                    new Quirk(FileFormatConformanceTest::second, true)),
            // layered H.264 tracks; ffmpeg also flags the first sample of an SVC enhancement layer fragment as not a
            // key frame, though its sample flags declare a sync sample (the GPAC dump confirms ours)
            Map.entry("published/nalu/mvc/DDF_10s_25fps.mp4", new Layered(ConformanceTest::mp4Offset, true)),
            Map.entry("published/nalu/mvc/DDF_10s_25fps-dynamic.mp4", new Layered(ConformanceTest::mp4Offset, true)),
            Map.entry("published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R1_seg-0005.3gs",
                    new Layered(ConformanceTest::mp4Offset, true)),
            Map.entry("published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R2_seg-0005.3gs",
                    new Layered(ConformanceTest::mp4Offset, true)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0001.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0002.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0003.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0001.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0002.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0003.mp4", new Layered(ConformanceTest::mp4Offset, false)),
            // ffmpeg raises the presentation times of a fragmented track whose composition offsets go below zero
            // (trun version 1) by the most negative offset, where we keep them (tfdt plus the offset) and lower the
            // decoding times instead; the GPAC dump confirms ours
            // (and flags its sample 86, an independent picture whose sample flags declare it non-sync, a key frame)
            Map.entry("published/nalu/hevc/hev1_clg1_segment.m4s", new Quirk(track -> -16, false)),
            Map.entry("published/uvvu/Solekai002_1280_23_1x1_v7clear.uvvu",
                    new Quirk(track -> track.codec() == Codec.H264 ? -2002 : ConformanceTest.mp4Offset(track), true)),
            Map.entry("published/uvvu/Solekai007_1920_29_1x1_v7clear.uvu",
                    new Quirk(track -> track.codec() == Codec.H264 ? -1001 : ConformanceTest.mp4Offset(track), true)),
            // ffmpeg flags samples as key frames that the fragment's default sample flags declare non-sync (the
            // GPAC dump confirms ours)
            Map.entry("under_consideration/event_message/IF1_V2_captures/video-720x480-500000.ismv",
                    new Quirk(ConformanceTest::mp4Offset, false)),
            // raw planar 4:2:0 QCIF frames in a QuickTime j420 sample entry
            Map.entry("published/isobmff/a10-foreman_QCIF-raw.mp4", new RawVideo(176 * 144 * 3 / 2))
    );

    /** The media segments of the suite, with the initialization segment each continues. */
    static final Map<String, String> INITIALIZATION = Map.ofEntries(
            Map.entry("published/nalu/hevc/hev1_clg1_segment.m4s", "published/nalu/hevc/hev1_clg1_header.mp4"),
            Map.entry("published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R1_seg-0005.3gs",
                    "published/nalu/mvc/DDF_10s_25fps-dynamic.mp4"),
            Map.entry("published/nalu/mvc/DDF_10s_25fps-dynamic_rep-R2_seg-0005.3gs",
                    "published/nalu/mvc/DDF_10s_25fps-dynamic.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R1-0001.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R1-0002.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R1-0003.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0001.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0002.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R2-0003.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0001.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0002.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4"),
            Map.entry("published/nalu/svc/mp4-live-LastTime-depRep-R3-0003.mp4", "published/nalu/svc/mp4-live-LastTime-depRep.mp4")
    );

    /** Codecs whose packets are not the file's samples: one packet per WebVTT cue, PCM in packets of 1024 frames. */
    private static final Set<Codec> REPACKETIZED = Set.of(Codec.WEBVTT);

    static Stream<String> files() {
        return FILES.stream();
    }

    @TempDir
    Path work;

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("demuxes like the GPAC dump and ffprobe, or rejects what it does not support")
    void conformance(String path) {
        try {
            check(path);
        } catch (TestAbortedException skip) {
            throw skip;
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError(path + ": " + failure.getMessage(), failure);
        }
    }

    private void check(String path) {
        var file = file(path);
        var expectation = EXPECTATIONS.get(path);
        if (expectation == null && path.startsWith("under_consideration/cenc/")) {
            expectation = new Unsupported("mp4 encryption");
        }
        if (expectation == null && path.startsWith("under_consideration/compressed_boxes/")) {
            expectation = new Compressed();
        }
        switch (expectation) {
            case Unsupported(var format) -> {
                var failure = assertThrows(MediaException.Unsupported.class,
                        () -> ContainerProbe.demux(file, Mp4Demuxer::new));
                assertEquals(format, failure.format());
            }
            case External(var sibling) -> external(path, file, sibling);
            case Rejected(var message) -> {
                var failure = assertThrows(MediaException.Malformed.class,
                        () -> ContainerProbe.demux(file, Mp4Demuxer::new));
                assertTrue(failure.getMessage().contains(message), failure.getMessage());
            }
            case Truncated() -> truncated(file);
            case RawVideo(var frameSize) -> {
                var ours = ContainerProbe.demux(file, Mp4Demuxer::new);
                var track = ours.tracks().getFirst();
                assertEquals(Codec.RAW_VIDEO, track.codec());
                var packets = ours.packetsOf(track);
                assertTrue(packets.size() > 10);
                assertTrue(packets.stream().allMatch(packet -> packet.size() == frameSize), "every frame is one picture");
                assertMatchesDump(ours, dump(path));
                var video = (VideoTrack) track;
                assertEquals(ColorRange.FULL, video.color().orElseThrow().range(), "j420 is full range");
                try (var decoder = VideoDecoders.create(video)) {
                    var frames = new int[1];
                    for (var packet : packets) {
                        decoder.decode(packet, frame -> {
                            var picture = assertInstanceOf(YuvPicture.class, frame.picture());
                            assertEquals(176, picture.width());
                            assertEquals(144, picture.height());
                            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
                            // planar Y, Cb, Cr: the first luma row and the first chroma row are the packet's bytes
                            var data = packet.data();
                            assertArrayEquals(Arrays.copyOfRange(data, 0, 176), Arrays.copyOfRange(picture.y(), 0, 176));
                            assertArrayEquals(Arrays.copyOfRange(data, 176 * 144, 176 * 144 + 88),
                                    Arrays.copyOfRange(picture.u(), 0, 88));
                            assertArrayEquals(Arrays.copyOfRange(data, 176 * 144 * 5 / 4, 176 * 144 * 5 / 4 + 88),
                                    Arrays.copyOfRange(picture.v(), 0, 88));
                            frames[0]++;
                        });
                    }
                    assertEquals(packets.size(), frames[0]);
                }
            }
            case GpacOnly() -> assertMatchesDump(ContainerProbe.demux(file, Mp4Demuxer::new), dump(path));
            case Compressed() -> {
                var probe = ContainerProbe.probe(decompressed(file));
                var ours = ContainerProbe.demux(file, Mp4Demuxer::new);
                assertFalse(ours.order().isEmpty(), "no packet");
                DemuxAssertions.assertPacketsMatch(ours, probe, ConformanceTest::mp4Offset, true, 0, true);
                DemuxAssertions.assertMonotonicDts(ours);
            }
            case Quirk(var offset, var keyframes) -> demuxes(path, file, offset, keyframes);
            case Layered(var offset, var keyframes) -> layered(path, file, offset, keyframes);
            case null -> demuxes(path, file, ConformanceTest::mp4Offset, true);
        }
    }

    /** The timestamp offset of the second track of the L-HEVC implicit reconstruction files, ours against ffprobe's. */
    private static long second(Track track) {
        return track.id() == 2 ? 1000 : 0;
    }

    private void demuxes(String path, Path file, ToLongFunction<Track> offset, boolean keyframes) {
        var probe = ContainerProbe.probe(file);
        var ours = ContainerProbe.demux(file, Mp4Demuxer::new);
        DemuxAssertions.assertPacketsMatch(ours, probe, offset, true, 0, keyframes);
        DemuxAssertions.assertMonotonicDts(ours);
        assertMatchesDump(ours, dump(path));
    }

    /** The sample entries of the H.264 layers, and those of them that use implicit reconstruction. */
    private static final Set<String> AVC_ENTRIES = Set.of("avc1", "avc2", "avc3", "avc4", "svc1", "svc2", "mvc1",
            "mvc2", "mvc3", "mvc4");
    private static final Set<String> IMPLICIT_ENTRIES = Set.of("svc1", "mvc1", "mvc3");

    /** A track of the movie box, as {@link Layered} reads it: identifier, sample entry, NAL length size and scal list. */
    private record TrackBoxes(int id, String entry, int lengthSize, List<Integer> scalable) {
        boolean layered() {
            return AVC_ENTRIES.contains(entry) && !scalable.isEmpty();
        }
    }

    private void layered(String path, Path file, ToLongFunction<Track> offset, boolean keyframes) {
        var probe = ContainerProbe.probe(file);
        var data = ContainerProbe.packetData(file);
        assertEquals(probe.packets().size(), data.size(), "ffprobe packet data count");
        for (var index = 0; index < data.size(); index++) {
            assertEquals(probe.packets().get(index).hash(), ContainerProbe.crc32(data.get(index)),
                    "ffprobe packet " + index + " data");
        }
        var boxes = trackBoxes(file);
        assertEquals(probe.streams().size(), boxes.size(), "tracks in the movie box");
        var streamOf = new java.util.HashMap<Integer, Integer>();
        for (var stream = 0; stream < boxes.size(); stream++) {
            streamOf.put(boxes.get(stream).id(), stream);
        }
        var packets = new ArrayList<List<ContainerProbe.ProbePacket>>();
        var samples = new ArrayList<List<byte[]>>();
        for (var stream = 0; stream < boxes.size(); stream++) {
            packets.add(new ArrayList<>());
            samples.add(new ArrayList<>());
        }
        for (var index = 0; index < data.size(); index++) {
            var packet = probe.packets().get(index);
            packets.get(packet.stream()).add(packet);
            samples.get(packet.stream()).add(data.get(index));
        }
        var rebuiltPackets = new ArrayList<ContainerProbe.ProbePacket>();
        var rebuiltTracks = new java.util.HashSet<Integer>();
        for (var index = 0; index < data.size(); index++) {
            var packet = probe.packets().get(index);
            if (!boxes.get(packet.stream()).layered()) {
                rebuiltPackets.add(packet);
                continue;
            }
            var visited = new java.util.HashSet<Integer>(List.of(packet.stream()));
            var access = rebuild(packet.stream(), packet.dts(), data.get(index), boxes, streamOf, probe, packets,
                    samples, visited, 0);
            rebuiltPackets.add(new ContainerProbe.ProbePacket(packet.stream(), packet.pts(), packet.dts(),
                    packet.duration(), access.length, packet.key(), packet.discard(), ContainerProbe.crc32(access)));
        }
        assertTrue(boxes.stream().anyMatch(TrackBoxes::layered), "the file has no layered track");
        var streams = new ArrayList<ContainerProbe.ProbeStream>();
        for (var stream : probe.streams()) {
            var entry = boxes.get(stream.index()).entry();
            if (entry.startsWith("mvc") && "unknown".equals(stream.codecName())) {
                var fields = new java.util.LinkedHashMap<>(stream.fields());
                fields.put("codec_name", "h264");
                stream = new ContainerProbe.ProbeStream(stream.index(), fields, stream.extradata());
            }
            streams.add(stream);
        }
        var ours = ContainerProbe.demux(file, Mp4Demuxer::new);
        for (var stream = 0; stream < boxes.size(); stream++) {
            if (boxes.get(stream).layered()) {
                rebuiltTracks.add(ours.tracks().get(stream).id());
            }
        }
        DemuxAssertions.assertPacketsMatch(ours, new ContainerProbe.Probe(streams, rebuiltPackets, probe.format()),
                offset, true, 0, keyframes);
        DemuxAssertions.assertMonotonicDts(ours);
        assertMatchesDump(ours, dump(path), rebuiltTracks);
    }

    /** Rebuilds the access unit of a sample of a layered track, as {@link Layered} describes. */
    private static byte[] rebuild(int stream, long dts, byte[] sample, List<TrackBoxes> boxes,
                                  Map<Integer, Integer> streamOf, ContainerProbe.Probe probe,
                                  List<List<ContainerProbe.ProbePacket>> packets, List<List<byte[]>> samples,
                                  Set<Integer> visited, int depth) {
        var track = boxes.get(stream);
        var out = new java.io.ByteArrayOutputStream();
        if (IMPLICIT_ENTRIES.contains(track.entry())) {
            for (var id : track.scalable()) {
                var referenced = streamOf.get(id);
                if (referenced == null || !visited.add(referenced)) {
                    continue;
                }
                var time = rescale(dts, probe.streams().get(stream), probe.streams().get(referenced));
                var aligned = aligned(packets.get(referenced), samples.get(referenced), time, 0);
                if (aligned != null) {
                    out.writeBytes(boxes.get(referenced).layered() && depth < 8
                            ? rebuild(referenced, time, aligned, boxes, streamOf, probe, packets, samples, visited,
                            depth + 1) : aligned);
                }
            }
            out.writeBytes(sample);
            return out.toByteArray();
        }
        var size = track.lengthSize();
        var layerUnits = false;
        for (var position = 0; position + size <= sample.length; ) {
            var length = (int) field(sample, position, size);
            if (length <= 0 || length > sample.length - position - size) {
                break;
            }
            var type = sample[position + size] & 0x1F;
            layerUnits |= type == 30 || type == 31;
            position += size + length;
        }
        if (!layerUnits) {
            return sample;
        }
        units(stream, dts, sample, 0, sample.length, boxes, streamOf, probe, packets, samples, out, 0);
        return out.toByteArray();
    }

    /** Copies the NAL units of a range of a sample, unwrapping aggregators and resolving extractors. */
    private static void units(int stream, long dts, byte[] sample, int from, int to, List<TrackBoxes> boxes,
                              Map<Integer, Integer> streamOf, ContainerProbe.Probe probe,
                              List<List<ContainerProbe.ProbePacket>> packets, List<List<byte[]>> samples,
                              java.io.ByteArrayOutputStream out, int depth) {
        var track = boxes.get(stream);
        var size = track.lengthSize();
        for (var position = from; position + size <= to; ) {
            var length = (int) field(sample, position, size);
            var start = position + size;
            if (length < 0 || length > to - start) {
                return;
            }
            var type = length > 0 ? sample[start] & 0x1F : 0;
            if (type == 30 && length > 4 && depth < 8) {
                // the aggregator's 4-byte NAL unit header (with the SVC or MVC extension) precedes its units
                units(stream, dts, sample, start + 4, start + length, boxes, streamOf, probe, packets, samples, out,
                        depth + 1);
            } else if (type == 31 && length >= 6 + 2 * size) {
                // ISO/IEC 14496-15 extractor: header, track_ref_index, sample_offset, data_offset, data_length
                var reference = sample[start + 4] & 0xFF;
                var sampleOffset = sample[start + 5];
                var dataOffset = (int) field(sample, start + 6, size);
                var dataLength = (int) field(sample, start + 6 + size, size);
                var referenced = reference >= 1 && reference <= track.scalable().size()
                        ? streamOf.get(track.scalable().get(reference - 1)) : null;
                if (referenced != null && referenced != stream) {
                    var time = rescale(dts, probe.streams().get(stream), probe.streams().get(referenced));
                    var source = aligned(packets.get(referenced), samples.get(referenced), time, sampleOffset);
                    if (source != null && dataOffset < source.length) {
                        var end = dataLength == 0
                                ? dataOffset + size + field(source, dataOffset, size)
                                : (long) dataOffset + dataLength;
                        out.write(source, dataOffset, (int) (Math.min(source.length, end) - dataOffset));
                    }
                }
            } else {
                out.write(sample, position, size + length);
            }
            position = start + length;
        }
    }

    /** Returns the sample of a stream whose decoding time is the latest at or before a time, moved by a count. */
    private static byte[] aligned(List<ContainerProbe.ProbePacket> packets, List<byte[]> samples, long time,
                                  int offset) {
        var found = -1;
        for (var index = 0; index < packets.size(); index++) {
            if (packets.get(index).dts() <= time) {
                found = index;
            }
        }
        var index = found + offset;
        return found < 0 || index < 0 || index >= samples.size() ? null : samples.get(index);
    }

    /** Converts a decoding time of one stream to the time base of another. */
    private static long rescale(long time, ContainerProbe.ProbeStream from, ContainerProbe.ProbeStream to) {
        return Math.floorDiv(time * from.timeBaseNumerator() * to.timeBaseDenominator(),
                from.timeBaseDenominator() * to.timeBaseNumerator());
    }

    /** Reads a big-endian unsigned field of one to four bytes, or {@code -1} past the end. */
    private static long field(byte[] data, int position, int size) {
        if (position < 0 || position + size > data.length) {
            return -1;
        }
        long value = 0;
        for (var index = 0; index < size; index++) {
            value = (value << 8) | (data[position + index] & 0xFF);
        }
        return value;
    }

    /** Reads every track of the file's movie box, in file order. */
    private static List<TrackBoxes> trackBoxes(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var moov = child(bytes, 0, bytes.length, "moov");
        assertNotNull(moov, "no moov box");
        var tracks = new ArrayList<TrackBoxes>();
        for (var position = moov[0]; position + 8 <= moov[1]; ) {
            var size = (int) field(bytes, position, 4);
            var type = new String(bytes, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("trak")) {
                tracks.add(track(bytes, position + 8, position + size));
            }
            position += size;
        }
        return tracks;
    }

    private static TrackBoxes track(byte[] bytes, int from, int to) {
        var tkhd = child(bytes, from, to, "tkhd");
        assertNotNull(tkhd, "no tkhd box");
        var id = (int) field(bytes, tkhd[0] + (bytes[tkhd[0]] == 1 ? 20 : 12), 4);
        var scalable = new ArrayList<Integer>();
        var tref = child(bytes, from, to, "tref");
        var scal = tref == null ? null : child(bytes, tref[0], tref[1], "scal");
        if (scal != null) {
            for (var position = scal[0]; position + 4 <= scal[1]; position += 4) {
                scalable.add((int) field(bytes, position, 4));
            }
        }
        int[] stsd = null;
        var mdia = child(bytes, from, to, "mdia");
        var minf = mdia == null ? null : child(bytes, mdia[0], mdia[1], "minf");
        var stbl = minf == null ? null : child(bytes, minf[0], minf[1], "stbl");
        if (stbl != null) {
            stsd = child(bytes, stbl[0], stbl[1], "stsd");
        }
        if (stsd == null || stsd[0] + 16 > stsd[1]) {
            return new TrackBoxes(id, "", 4, scalable);
        }
        var entryStart = stsd[0] + 8;
        var entryEnd = entryStart + (int) field(bytes, entryStart, 4);
        var entry = new String(bytes, entryStart + 4, 4, StandardCharsets.ISO_8859_1);
        var lengthSize = 4;
        if (AVC_ENTRIES.contains(entry)) {
            // the configuration records follow the 78 bytes of the visual sample entry; all three keep
            // lengthSizeMinusOne in the low bits of their fifth byte
            for (var name : List.of("avcC", "svcC", "mvcC")) {
                var record = child(bytes, entryStart + 8 + 78, entryEnd, name);
                if (record != null) {
                    lengthSize = (bytes[record[0] + 4] & 3) + 1;
                    break;
                }
            }
        }
        return new TrackBoxes(id, entry, lengthSize, scalable);
    }

    /** Returns the payload range (after the box header) of the first child box of a type, or {@code null}. */
    private static int[] child(byte[] bytes, int from, int to, String type) {
        for (var position = from; position + 8 <= to; ) {
            var size = (int) field(bytes, position, 4);
            if (size < 8 || size > to - position) {
                return null;
            }
            if (new String(bytes, position + 4, 4, StandardCharsets.ISO_8859_1).equals(type)) {
                return new int[]{position + 8, position + size};
            }
            position += size;
        }
        return null;
    }

    private void external(String path, Path file, String sibling) {
        var failure = assertThrows(MediaException.Unsupported.class, () -> ContainerProbe.demux(file, Mp4Demuxer::new));
        assertEquals("mp4 external data reference", failure.format());
        try {
            var directory = Files.createDirectories(work.resolve("external"));
            var movie = Files.copy(file, directory.resolve(path.substring(path.lastIndexOf('/') + 1)),
                    StandardCopyOption.REPLACE_EXISTING);
            var referenced = Files.copy(Conformance.fetchListed("mpegff-" + sibling), directory.resolve(sibling),
                    StandardCopyOption.REPLACE_EXISTING);
            var options = DemuxerOptions.builder().dataReferences(DataReferenceResolver.directory(directory)).build();
            var ours = ContainerProbe.demux(movie, source -> new Mp4Demuxer(source, options));
            assertMatchesDump(ours, dump(path));
            var joined = new java.io.ByteArrayOutputStream();
            for (var packet : ours.order()) {
                joined.writeBytes(packet.data());
            }
            assertArrayEquals(Files.readAllBytes(referenced), joined.toByteArray(), "the samples are the referenced file");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Demuxes until the first sample past the end of the file, which must fail as malformed. */
    private static void truncated(Path file) {
        var probe = ContainerProbe.probe(file);
        var delivered = new ArrayList<Packet>();
        List<Track> tracks;
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            tracks = demuxer.tracks();
            var failure = assertThrows(MediaException.Malformed.class, () -> {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    delivered.add(packet);
                }
            });
            assertTrue(failure.getMessage().contains("runs past the end"), failure.getMessage());
        }
        assertEquals(probe.streams().size(), tracks.size(), "track count");
        for (var index = 0; index < tracks.size(); index++) {
            var track = tracks.get(index);
            var ours = delivered.stream().filter(packet -> packet.trackId() == track.id()).toList();
            var expected = probe.packetsOf(probe.streams().get(index).index());
            assertFalse(ours.isEmpty(), "track " + index + " returned no packet");
            for (var i = 0; i < Math.min(ours.size(), expected.size()); i++) {
                assertEquals(expected.get(i).size(), ours.get(i).size(), "track " + index + " packet " + i + " size");
                assertEquals(expected.get(i).hash(), ContainerProbe.crc32(ours.get(i).data()),
                        "track " + index + " packet " + i + " data hash");
            }
        }
    }

    /**
     * Compares every sample of every track the demuxer returns with the GPAC dump: count, size, decoding duration,
     * composition offset relative to the first sample (edit lists shift both alike) and, where the dump states it,
     * the sync sample flag.
     */
    private static void assertMatchesDump(ContainerProbe.Demuxed ours, GpacDump dump) {
        assertMatchesDump(ours, dump, Set.of());
    }

    /**
     * Compares like {@link #assertMatchesDump(ContainerProbe.Demuxed, GpacDump)}, without the sample sizes of the
     * tracks whose packets are rebuilt access units ({@link Layered}), which the dump states for the stored samples.
     */
    private static void assertMatchesDump(ContainerProbe.Demuxed ours, GpacDump dump, Set<Integer> rebuilt) {
        if (dump == null) {
            return;
        }
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            if (REPACKETIZED.contains(track.codec()) || track.codec().isPcm()) {
                continue;
            }
            var expected = dump.track(track.id());
            if (expected == null && index < dump.tracks().size()) {
                expected = dump.tracks().get(index);
            }
            var label = "track " + track.id() + " (" + track.codec() + ")";
            assertNotNull(expected, label + " is not in the GPAC dump");
            var packets = ours.packetsOf(track);
            var samples = expected.samples();
            assertEquals(samples.size(), packets.size(), label + " sample count against the GPAC dump");
            assertEquals(expected.timescale(), track.timeBase().denominator(), label + " timescale");
            for (var i = 0; i < samples.size(); i++) {
                var sample = samples.get(i);
                var packet = packets.get(i);
                var where = label + " sample " + i + " against the GPAC dump";
                if (!rebuilt.contains(track.id())) {
                    assertEquals(sample[0], packet.size(), where + ": size");
                }
                if (i > 0) {
                    assertEquals(samples.get(i - 1)[1], packet.dts() - packets.get(i - 1).dts(), where + ": decoding time");
                    if (packet.pts() != Packet.NO_TIMESTAMP && packets.getFirst().pts() != Packet.NO_TIMESTAMP) {
                        assertEquals(sample[2] - samples.getFirst()[2], (packet.pts() - packet.dts())
                                - (packets.getFirst().pts() - packets.getFirst().dts()), where + ": composition offset");
                    }
                }
                // audio samples are all random access points whatever the sync sample table says
                if (sample[3] >= 0 && track instanceof VideoTrack) {
                    assertEquals(sample[3] == 1, packet.keyframe(), where + ": sync sample");
                }
            }
        }
    }

    /** The boxes the compressed boxes replace, by compressed type. */
    private static final Map<String, String> UNCOMPRESSED = Map.of("!mov", "moov", "!mof", "moof", "!six", "sidx",
            "!ssx", "ssix");

    /** Writes the copy of a file with compressed boxes that {@link Compressed} describes. */
    private Path decompressed(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            var plain = new java.io.ByteArrayOutputStream();
            var replaced = 0;
            var position = 0;
            while (position + 8 <= bytes.length) {
                var size = java.nio.ByteBuffer.wrap(bytes, position, 4).getInt();
                var type = new String(bytes, position + 4, 4, StandardCharsets.ISO_8859_1);
                var original = UNCOMPRESSED.get(type);
                if (original != null) {
                    byte[] payload;
                    try (var input = new java.util.zip.GZIPInputStream(
                            new java.io.ByteArrayInputStream(bytes, position + 8, size - 8))) {
                        payload = input.readAllBytes();
                    }
                    plain.writeBytes(java.nio.ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                            .put(original.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array());
                    replaced++;
                } else {
                    plain.write(bytes, position, size);
                }
                position += size;
            }
            assertTrue(replaced > 0, "no compressed box in " + file);
            var out = work.resolve(file.getFileName() + ".plain.mp4");
            Files.write(out, plain.toByteArray());
            return out;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the GPAC dump of a path, with the dump of its initialization segment first, or {@code null}. */
    private static GpacDump dump(String path) {
        var dumps = new ArrayList<String>();
        var init = INITIALIZATION.get(path);
        if (init != null) {
            dumps.add(read(dumpName(init)));
        }
        var own = dumpName(path);
        if (!path.endsWith(".m4s")) {
            dumps.add(read(own));
        } else {
            return null;
        }
        return GpacDump.parse(dumps);
    }

    private static String dumpName(String path) {
        var stem = path.endsWith(".mp4.zip") ? path.substring(0, path.length() - 8)
                : path.substring(0, path.lastIndexOf('.'));
        return "mpegff-" + stem.substring(stem.lastIndexOf('/') + 1) + "_gpac.json";
    }

    private static String read(String vector) {
        try {
            return Files.readString(Conformance.fetchListed(vector), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns the cached file of a path: a media segment joined to its initialization segment, a zipped file
     * extracted, into the work directory.
     */
    private Path file(String path) {
        var vector = Conformance.fetchListed("mpegff-" + path.substring(path.lastIndexOf('/') + 1));
        var init = INITIALIZATION.get(path);
        if (init != null) {
            try {
                var joined = work.resolve(path.substring(path.lastIndexOf('/') + 1) + ".joined.mp4");
                var header = Files.readAllBytes(file(init));
                var segment = Files.readAllBytes(vector);
                var bytes = java.util.Arrays.copyOf(header, header.length + segment.length);
                System.arraycopy(segment, 0, bytes, header.length, segment.length);
                Files.write(joined, bytes);
                return joined;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        if (!path.endsWith(".zip")) {
            return vector;
        }
        try (var zip = new ZipInputStream(Files.newInputStream(vector))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && !entry.getName().startsWith("__MACOSX")) {
                    var target = work.resolve(Path.of(entry.getName()).getFileName().toString());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        throw new IllegalStateException(path + " holds no file");
    }
}
