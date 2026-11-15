/* Oracle for the USAC time-warped filterbank, over the ISO/IEC 23003-3 reference software.

   twref <frame length 768|1024> <frames> <seed> <out.f32> [lpd]

   Drives tw_reset, tw_calc_tw, tw_adjust_past (usac_tw_tools.c) and the time-warped branch of fd2buffer
   (usac_imdct.c, copied below) over pseudo-random window sequences, window shapes, warp ratios and transform
   outputs, the way decodeChannelElement (decode_chan_ele.c) calls them, and writes the output samples of every
   frame as float32 little endian. The inputs come from a xorshift32 generator so that a test can rebuild them:

     state ^= state << 13; state ^= state >> 17; state ^= state << 5;
     sample = (float) (int32) state * (1.0f / 2147483648.0f) * 8192.0f

   Per frequency domain frame, in this order: the window sequence (next() % count among the sequences allowed
   after the last one, in the order of WINDOW_SEQUENCE), the window shape (next() & 1), tw_data_present
   (next() % 4 != 0) and, when present, the 16 tw_ratio values (next() % 8), then the 2 * frame length transform
   samples.

   With "lpd", linear prediction domain frames come between them, through the time-warped transitions of the
   reference software, usac_past_tw and the time-warped branch of usac_td2buffer (usac_td_dec.c, copied below),
   the placement of the LPD decoder's last synthesis that decoder_LPD_end (dec_LPD.c) makes, and the FrameWasTd
   and ApplyFac branches of the time-warped fd2buffer. The LPD decoder itself is not run: its synthesis, its last
   synthesis and the FAC signal of the following frequency domain frame are generator samples. A frame is a linear
   prediction domain frame when next() % 2 == 0, drawn first, only after a linear prediction domain frame or after
   a LONG_START, EIGHT_SHORT or STOP_START frame. A linear prediction domain frame then draws whether it starts with
   ACELP (next() % 2 == 0) and its frame length synthesized samples; when it follows a frequency domain frame, the
   2 * frame length samples of the overlap after usac_past_tw, which the LPD decoder reads, are written before the
   frame's output. A frequency domain frame after a linear prediction domain frame draws, after its transform
   samples, frame length / 2 + frame length / 8 samples of the LPD decoder's last synthesis (the rest is zero),
   whether FAC data is present (next() % 2 == 0) and, when it is, the 2 * lfac samples of the FAC signal.

   Two statements of the copied reference code are changed, as smalt-mpeg's TimeWarpedSynthesis documents: the
   time-warped usac_td2buffer clears the overlap under a frame that starts with TCX as the plain branch does, and
   the time-warped fd2buffer keeps the samples of a long FAC signal past the first eighth of a frame after its
   center. The sine transition fd2buffer windows the overlap with after a TCX frame is computed from its definition,
   which the reference software rounds to six decimals for 96, 128 and 192 samples.

   The copied code is Copyright Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008, under the ISO/IEC reference
   software licence (use in products conforming to ISO/IEC 23003). */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include "usac_all.h"
#include "usac_interface.h"
#include "usac_tw_tools.h"
#include "usac_tw_defines.h"
#include "usac_calcWindow.h"
#include "vector_ops.h"

#define MAX_SHIFT_LEN_LONG 4096
#define LFAC_1024 128
#define L_FRAME_1024 1024
#define L_SUBFR 64
#define BPF_SFD 1

#ifndef M_PI
#define M_PI 3.14159265358979323846264338327950288
#endif

int debug[256];
const double dolby_win_1024[1024], dolby_win_960[960], dolby_win_256[256], dolby_win_128[128], dolby_win_120[120],
    dolby_win_32[32], dolby_win_16[16], dolby_win_4[4];
const float ShortWindowSine64[64], usac_sine_win_64[64], usac_kbd_win_64[64], usac_sine_win_1024[1024],
    usac_sine_win_128[128], usac_dolby_win_1024[1024], usac_dolby_win_128[128], usac_sine_win_768[768],
    usac_dolby_win_768[768], usac_sine_win_192[192], usac_dolby_win_192[192], usac_sine_win_96[96],
    usac_dolby_win_96[96];

void CommonWarning(char *message, ...) {
}

void CommonExit(int errorCode, char *message, ...) {
    va_list args;
    va_start(args, message);
    vfprintf(stderr, message, args);
    va_end(args);
    fprintf(stderr, "\n");
    exit(errorCode ? errorCode : 1);
}

static uint32_t state;

static uint32_t next(void) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

static float sample(void) {
    return (float) (int32_t) next() * (1.0f / 2147483648.0f) * 8192.0f;
}

static const float zero = 0.0f;

/* The default branch of calc_window (usac_calcWindow.c) for the sine shape. */
static void sine_window(float window[], int len) {
    for (int i = 0; i < len; i++) {
        window[i] = sin(((i + 1) - 0.5) * M_PI / (2 * len));
    }
}

/* The time-warped branch of fd2buffer (usac_imdct.c) with sbrSyncDelay = 0, the transform output given instead of
   computed and the FAC signal given instead of decoded. */
static void tw_fd2buffer(const float transform[], float p_out_data[], float p_overlap[],
                         WINDOW_SEQUENCE windowSequence, WINDOW_SEQUENCE windowSequenceLast, int nlong,
                         WINDOW_SHAPE wfun_select, WINDOW_SHAPE wfun_select_prev, int FrameWasTd, float sample_pos[],
                         float tw_trans_len[], int tw_start_stop[], float past_sample_pos[],
                         float past_tw_trans_len[], int past_tw_start_stop[],
                         float past_warped_time_sample_vector[], int ApplyFac, const float facTimeSig[]) {
    static float transf_buf[2 * MAX_SHIFT_LEN_LONG];
    static float transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    static float overlap_buf[2 * MAX_SHIFT_LEN_LONG];
    static float os_window[MAX_SHIFT_LEN_LONG * TW_OS_FACTOR_WIN];
    static float os_window_prev[MAX_SHIFT_LEN_LONG * TW_OS_FACTOR_WIN];
    static float window_short_prev[MAX_SHIFT_LEN_LONG];
    static float tw_transf_buf[2 * MAX_SHIFT_LEN_LONG];
    static float tw_transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    float *o_buf;
    int nmdct = nlong;
    int nmdct_past = nlong;
    int nshort = nlong / 8;
    int lfac = LFAC_1024;
    int nflat_ls = (nlong - nshort) / 2;
    int ntrans_ls = nshort;

    if (FrameWasTd) {
        if (windowSequence == EIGHT_SHORT_SEQUENCE) {
            lfac = nlong / 16;
        } else {
            lfac = nlong / 8;
        }
        nflat_ls = (nlong - lfac * 2) / 2;
        ntrans_ls = lfac * 2;
    }
    memset(transf_buf, 0, sizeof transf_buf);
    memset(transf_buf_past, 0, sizeof transf_buf_past);
    memset(overlap_buf, 0, sizeof overlap_buf);
    calc_tw_window(os_window, nlong * TW_OS_FACTOR_WIN, wfun_select);
    calc_tw_window(os_window_prev, nlong * TW_OS_FACTOR_WIN, wfun_select_prev);
    sine_window(window_short_prev, ntrans_ls);
    vcopy(p_overlap, overlap_buf, 1, 1, 2 * nlong);
    o_buf = overlap_buf;
    vcopy(&zero, tw_transf_buf, 0, 1, 2 * MAX_SHIFT_LEN_LONG);
    vcopy(&zero, tw_transf_buf, 0, 1, 3 * nlong);
    vcopy(past_warped_time_sample_vector, tw_transf_buf_past, 1, 1, 3 * nlong);
    memcpy(transf_buf, transform, sizeof(float) * 2 * nlong);
    if (windowSequence != EIGHT_SHORT_SEQUENCE) {
        tw_windowing_long(transf_buf, tw_transf_buf + TW_IPLEN2S, tw_start_stop[0], tw_start_stop[1], nmdct,
                          tw_trans_len[0], tw_trans_len[1], os_window_prev, os_window);
    } else {
        tw_windowing_short(transf_buf, tw_transf_buf + TW_IPLEN2S, tw_start_stop[0], tw_start_stop[1],
                           tw_trans_len[0], tw_trans_len[1], os_window_prev, os_window, nmdct / 8);
    }
    tw_resamp(tw_transf_buf, 0, 2 * nmdct + 2 * TW_IPLEN2S, nlong + (LFAC_1024 * nlong) / L_FRAME_1024, sample_pos,
              0.5f, transf_buf);
    if (windowSequenceLast != EIGHT_SHORT_SEQUENCE && !FrameWasTd) {
        tw_windowing_past(tw_transf_buf_past + TW_IPLEN2S, tw_transf_buf_past + TW_IPLEN2S, past_tw_start_stop[1],
                          nmdct_past, past_tw_trans_len[1], os_window_prev);
    }
    if (!FrameWasTd) {
        tw_resamp(tw_transf_buf_past, nlong + (LFAC_1024 * nlong) / L_FRAME_1024, 2 * nmdct_past + 2 * TW_IPLEN2S,
                  3 * nlong, past_sample_pos, 0.5f, transf_buf_past);
        vadd(transf_buf_past + nlong + (LFAC_1024 * nlong) / L_FRAME_1024,
             o_buf + (LFAC_1024 * nlong) / L_FRAME_1024, o_buf + (LFAC_1024 * nlong) / L_FRAME_1024, 1, 1, 1,
             2 * nlong - (LFAC_1024 * nlong) / L_FRAME_1024);
    }
    if (ApplyFac) {
        vadd(facTimeSig, transf_buf + nlong, transf_buf + nlong, 1, 1, 1, 2 * lfac);
        vcopy(&zero, transf_buf, 0, 1, nlong);
        vcopy(&zero, o_buf + nlong, 0, 1, (LFAC_1024 * nlong) / L_FRAME_1024);
    }
    if (FrameWasTd && !ApplyFac) {
        vmult(o_buf + (nlong / 2) + nflat_ls, window_short_prev + ntrans_ls - 1, o_buf + (nlong / 2) + nflat_ls, 1,
              -1, 1, ntrans_ls);
        vcopy(&zero, o_buf + (nlong / 2) + nflat_ls + ntrans_ls, 0, 1, nflat_ls);
    }
    vadd(transf_buf, o_buf, o_buf, 1, 1, 1, nlong + (LFAC_1024 * nlong) / L_FRAME_1024);
    if (ApplyFac) {
        /* changed: the reference software leaves out the FAC samples past the resampled part of the frame */
        for (int i = (LFAC_1024 * nlong) / L_FRAME_1024; i < 2 * lfac; i++) {
            o_buf[nlong + i] += facTimeSig[i];
        }
    }
    vcopy(o_buf, p_out_data, 1, 1, nlong);
    vcopy(o_buf + nlong, p_overlap, 1, 1, 2 * nlong);
    vcopy(tw_transf_buf, past_warped_time_sample_vector, 1, 1, 2 * nlong + 2 * TW_IPLEN2S);
    vcopy(sample_pos, past_sample_pos, 1, 1, 3 * nlong);
    past_tw_trans_len[0] = tw_trans_len[0];
    past_tw_trans_len[1] = tw_trans_len[1];
    past_tw_start_stop[0] = tw_start_stop[0];
    past_tw_start_stop[1] = tw_start_stop[1];
}

/* usac_past_tw (usac_td_dec.c). */
static void tw_past(float p_overlap[], int nlong, WINDOW_SHAPE prev_wfun_select, WINDOW_SEQUENCE windowSequenceLast,
                    float past_sample_pos[], float past_tw_trans_len[], int past_tw_start_stop[],
                    float past_warped_time_sample_vector[]) {
    static float tw_transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    static float transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    static float os_window_prev[MAX_SHIFT_LEN_LONG * TW_OS_FACTOR_WIN];
    int nmdct = nlong;

    memset(transf_buf_past, 0, sizeof transf_buf_past);
    calc_tw_window(os_window_prev, nlong * TW_OS_FACTOR_WIN, prev_wfun_select);
    vcopy(past_warped_time_sample_vector, tw_transf_buf_past, 1, 1, 3 * nlong);
    if (windowSequenceLast != EIGHT_SHORT_SEQUENCE) {
        tw_adjust_past((WINDOW_SEQUENCE) 0, windowSequenceLast, 1, past_tw_start_stop, past_tw_trans_len, nmdct);
        tw_windowing_past(tw_transf_buf_past + TW_IPLEN2S, tw_transf_buf_past + TW_IPLEN2S, past_tw_start_stop[1],
                          nmdct, past_tw_trans_len[1], os_window_prev);
    }
    tw_resamp(tw_transf_buf_past, nlong + nlong / 8, 2 * nmdct + 2 * TW_IPLEN2S, 3 * nlong, past_sample_pos, 0.5f,
              transf_buf_past);
    vadd(transf_buf_past + nlong + nlong / 8, p_overlap + nlong / 8, p_overlap + nlong / 8, 1, 1, 1,
         2 * nlong - nlong / 8);
}

/* The time-warped branch of usac_td2buffer (usac_td_dec.c). */
static void tw_td2buffer(float p_in_data[], float p_out_data[], float p_overlap[], int nlong,
                         WINDOW_SEQUENCE windowSequenceLast, int mod0, int frameWasTd) {
    int nshort = nlong / 8;
    int lfac = 0;
    int i;

    if (!frameWasTd) {
        if (windowSequenceLast == EIGHT_SHORT_SEQUENCE) {
            lfac = nshort / 2;
        } else {
            lfac = nshort;
        }
    }
    if (!frameWasTd && (mod0 == 0)) {
        for (i = 0; i < (nlong / 2) - lfac - (BPF_SFD * L_SUBFR); i++) {
            p_in_data[i] = 0.0f;
        }
    } else if (!frameWasTd && (mod0 > 0)) {
        for (i = 0; i < (nlong / 2) - lfac; i++) {
            p_in_data[i] = 0.0f;
        }
    }
    if (!frameWasTd && (mod0 == 0)) {
        for (i = (nlong / 2) - lfac - (BPF_SFD * L_SUBFR); i < nlong / 2; i++) {
            p_overlap[i + (nlong / 2)] = 0.0f;
        }
    } else if (!frameWasTd) {
        /* changed: the clearing the plain branch makes under a frame that starts with TCX, half a frame later */
        for (i = (nlong / 2) - lfac; i < nlong; i++) {
            p_overlap[i + (nlong / 2)] = 0.0f;
        }
    }
    for (i = 0; i < nlong / 2; i++) {
        p_out_data[i] = p_overlap[i];
        p_out_data[i + nlong / 2] = p_overlap[i + nlong / 2] + p_in_data[i];
        p_overlap[i] = p_in_data[i + (nlong / 2)] + p_overlap[i + nlong];
        p_overlap[i + (nlong / 2)] = 0.0f;
        p_overlap[i + nlong] = 0.0f;
        p_overlap[i + nlong + (nlong / 2)] = 0.0f;
    }
}

int main(int argc, char **argv) {
    if (argc != 5 && argc != 6) {
        fprintf(stderr, "usage: twref <frame length> <frames> <seed> <out.f32> [lpd]\n");
        return 1;
    }
    int n = atoi(argv[1]);
    int frames = atoi(argv[2]);
    int lpd = argc == 6 && strcmp(argv[5], "lpd") == 0;
    state = (uint32_t) strtoul(argv[3], NULL, 10);
    FILE *out = fopen(argv[4], "wb");
    if (!out || (n != 768 && n != 1024) || frames <= 0 || state == 0 || (argc == 6 && !lpd)) {
        fprintf(stderr, "bad arguments\n");
        return 1;
    }
    tw_init();
    float *warp_cont_mem = calloc(2 * n, sizeof(float));
    float warp_sum[2];
    float *sample_pos = calloc(3 * 1024, sizeof(float));
    float *prev_sample_pos = calloc(3 * n, sizeof(float));
    float *prev_warped = calloc(3 * n, sizeof(float));
    float *overlap = calloc(2 * n, sizeof(float));
    float *transform = calloc(2 * n, sizeof(float));
    float *synth = calloc(n, sizeof(float));
    float *fac = calloc(2 * LFAC_1024, sizeof(float));
    float *output = calloc(n, sizeof(float));
    float prev_trans_len[2] = {0, 0};
    int prev_start_stop[2] = {0, 0};
    float tw_trans_len[2];
    int tw_start_stop[2];
    int tw_ratio[NUM_TW_NODES];
    for (int i = 0; i < 2 * n; i++) {
        warp_cont_mem[i] = 1.0f;
    }
    warp_sum[0] = warp_sum[1] = (float) n;
    WINDOW_SEQUENCE last = ONLY_LONG_SEQUENCE;
    WINDOW_SHAPE lastShape = WS_FHG;
    int lastTd = 0;
    for (int frame = 0; frame < frames; frame++) {
        int td = 0;
        if (lpd && (lastTd || last == LONG_START_SEQUENCE || last == EIGHT_SHORT_SEQUENCE
                    || last == STOP_START_SEQUENCE)) {
            td = next() % 2 == 0;
        }
        if (td) {
            int mod0 = next() % 2 == 0 ? 0 : 1;
            for (int i = 0; i < n; i++) {
                synth[i] = sample();
            }
            if (!lastTd) {
                tw_past(overlap, n, lastShape, last, prev_sample_pos, prev_trans_len, prev_start_stop, prev_warped);
                fwrite(overlap, sizeof(float), 2 * n, out);
            }
            tw_td2buffer(synth, output, overlap, n, last, mod0, lastTd);
            fwrite(output, sizeof(float), n, out);
            lastShape = WS_FHG;
            last = EIGHT_SHORT_SEQUENCE;
            lastTd = 1;
            continue;
        }
        WINDOW_SEQUENCE allowed[3];
        int count;
        switch (last) {
            case ONLY_LONG_SEQUENCE:
            case LONG_STOP_SEQUENCE:
                allowed[0] = ONLY_LONG_SEQUENCE;
                allowed[1] = LONG_START_SEQUENCE;
                count = 2;
                break;
            default:
                allowed[0] = EIGHT_SHORT_SEQUENCE;
                allowed[1] = LONG_STOP_SEQUENCE;
                allowed[2] = STOP_START_SEQUENCE;
                count = 3;
                break;
        }
        WINDOW_SEQUENCE sequence = allowed[next() % count];
        WINDOW_SHAPE shape = (WINDOW_SHAPE) (next() & 1);
        int present = next() % 4 != 0;
        for (int i = 0; i < NUM_TW_NODES; i++) {
            tw_ratio[i] = present ? (int) (next() % 8) : 0;
        }
        for (int i = 0; i < 2 * n; i++) {
            transform[i] = sample();
        }
        int applyFac = 0;
        if (lastTd) {
            /* decoder_LPD_end */
            for (int i = 0; i < n; i++) {
                overlap[i + n / 2] = i < n / 2 + n / 8 ? sample() : 0.0f;
            }
            applyFac = next() % 2 == 0;
            int lfac = sequence == EIGHT_SHORT_SEQUENCE ? n / 16 : n / 8;
            for (int i = 0; i < 2 * lfac; i++) {
                fac[i] = applyFac ? sample() : 0.0f;
            }
            tw_reset(n, warp_cont_mem, warp_sum);
        }
        tw_calc_tw(present, lastTd, tw_ratio, sequence, warp_cont_mem, sample_pos, tw_trans_len, tw_start_stop,
                   warp_sum, n);
        tw_adjust_past(sequence, last, lastTd, prev_start_stop, prev_trans_len, n);
        tw_fd2buffer(transform, output, overlap, sequence, last, n, shape, lastShape, lastTd, sample_pos,
                     tw_trans_len, tw_start_stop, prev_sample_pos, prev_trans_len, prev_start_stop, prev_warped,
                     applyFac, fac);
        fwrite(output, sizeof(float), n, out);
        last = sequence;
        lastShape = shape;
        lastTd = 0;
    }
    fclose(out);
    return 0;
}
