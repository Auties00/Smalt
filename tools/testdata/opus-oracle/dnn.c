/* Reference harness of the libopus 1.5.2 neural extensions (deep PLC, DRED, OSCE), built by build.py with
   ENABLE_DEEP_PLC, ENABLE_DRED and ENABLE_OSCE and the generic C code of the networks.

   dnn enc <in.pcm> <out.bit> <rate> <channels> <voip|audio|lowdelay> <bitrate> <vbr|cvbr|cbr> <complexity>
           <frame_us> <signal auto|voice|music> <maxbw auto|nb|mb|wb|swb|fb> <fec 0|1> <loss pct> <dtx 0|1>
           <dred frames>
       Encodes interleaved s16le PCM, scaled to float, with opus_encoder_create and opus_encode_float, padded with
       silence as msenc.c does, and writes the packets in opus_demo form (length BE32, final range BE32,
       payload).

   dnn dec <in.bit> <out> <rate> <channels> <complexity> <loss pct> <seed> <plc|dred|fec> <f32|s16>
       Decodes an opus_demo bitstream with opus_decoder_create at the given complexity. Packet i (never the
       first) is lost when (i * 37 + seed) % 100 < pct. plc conceals each lost packet (a span of its own
       duration); fec rebuilds the last lost packet from the in-band FEC of the next packet; dred parses the
       Deep REDundancy of the first packet after a gap with opus_dred_parse (at most the gap, at most 1 s)
       and rebuilds each lost packet with opus_decoder_dred_decode(_float) at the distance of its first sample
       before that packet, concealing when the packet carries none, as opus_demo does. Lost packets at the
       end are concealed. Writes the interleaved output (float or 16-bit, little endian) and prints, for dred,
       the number of DRED rebuilds and the samples each parse made available. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"

static void be32(FILE *f, unsigned v) {
    unsigned char b[4] = {(unsigned char)(v >> 24), (unsigned char)(v >> 16), (unsigned char)(v >> 8), (unsigned char)v};
    fwrite(b, 1, 4, f);
}

static unsigned rd32(const unsigned char *b) {
    return (unsigned)b[0] << 24 | (unsigned)b[1] << 16 | (unsigned)b[2] << 8 | b[3];
}

static int lost(int i, int pct, int seed) {
    return i > 0 && (i * 37 + seed) % 100 < pct;
}

static int encode(int argc, char **argv) {
    if (argc < 17) {
        fprintf(stderr, "usage\n");
        return 2;
    }
    FILE *in = fopen(argv[2], "rb");
    fseek(in, 0, SEEK_END);
    long bytes = ftell(in);
    fseek(in, 0, SEEK_SET);
    short *pcm = malloc(bytes);
    fread(pcm, 1, bytes, in);
    fclose(in);
    FILE *out = fopen(argv[3], "wb");
    int rate = atoi(argv[4]);
    int channels = atoi(argv[5]);
    int app = strcmp(argv[6], "voip") == 0 ? OPUS_APPLICATION_VOIP
            : strcmp(argv[6], "lowdelay") == 0 ? OPUS_APPLICATION_RESTRICTED_LOWDELAY : OPUS_APPLICATION_AUDIO;
    int bitrate = atoi(argv[7]);
    int vbr = strcmp(argv[8], "cbr") != 0;
    int cvbr = strcmp(argv[8], "cvbr") == 0;
    int complexity = atoi(argv[9]);
    int frame = (int)((long long)rate * atoi(argv[10]) / 1000000);
    const char *signal = argv[11];
    const char *maxbw = argv[12];
    int fec = atoi(argv[13]);
    int loss = atoi(argv[14]);
    int dtx = atoi(argv[15]);
    int dred = atoi(argv[16]);
    int err;
    OpusEncoder *enc = opus_encoder_create(rate, channels, app, &err);
    if (err != OPUS_OK) {
        fprintf(stderr, "encoder: %s\n", opus_strerror(err));
        return 1;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(vbr));
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(cvbr));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(strcmp(signal, "voice") == 0 ? OPUS_SIGNAL_VOICE
            : strcmp(signal, "music") == 0 ? OPUS_SIGNAL_MUSIC : OPUS_AUTO));
    if (strcmp(maxbw, "auto") != 0 && strcmp(maxbw, "fb") != 0) {
        int bw = strcmp(maxbw, "nb") == 0 ? OPUS_BANDWIDTH_NARROWBAND : strcmp(maxbw, "mb") == 0 ? OPUS_BANDWIDTH_MEDIUMBAND
                : strcmp(maxbw, "wb") == 0 ? OPUS_BANDWIDTH_WIDEBAND : OPUS_BANDWIDTH_SUPERWIDEBAND;
        opus_encoder_ctl(enc, OPUS_SET_MAX_BANDWIDTH(bw));
    }
    if (fec) opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    if (loss) opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(loss));
    if (dtx) opus_encoder_ctl(enc, OPUS_SET_DTX(1));
    if (opus_encoder_ctl(enc, OPUS_SET_DRED_DURATION(dred)) != OPUS_OK) {
        fprintf(stderr, "DRED unavailable\n");
        return 1;
    }
    int lookahead;
    opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD(&lookahead));
    long samples = bytes / 2 / channels;
    long total = samples + lookahead;
    float *buf = calloc((size_t)frame * channels, sizeof(float));
    static unsigned char packet[1276 * 6];
    for (long pos = 0; pos < total; pos += frame) {
        for (int i = 0; i < frame * channels; i++) {
            long s = pos * channels + i;
            buf[i] = (1.0f / 32768) * (s < samples * channels ? pcm[s] : 0);
        }
        int len = opus_encode_float(enc, buf, frame, packet, sizeof(packet));
        if (len < 0) {
            fprintf(stderr, "encode: %s\n", opus_strerror(len));
            return 1;
        }
        opus_uint32 range;
        opus_encoder_ctl(enc, OPUS_GET_FINAL_RANGE(&range));
        be32(out, (unsigned)len);
        be32(out, range);
        fwrite(packet, 1, len, out);
    }
    fclose(out);
    printf("%d\n", lookahead);
    return 0;
}

static int decode(int argc, char **argv) {
    if (argc < 11) {
        fprintf(stderr, "usage\n");
        return 2;
    }
    FILE *in = fopen(argv[2], "rb");
    fseek(in, 0, SEEK_END);
    long bytes = ftell(in);
    fseek(in, 0, SEEK_SET);
    unsigned char *bits = malloc(bytes);
    fread(bits, 1, bytes, in);
    fclose(in);
    FILE *out = fopen(argv[3], "wb");
    int rate = atoi(argv[4]);
    int channels = atoi(argv[5]);
    int complexity = atoi(argv[6]);
    int pct = atoi(argv[7]);
    int seed = atoi(argv[8]);
    const char *mode = argv[9];
    int s16 = strcmp(argv[10], "s16") == 0;
    int err;
    OpusDecoder *dec = opus_decoder_create(rate, channels, &err);
    if (err != OPUS_OK || opus_decoder_ctl(dec, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK) {
        fprintf(stderr, "decoder\n");
        return 1;
    }
    OpusDREDDecoder *dred_dec = opus_dred_decoder_create(&err);
    OpusDRED *dred = opus_dred_alloc(&err);
    int max = rate / 1000 * 120 * 10;
    float *f = malloc(sizeof(float) * max * channels);
    short *s = malloc(sizeof(short) * max * channels);
    long pos = 0;
    int index = 0;
    int lost_count = 0;
    int rebuilt = 0;
    while (pos + 8 <= bytes) {
        int len = (int)rd32(bits + pos);
        const unsigned char *data = bits + pos + 8;
        pos += 8 + len;
        int duration = opus_packet_get_nb_samples(data, len, rate);
        if (lost(index++, pct, seed)) {
            if (strcmp(mode, "plc") == 0) {
                int n = s16 ? opus_decode(dec, NULL, 0, s, duration, 0) : opus_decode_float(dec, NULL, 0, f, duration, 0);
                if (n < 0) { fprintf(stderr, "plc: %s\n", opus_strerror(n)); return 1; }
                if (s16) fwrite(s, sizeof(short), (size_t)n * channels, out); else fwrite(f, sizeof(float), (size_t)n * channels, out);
            } else {
                lost_count++;
            }
            continue;
        }
        if (lost_count > 0) {
            opus_int32 dur;
            opus_decoder_ctl(dec, OPUS_GET_LAST_PACKET_DURATION(&dur));
            int dred_input = 0;
            if (strcmp(mode, "dred") == 0) {
                int dred_end = 0;
                int want = lost_count * dur;
                int ret = opus_dred_parse(dred_dec, dred, data, len, want < 48000 ? want : 48000, rate, &dred_end, 0);
                dred_input = ret > 0 ? ret : 0;
                printf("parse %d %d %d\n", index - 1, ret, dred_end);
            }
            for (int fr = 0; fr < lost_count; fr++) {
                int n;
                if (strcmp(mode, "fec") == 0 && fr == lost_count - 1 && opus_packet_has_lbrr(data, len)) {
                    n = s16 ? opus_decode(dec, data, len, s, dur, 1) : opus_decode_float(dec, data, len, f, dur, 1);
                } else if (dred_input > 0) {
                    n = s16 ? opus_decoder_dred_decode(dec, dred, (lost_count - fr) * dur, s, dur)
                            : opus_decoder_dred_decode_float(dec, dred, (lost_count - fr) * dur, f, dur);
                    rebuilt++;
                } else {
                    n = s16 ? opus_decode(dec, NULL, 0, s, dur, 0) : opus_decode_float(dec, NULL, 0, f, dur, 0);
                }
                if (n < 0) { fprintf(stderr, "rebuild: %s\n", opus_strerror(n)); return 1; }
                if (s16) fwrite(s, sizeof(short), (size_t)n * channels, out); else fwrite(f, sizeof(float), (size_t)n * channels, out);
            }
            lost_count = 0;
        }
        int n = s16 ? opus_decode(dec, data, len, s, max, 0) : opus_decode_float(dec, data, len, f, max, 0);
        if (n < 0) { fprintf(stderr, "decode: %s\n", opus_strerror(n)); return 1; }
        if (s16) fwrite(s, sizeof(short), (size_t)n * channels, out); else fwrite(f, sizeof(float), (size_t)n * channels, out);
    }
    for (; lost_count > 0; lost_count--) {
        opus_int32 dur;
        opus_decoder_ctl(dec, OPUS_GET_LAST_PACKET_DURATION(&dur));
        int n = s16 ? opus_decode(dec, NULL, 0, s, dur, 0) : opus_decode_float(dec, NULL, 0, f, dur, 0);
        if (n < 0) { fprintf(stderr, "plc: %s\n", opus_strerror(n)); return 1; }
        if (s16) fwrite(s, sizeof(short), (size_t)n * channels, out); else fwrite(f, sizeof(float), (size_t)n * channels, out);
    }
    fclose(out);
    printf("rebuilt %d\n", rebuilt);
    return 0;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "enc") == 0) return encode(argc, argv);
    if (argc > 1 && strcmp(argv[1], "dec") == 0) return decode(argc, argv);
    fprintf(stderr, "usage: dnn enc|dec ...\n");
    return 2;
}
