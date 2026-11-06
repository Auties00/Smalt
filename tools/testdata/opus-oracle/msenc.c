/* Reference harness: encodes interleaved s16le PCM with libopus 1.5.2 and writes the packets in
   opus_demo form (length BE32, final range BE32, payload).

   msenc <in.pcm> <out.bit> <rate> <channels> <family|s> <voip|audio|lowdelay> <bitrate|auto|max>
         <vbr|cvbr|cbr> <complexity> <frame_us> [signal auto|voice|music] [maxbw auto|nb|mb|wb|swb|fb]
         [fec 0|1] [loss pct] [dtx 0|1] [s16 0|1] [decoded.f32]

   Family "s" uses the single-stream encoder and family 3 the projection encoder
   (opus_projection_ambisonics_encoder_create), whose packets the projection decoder demixes with
   the demixing matrix gain set as its output gain. The input is padded with silence until the coded
   length reaches the input length plus the look-ahead. Writes streams, coupled streams, look-ahead
   and the mapping (for family 3, the demixing gain and matrix in hex) to stdout. With a last argument, also decodes every packet with the libopus
   decoder (opus_decode_float, opus_multistream_decode_float or opus_projection_decode_float, at
   the input rate) and writes the
   interleaved float output there. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"
#include "opus_multistream.h"
#include "opus_projection.h"

static void be32(FILE *f, unsigned v) {
    unsigned char b[4] = {(unsigned char)(v >> 24), (unsigned char)(v >> 16), (unsigned char)(v >> 8), (unsigned char)v};
    fwrite(b, 1, 4, f);
}

int main(int argc, char **argv) {
    if (argc < 11) {
        fprintf(stderr, "usage\n");
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    fseek(in, 0, SEEK_END);
    long bytes = ftell(in);
    fseek(in, 0, SEEK_SET);
    short *pcm = malloc(bytes);
    fread(pcm, 1, bytes, in);
    fclose(in);
    FILE *out = fopen(argv[2], "wb");
    int rate = atoi(argv[3]);
    int channels = atoi(argv[4]);
    int single = strcmp(argv[5], "s") == 0;
    int family = single ? 0 : atoi(argv[5]);
    int app = strcmp(argv[6], "voip") == 0 ? OPUS_APPLICATION_VOIP
            : strcmp(argv[6], "audio") == 0 ? OPUS_APPLICATION_AUDIO : OPUS_APPLICATION_RESTRICTED_LOWDELAY;
    int bitrate = strcmp(argv[7], "auto") == 0 ? OPUS_AUTO : strcmp(argv[7], "max") == 0 ? OPUS_BITRATE_MAX : atoi(argv[7]);
    int vbr = strcmp(argv[8], "cbr") != 0;
    int cvbr = strcmp(argv[8], "cvbr") == 0;
    int complexity = atoi(argv[9]);
    int frame = (int)((long long)rate * atoi(argv[10]) / 1000000);
    const char *signal = argc > 11 ? argv[11] : "auto";
    const char *maxbw = argc > 12 ? argv[12] : "auto";
    int fec = argc > 13 ? atoi(argv[13]) : 0;
    int loss = argc > 14 ? atoi(argv[14]) : 0;
    int dtx = argc > 15 ? atoi(argv[15]) : 0;
    int s16 = argc > 16 ? atoi(argv[16]) : 0;
    int err = 0;
    OpusEncoder *enc = NULL;
    OpusMSEncoder *ms = NULL;
    OpusProjectionEncoder *proj = NULL;
    int projection = !single && family == 3;
    static unsigned char demixing[255 * 255 * 2];
    opus_int32 demixing_size = 0, demixing_gain = 0;
    int streams = 1, coupled = channels - 1;
    unsigned char mapping[256] = {0, 1};
    if (single) {
        enc = opus_encoder_create(rate, channels, app, &err);
    } else if (projection) {
        proj = opus_projection_ambisonics_encoder_create(rate, channels, 3, &streams, &coupled, app, &err);
    } else {
        ms = opus_multistream_surround_encoder_create(rate, channels, family, &streams, &coupled, mapping, app, &err);
    }
    if (err != OPUS_OK) {
        fprintf(stderr, "create failed %d\n", err);
        return 1;
    }
#define CTL(x) (single ? opus_encoder_ctl(enc, x) : projection ? opus_projection_encoder_ctl(proj, x) \
        : opus_multistream_encoder_ctl(ms, x))
    CTL(OPUS_SET_BITRATE(bitrate));
    CTL(OPUS_SET_VBR(vbr));
    CTL(OPUS_SET_VBR_CONSTRAINT(cvbr));
    CTL(OPUS_SET_COMPLEXITY(complexity));
    CTL(OPUS_SET_SIGNAL(strcmp(signal, "voice") == 0 ? OPUS_SIGNAL_VOICE : strcmp(signal, "music") == 0 ? OPUS_SIGNAL_MUSIC : OPUS_AUTO));
    if (strcmp(maxbw, "auto") != 0 && strcmp(maxbw, "fb") != 0) {
        int bw = strcmp(maxbw, "nb") == 0 ? OPUS_BANDWIDTH_NARROWBAND : strcmp(maxbw, "mb") == 0 ? OPUS_BANDWIDTH_MEDIUMBAND
                : strcmp(maxbw, "wb") == 0 ? OPUS_BANDWIDTH_WIDEBAND : OPUS_BANDWIDTH_SUPERWIDEBAND;
        CTL(OPUS_SET_MAX_BANDWIDTH(bw));
    }
    if (fec) CTL(OPUS_SET_INBAND_FEC(1));
    if (loss) CTL(OPUS_SET_PACKET_LOSS_PERC(loss));
    if (dtx) CTL(OPUS_SET_DTX(1));
    opus_int32 lookahead;
    CTL(OPUS_GET_LOOKAHEAD(&lookahead));
    if (projection) {
        opus_projection_encoder_ctl(proj, OPUS_PROJECTION_GET_DEMIXING_MATRIX_SIZE(&demixing_size));
        opus_projection_encoder_ctl(proj, OPUS_PROJECTION_GET_DEMIXING_MATRIX_GAIN(&demixing_gain));
        opus_projection_encoder_ctl(proj, OPUS_PROJECTION_GET_DEMIXING_MATRIX(demixing, demixing_size));
    }
    FILE *decoded = argc > 17 ? fopen(argv[17], "wb") : NULL;
    OpusDecoder *dec = NULL;
    OpusMSDecoder *msdec = NULL;
    OpusProjectionDecoder *projdec = NULL;
    float *pcmout = malloc(sizeof(float) * 5760 * channels);
    if (decoded) {
        if (single) {
            dec = opus_decoder_create(rate, channels, &err);
        } else if (projection) {
            projdec = opus_projection_decoder_create(rate, channels, streams, coupled, demixing, demixing_size, &err);
            if (err == OPUS_OK) {
                opus_projection_decoder_ctl(projdec, OPUS_SET_GAIN(demixing_gain));
            }
        } else {
            msdec = opus_multistream_decoder_create(rate, channels, streams, coupled, mapping, &err);
        }
        if (err != OPUS_OK) {
            fprintf(stderr, "decoder create failed %d\n", err);
            return 1;
        }
    }
    long total = bytes / 2 / channels;
    long long target = total + lookahead;
    float *buf = malloc(sizeof(float) * frame * channels);
    short *sbuf = malloc(sizeof(short) * frame * channels);
    static unsigned char packet[1275 * 6 * 255 + 7 * 255];
    long long coded = 0, position = 0;
    int count = 0;
    while (coded < target && total > 0) {
        for (int i = 0; i < frame * channels; i++) {
            long long s = position * channels + i;
            short v = s < (long long)total * channels ? pcm[s] : 0;
            buf[i] = (1.0f / 32768) * v;
            sbuf[i] = v;
        }
        position += frame;
        int len;
        opus_uint32 range;
        int cap = streams * (6 * 1275 + 12 + 2);
        if (single) {
            len = s16 ? opus_encode(enc, sbuf, frame, packet, 1276 * 6) : opus_encode_float(enc, buf, frame, packet, 1276 * 6);
        } else if (projection) {
            len = s16 ? opus_projection_encode(proj, sbuf, frame, packet, cap) : opus_projection_encode_float(proj, buf, frame, packet, cap);
        } else {
            len = s16 ? opus_multistream_encode(ms, sbuf, frame, packet, cap) : opus_multistream_encode_float(ms, buf, frame, packet, cap);
        }
        if (len < 0) {
            fprintf(stderr, "encode failed %d at %d\n", len, count);
            return 1;
        }
        CTL(OPUS_GET_FINAL_RANGE(&range));
        be32(out, (unsigned)len);
        be32(out, range);
        fwrite(packet, 1, len, out);
        if (decoded) {
            int samples = single ? opus_decode_float(dec, packet, len, pcmout, 5760, 0)
                    : projection ? opus_projection_decode_float(projdec, packet, len, pcmout, 5760, 0)
                    : opus_multistream_decode_float(msdec, packet, len, pcmout, 5760, 0);
            if (samples < 0) {
                fprintf(stderr, "decode failed %d at %d\n", samples, count);
                return 1;
            }
            fwrite(pcmout, sizeof(float), (size_t)samples * channels, decoded);
        }
        coded += frame;
        count++;
    }
    fclose(out);
    if (decoded) {
        fclose(decoded);
    }
    printf("streams=%d coupled=%d lookahead=%d packets=%d mapping=", streams, coupled, lookahead, count);
    if (projection) {
        printf("gain=%d demixing=", demixing_gain);
        for (int i = 0; i < demixing_size; i++) printf("%02x", demixing[i]);
        printf("\n");
    } else {
        for (int i = 0; i < channels; i++) printf("%d%s", mapping[i], i + 1 < channels ? "," : "\n");
    }
    return 0;
}
