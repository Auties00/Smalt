/* The libvorbis 1.3.7 reference harness of the Vorbis encoder goldens (built by build.py). Encodes planar
   float32 little-endian input (channels in Vorbis order, one plane after the other) the way VorbisEncoder feeds
   libvorbis, in 1024-sample chunks, and writes the identification, comment and setup headers and every audio
   packet, each prefixed by its length as a little-endian uint32. Usage:
     vbm <in.f32> <channels> <rate> <length> <out.bin> vbr q=<-0.1..1>
     vbm ... qlim q=<-0.1..1> [min=<kbit/s>] [max=<kbit/s>] [res=<bits>] [bias=<0..1>] [damp=<s>]
     vbm ... managed nom=<bit/s> [min=<bit/s>] [max=<bit/s>] [res=<bits>] [bias=<0..1>] [damp=<s>] [noavg=1]
   An absent limit is passed to libvorbis as 0. vbr is vorbis_encode_setup_vbr; qlim adds bitrate management
   through OV_ECTL_RATEMANAGE2_SET (limits in kbit/s, no average), with a default reservoir of twice the
   approximate bitrate of the quality setting; managed is vorbis_encode_setup_managed(max, nom, min), the
   average bitrate being nom, followed by OV_ECTL_RATEMANAGE2_GET and SET when res, bias, damp or noavg (average
   tracking off, for limits at a nominal bitrate) is given. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vorbis/vorbisenc.h>

static double arg(int argc, char **argv, const char *key, double fallback) {
  size_t n = strlen(key);
  for (int i = 7; i < argc; i++) {
    if (strncmp(argv[i], key, n) == 0 && argv[i][n] == '=') return atof(argv[i] + n + 1);
  }
  return fallback;
}

static void put(FILE *out, const unsigned char *data, long bytes) {
  unsigned char len[4] = {bytes & 255, (bytes >> 8) & 255, (bytes >> 16) & 255, (bytes >> 24) & 255};
  fwrite(len, 1, 4, out);
  fwrite(data, 1, bytes, out);
}

int main(int argc, char **argv) {
  if (argc < 7) { fprintf(stderr, "usage\n"); return 2; }
  int channels = atoi(argv[2]);
  long rate = atol(argv[3]);
  long length = atol(argv[4]);
  const char *mode = argv[6];
  float *samples = malloc(sizeof(float) * channels * length);
  FILE *in = fopen(argv[1], "rb");
  if (!in || fread(samples, sizeof(float), channels * length, in) != (size_t)(channels * length)) {
    fprintf(stderr, "cannot read input\n"); return 2;
  }
  fclose(in);

  vorbis_info vi;
  vorbis_info_init(&vi);
  int ret;
  if (strcmp(mode, "vbr") == 0) {
    ret = vorbis_encode_setup_vbr(&vi, channels, rate, (float) arg(argc, argv, "q", 0.3));
  } else if (strcmp(mode, "qlim") == 0) {
    float q = (float) arg(argc, argv, "q", 0.3);
    vorbis_info probe;
    vorbis_info_init(&probe);
    if (vorbis_encode_init_vbr(&probe, channels, rate, q)) { fprintf(stderr, "probe failed\n"); return 3; }
    long approx = probe.bitrate_nominal;
    vorbis_info_clear(&probe);
    ret = vorbis_encode_setup_vbr(&vi, channels, rate, q);
    if (!ret) {
      struct ovectl_ratemanage2_arg ai;
      memset(&ai, 0, sizeof(ai));
      ai.management_active = 1;
      ai.bitrate_limit_min_kbps = (long) arg(argc, argv, "min", 0);
      ai.bitrate_limit_max_kbps = (long) arg(argc, argv, "max", 0);
      ai.bitrate_average_kbps = 0;
      ai.bitrate_average_damping = arg(argc, argv, "damp", 1.5);
      ai.bitrate_limit_reservoir_bits = (long) arg(argc, argv, "res", approx * 2.0);
      ai.bitrate_limit_reservoir_bias = arg(argc, argv, "bias", .1);
      ret = vorbis_encode_ctl(&vi, OV_ECTL_RATEMANAGE2_SET, &ai);
    }
  } else if (strcmp(mode, "managed") == 0) {
    ret = vorbis_encode_setup_managed(&vi, channels, rate, (long) arg(argc, argv, "max", 0),
                                      (long) arg(argc, argv, "nom", 0), (long) arg(argc, argv, "min", 0));
    if (!ret && (arg(argc, argv, "res", -1) >= 0 || arg(argc, argv, "bias", -1) >= 0
                 || arg(argc, argv, "damp", -1) >= 0 || arg(argc, argv, "noavg", 0) != 0)) {
      struct ovectl_ratemanage2_arg ai;
      ret = vorbis_encode_ctl(&vi, OV_ECTL_RATEMANAGE2_GET, &ai);
      if (arg(argc, argv, "res", -1) >= 0) ai.bitrate_limit_reservoir_bits = (long) arg(argc, argv, "res", 0);
      if (arg(argc, argv, "bias", -1) >= 0) ai.bitrate_limit_reservoir_bias = arg(argc, argv, "bias", 0);
      if (arg(argc, argv, "damp", -1) >= 0) ai.bitrate_average_damping = arg(argc, argv, "damp", 0);
      if (arg(argc, argv, "noavg", 0) != 0) ai.bitrate_average_kbps = 0;
      if (!ret) ret = vorbis_encode_ctl(&vi, OV_ECTL_RATEMANAGE2_SET, &ai);
    }
  } else {
    fprintf(stderr, "unknown mode %s\n", mode); return 2;
  }
  if (ret || (ret = vorbis_encode_setup_init(&vi))) { fprintf(stderr, "setup failed %d\n", ret); return 3; }

  vorbis_dsp_state vd;
  vorbis_block vb;
  vorbis_comment vc;
  vorbis_comment_init(&vc);
  vorbis_analysis_init(&vd, &vi);
  vorbis_block_init(&vd, &vb);
  ogg_packet h0, h1, h2, op;
  vorbis_analysis_headerout(&vd, &vc, &h0, &h1, &h2);
  FILE *out = fopen(argv[5], "wb");
  put(out, h0.packet, h0.bytes);
  put(out, h1.packet, h1.bytes);
  put(out, h2.packet, h2.bytes);
  long position = 0;
  for (;;) {
    long count = length - position < 1024 ? length - position : 1024;
    if (count > 0) {
      float **buffer = vorbis_analysis_buffer(&vd, (int) count);
      for (int c = 0; c < channels; c++) memcpy(buffer[c], samples + (size_t) c * length + position, sizeof(float) * count);
      vorbis_analysis_wrote(&vd, (int) count);
      position += count;
      if (count < 1024) vorbis_analysis_wrote(&vd, 0);
    } else {
      vorbis_analysis_wrote(&vd, 0);
    }
    while (vorbis_analysis_blockout(&vd, &vb) == 1) {
      vorbis_analysis(&vb, NULL);
      vorbis_bitrate_addblock(&vb);
      while (vorbis_bitrate_flushpacket(&vd, &op)) put(out, op.packet, op.bytes);
    }
    if (count < 1024) break;
  }
  fclose(out);
  vorbis_block_clear(&vb);
  vorbis_dsp_clear(&vd);
  vorbis_comment_clear(&vc);
  vorbis_info_clear(&vi);
  free(samples);
  return 0;
}
