/* Oracle for the USAC time-warped filterbank, over the ISO/IEC 23003-3 reference software.

   twref <frame length 768|1024> <frames> <seed> <out.f32>

   Drives tw_calc_tw, tw_adjust_past (usac_tw_tools.c) and the time-warped branch of fd2buffer (usac_imdct.c,
   copied below for frequency domain frames that follow frequency domain frames, without FAC) over pseudo-random
   window sequences, window shapes, warp ratios and transform outputs, the way decodeChannelElement
   (decode_chan_ele.c) calls them, and writes the output samples of every frame as float32 little endian. The
   inputs come from a xorshift32 generator so that a test can rebuild them:

     state ^= state << 13; state ^= state >> 17; state ^= state << 5;
     sample = (float) (int32) state * (1.0f / 2147483648.0f) * 8192.0f

   Per frame, in this order: the window sequence (next() % count among the sequences allowed after the last
   one, in the order of WINDOW_SEQUENCE), the window shape (next() & 1), tw_data_present (next() % 4 != 0) and,
   when present, the 16 tw_ratio values (next() % 8), then the 2 * frame length transform samples. The copied
   code is Copyright Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008, under the ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). */
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

static const float zero = 0.0f;

/* The time-warped branch of fd2buffer (usac_imdct.c) with FrameWasTd = 0, ApplyFac = 0, sbrSyncDelay = 0, and the
   transform output given instead of computed. */
static void tw_fd2buffer(const float transform[], float p_out_data[], float p_overlap[],
                         WINDOW_SEQUENCE windowSequence, WINDOW_SEQUENCE windowSequenceLast, int nlong,
                         WINDOW_SHAPE wfun_select, WINDOW_SHAPE wfun_select_prev, float sample_pos[],
                         float tw_trans_len[], int tw_start_stop[], float past_sample_pos[],
                         float past_tw_trans_len[], int past_tw_start_stop[],
                         float past_warped_time_sample_vector[]) {
    static float transf_buf[2 * MAX_SHIFT_LEN_LONG];
    static float transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    static float overlap_buf[2 * MAX_SHIFT_LEN_LONG];
    static float os_window[MAX_SHIFT_LEN_LONG * TW_OS_FACTOR_WIN];
    static float os_window_prev[MAX_SHIFT_LEN_LONG * TW_OS_FACTOR_WIN];
    static float tw_transf_buf[2 * MAX_SHIFT_LEN_LONG];
    static float tw_transf_buf_past[2 * MAX_SHIFT_LEN_LONG];
    float *o_buf;
    int nmdct = nlong;
    int nmdct_past = nlong;

    memset(transf_buf, 0, sizeof transf_buf);
    memset(transf_buf_past, 0, sizeof transf_buf_past);
    memset(overlap_buf, 0, sizeof overlap_buf);
    calc_tw_window(os_window, nlong * TW_OS_FACTOR_WIN, wfun_select);
    calc_tw_window(os_window_prev, nlong * TW_OS_FACTOR_WIN, wfun_select_prev);
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
    if (windowSequenceLast != EIGHT_SHORT_SEQUENCE) {
        tw_windowing_past(tw_transf_buf_past + TW_IPLEN2S, tw_transf_buf_past + TW_IPLEN2S, past_tw_start_stop[1],
                          nmdct_past, past_tw_trans_len[1], os_window_prev);
    }
    tw_resamp(tw_transf_buf_past, nlong + (LFAC_1024 * nlong) / L_FRAME_1024, 2 * nmdct_past + 2 * TW_IPLEN2S,
              3 * nlong, past_sample_pos, 0.5f, transf_buf_past);
    vadd(transf_buf_past + nlong + (LFAC_1024 * nlong) / L_FRAME_1024, o_buf + (LFAC_1024 * nlong) / L_FRAME_1024,
         o_buf + (LFAC_1024 * nlong) / L_FRAME_1024, 1, 1, 1, 2 * nlong - (LFAC_1024 * nlong) / L_FRAME_1024);
    vadd(transf_buf, o_buf, o_buf, 1, 1, 1, nlong + (LFAC_1024 * nlong) / L_FRAME_1024);
    vcopy(o_buf, p_out_data, 1, 1, nlong);
    vcopy(o_buf + nlong, p_overlap, 1, 1, 2 * nlong);
    vcopy(tw_transf_buf, past_warped_time_sample_vector, 1, 1, 2 * nlong + 2 * TW_IPLEN2S);
    vcopy(sample_pos, past_sample_pos, 1, 1, 3 * nlong);
    past_tw_trans_len[0] = tw_trans_len[0];
    past_tw_trans_len[1] = tw_trans_len[1];
    past_tw_start_stop[0] = tw_start_stop[0];
    past_tw_start_stop[1] = tw_start_stop[1];
}

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: twref <frame length> <frames> <seed> <out.f32>\n");
        return 1;
    }
    int n = atoi(argv[1]);
    int frames = atoi(argv[2]);
    state = (uint32_t) strtoul(argv[3], NULL, 10);
    FILE *out = fopen(argv[4], "wb");
    if (!out || (n != 768 && n != 1024) || frames <= 0 || state == 0) {
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
    for (int frame = 0; frame < frames; frame++) {
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
            transform[i] = (float) (int32_t) next() * (1.0f / 2147483648.0f) * 8192.0f;
        }
        tw_calc_tw(present, 0, tw_ratio, sequence, warp_cont_mem, sample_pos, tw_trans_len, tw_start_stop,
                   warp_sum, n);
        tw_adjust_past(sequence, last, 0, prev_start_stop, prev_trans_len, n);
        tw_fd2buffer(transform, output, overlap, sequence, last, n, shape, lastShape, sample_pos, tw_trans_len,
                     tw_start_stop, prev_sample_pos, prev_trans_len, prev_start_stop, prev_warped);
        fwrite(output, sizeof(float), n, out);
        last = sequence;
        lastShape = shape;
    }
    fclose(out);
    return 0;
}
