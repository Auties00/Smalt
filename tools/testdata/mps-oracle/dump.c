/* Prints the mapped parameters of the refsoft MPS decoder after each frame (MPSREF_DUMP), and reads the number of
   channels it outputs, the channels of the arbitrary tree when the configuration has one. */
#include <stdio.h>
#include "sac_dec.h"

int mpsref_output_channels(void *decoder) {
    return ((spatialDec *) decoder)->numOutputChannelsAT;
}

void mpsref_dump(void *decoder, int f) {
    spatialDec *d = decoder;
    printf("F %d sets %d slots", f, d->numParameterSets);
    for (int i = 0; i < d->numParameterSets; i++) printf(" %d", d->paramSlot[i]);
    printf("\n");
    for (int b = 0; b < d->numOttBoxes; b++) for (int ps = 0; ps < d->numParameterSets; ps++) {
        printf("CLD %d %d", b, ps);
        for (int pb = 0; pb < d->numParameterBands; pb++) printf(" %g", d->ottCLD[b][ps][pb]);
        printf("\nICC %d %d", b, ps);
        for (int pb = 0; pb < d->numParameterBands; pb++) printf(" %g", d->ottICC[b][ps][pb]);
        printf("\n");
    }
    for (int ps = 0; ps < d->numParameterSets; ps++) {
        printf("SMG %d %d", ps, d->smgTime[ps]);
        for (int pb = 0; pb < d->numParameterBands; pb++) printf(" %d", d->smgData[ps][pb]);
        printf("\n");
    }
    fflush(stdout);
}
