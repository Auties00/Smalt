/* Prints the mapped parameters of the refsoft MPS decoder after each frame (MPSREF_DUMP). */
#include <stdio.h>
#include "sac_dec.h"

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
