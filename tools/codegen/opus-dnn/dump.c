/* Writes the libopus 1.5.2 DNN models as weight blobs, one file per model, in the record format of
   dnn/write_lpcnet_weights.c (a 64-byte WeightHead, "DNNw", version 0, type, size, block size and name,
   then the data padded to a multiple of 64 bytes).

   Usage: dump <output directory>

   Build it against the libopus 1.5.2 sources with the dnn, celt and include directories on the include
   path, linking dnn/parse_lpcnet_weights.c; regen.py does both. */
#include <stdio.h>
#include <string.h>
#include "nnet.h"
#include "os_support.h"
#include "arch.h"

#undef HAVE_CONFIG_H
#ifdef USE_WEIGHTS_FILE
#undef USE_WEIGHTS_FILE
#endif
#include "pitchdnn_data.c"
#include "fargan_data.c"
#include "plc_data.c"
#include "dred_rdovae_enc_data.c"
#include "dred_rdovae_dec_data.c"
#include "lace_data.c"
#include "nolace_data.c"

static int write_weights(const WeightArray *list, const char *dir, const char *file)
{
  int i = 0;
  char path[4096];
  FILE *fout;
  unsigned char zeros[WEIGHT_BLOCK_SIZE] = {0};
  snprintf(path, sizeof(path), "%s/%s", dir, file);
  fout = fopen(path, "wb");
  if (fout == NULL) {
    perror(path);
    return 1;
  }
  while (list[i].name != NULL) {
    WeightHead h;
    if (strlen(list[i].name) >= sizeof(h.name) - 1) {
      fprintf(stderr, "name %s too long\n", list[i].name);
      fclose(fout);
      return 1;
    }
    memset(&h, 0, sizeof(h));
    memcpy(h.head, "DNNw", 4);
    h.version = WEIGHT_BLOB_VERSION;
    h.type = list[i].type;
    h.size = list[i].size;
    h.block_size = (h.size + WEIGHT_BLOCK_SIZE - 1) / WEIGHT_BLOCK_SIZE * WEIGHT_BLOCK_SIZE;
    strncpy(h.name, list[i].name, sizeof(h.name) - 1);
    fwrite(&h, 1, WEIGHT_BLOCK_SIZE, fout);
    fwrite(list[i].data, 1, h.size, fout);
    fwrite(zeros, 1, h.block_size - h.size, fout);
    i++;
  }
  fclose(fout);
  return 0;
}

int main(int argc, char **argv)
{
  if (argc != 2) {
    fprintf(stderr, "usage: dump <output directory>\n");
    return 2;
  }
  return write_weights(pitchdnn_arrays, argv[1], "pitchdnn.bin")
      || write_weights(fargan_arrays, argv[1], "fargan.bin")
      || write_weights(plcmodel_arrays, argv[1], "plc.bin")
      || write_weights(rdovaeenc_arrays, argv[1], "rdovae-enc.bin")
      || write_weights(rdovaedec_arrays, argv[1], "rdovae-dec.bin")
      || write_weights(lacelayers_arrays, argv[1], "lace.bin")
      || write_weights(nolacelayers_arrays, argv[1], "nolace.bin");
}
