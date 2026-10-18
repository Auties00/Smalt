/* Serializes the libvorbis 1.3.7 encoder setup templates, codebooks and psychoacoustic tables
   (BSD-3, Xiph.Org) into a little-endian binary blob read by Smalt's Vorbis encoder. */
#include "libvorbis-1.3.7/lib/vorbisenc.c"
#include "libvorbis-1.3.7/lib/masking.h"
#include <stdio.h>
extern const float *_vorbis_window_get(int n);

extern long _book_maptype1_quantvals(const static_codebook *b);

static unsigned char *buf; static size_t len, cap;
static void put(const void *p, size_t n){ if(len+n>cap){cap=(cap+n)*2; buf=realloc(buf,cap);} memcpy(buf+len,p,n); len+=n; }
static void wi(int v){ put(&v,4); }
static void wf(float v){ put(&v,4); }
static void wd(double v){ put(&v,8); }
static void wis(const int *p, int n){ for(int i=0;i<n;i++) wi(p[i]); }
static void wds(const double *p, int n){ for(int i=0;i<n;i++) wd(p[i]); }

static const static_codebook *books[4096]; static int nbooks;
static int book(const static_codebook *b){
  if(!b) return -1;
  for(int i=0;i<nbooks;i++) if(books[i]==b) return i;
  books[nbooks]=b; return nbooks++;
}

static void psy_global(const vorbis_info_psy_global *g){
  wi(g->eighth_octave_lines);
  for(int i=0;i<VE_BANDS;i++) wf(g->preecho_thresh[i]);
  for(int i=0;i<VE_BANDS;i++) wf(g->postecho_thresh[i]);
  wf(g->stretch_penalty); wf(g->preecho_minenergy); wf(g->ampmax_att_per_sec);
  wis(g->coupling_pkHz,PACKETBLOBS);
  wis(g->coupling_pointlimit[0],PACKETBLOBS); wis(g->coupling_pointlimit[1],PACKETBLOBS);
  wis(g->coupling_prepointamp,PACKETBLOBS); wis(g->coupling_postpointamp,PACKETBLOBS);
  wis(g->sliding_lowpass[0],PACKETBLOBS); wis(g->sliding_lowpass[1],PACKETBLOBS);
}

static void floor1(const vorbis_info_floor1 *f){
  wi(f->partitions); wis(f->partitionclass,VIF_PARTS);
  wis(f->class_dim,VIF_CLASS); wis(f->class_subs,VIF_CLASS); wis(f->class_book,VIF_CLASS);
  for(int i=0;i<VIF_CLASS;i++) wis(f->class_subbook[i],8);
  wi(f->mult); wis(f->postlist,VIF_POSIT+2);
  wf(f->maxover); wf(f->maxunder); wf(f->maxerr); wf(f->twofitweight); wf(f->twofitatten); wi(f->n);
}

static void mapping0(const vorbis_info_mapping0 *m){
  wi(m->submaps); wis(m->chmuxlist,256); wis(m->floorsubmap,16); wis(m->residuesubmap,16);
  wi(m->coupling_steps); wis(m->coupling_mag,256); wis(m->coupling_ang,256);
}

static void residue0(const vorbis_info_residue0 *r){
  wi((int)r->begin); wi((int)r->end); wi(r->grouping); wi(r->partitions); wi(r->partvals); wi(r->groupbook);
  wis(r->secondstages,64); wis(r->booklist,512); wis(r->classmetric1,64); wis(r->classmetric2,64);
}

static void bookblock(const static_bookblock *b){
  for(int i=0;i<12;i++) for(int k=0;k<4;k++) wi(b?book(b->books[i][k]):-1);
}

static void psy_info(const vorbis_info_psy *p){
  wi(p->blockflag); wf(p->ath_adjatt); wf(p->ath_maxatt);
  for(int i=0;i<P_NOISECURVES;i++) wf(p->tone_masteratt[i]);
  wf(p->tone_centerboost); wf(p->tone_decay); wf(p->tone_abs_limit);
  for(int i=0;i<P_BANDS;i++) wf(p->toneatt[i]);
  wi(p->noisemaskp); wf(p->noisemaxsupp); wf(p->noisewindowlo); wf(p->noisewindowhi);
  wi(p->noisewindowlomin); wi(p->noisewindowhimin); wi(p->noisewindowfixed);
  for(int j=0;j<P_NOISECURVES;j++) for(int i=0;i<P_BANDS;i++) wf(p->noiseoff[j][i]);
  for(int i=0;i<NOISE_COMPAND_LEVELS;i++) wf(p->noisecompand[i]);
  wf(p->max_curve_dB); wi(p->normal_p); wi(p->normal_start); wi(p->normal_partition); wd(p->normal_thresh);
}

static int ceilmax(const double *a, int n){ double m=0; for(int i=0;i<n;i++) if(a[i]>m) m=a[i]; return (int)ceil(m); }

static void template(const ve_setup_data_template *t){
  int m=t->mappings;
  wi(m);
  wi(t->rate_mapping!=NULL); if(t->rate_mapping) wds(t->rate_mapping,m+1);
  wds(t->quality_mapping,m+1);
  wi(t->coupling_restriction); wi((int)t->samplerate_min_restriction); wi((int)t->samplerate_max_restriction);
  wis(t->blocksize_short,m); wis(t->blocksize_long,m);
  for(int i=0;i<=m;i++){ wis(t->psy_tone_masteratt[i].att,P_NOISECURVES); wf(t->psy_tone_masteratt[i].boost); wf(t->psy_tone_masteratt[i].decay); }
  wis(t->psy_tone_0dB,m+1); wis(t->psy_tone_dBsuppress,m+1);
  const vp_adjblock *adj[3]={t->psy_tone_adj_impulse,t->psy_tone_adj_long,t->psy_tone_adj_other};
  for(int k=0;k<3;k++){ wi(adj[k]!=NULL); if(adj[k]) for(int i=0;i<=m;i++) wis(adj[k][i].block,P_BANDS); }
  for(int i=0;i<4;i++){ wi(t->psy_noiseguards[i].lo); wi(t->psy_noiseguards[i].hi); wi(t->psy_noiseguards[i].fixed); }
  const noise3 *bias[4]={t->psy_noise_bias_impulse,t->psy_noise_bias_padding,t->psy_noise_bias_trans,t->psy_noise_bias_long};
  for(int k=0;k<4;k++){ wi(bias[k]!=NULL); if(bias[k]) for(int i=0;i<=m;i++) for(int j=0;j<P_NOISECURVES;j++) wis(bias[k][i].data[j],17); }
  wis(t->psy_noise_dBsuppress,m+1);
  int c=ceilmax(t->psy_noise_compand_short_mapping,m+1);
  if(t->psy_noise_compand_long_mapping){ int c2=ceilmax(t->psy_noise_compand_long_mapping,m+1); if(c2>c)c=c2; }
  wi(c+1); for(int i=0;i<=c;i++) wis(t->psy_noise_compand[i].data,NOISE_COMPAND_LEVELS);
  wds(t->psy_noise_compand_short_mapping,m+1);
  wi(t->psy_noise_compand_long_mapping!=NULL); if(t->psy_noise_compand_long_mapping) wds(t->psy_noise_compand_long_mapping,m+1);
  for(int k=0;k<2;k++){ wi(t->psy_noise_normal_start[k]!=NULL); if(t->psy_noise_normal_start[k]) wis(t->psy_noise_normal_start[k],m); }
  for(int k=0;k<2;k++){ wi(t->psy_noise_normal_partition[k]!=NULL); if(t->psy_noise_normal_partition[k]) wis(t->psy_noise_normal_partition[k],m); }
  wds(t->psy_noise_normal_thresh,m);
  wis(t->psy_ath_float,m+1); wis(t->psy_ath_abs,m+1);
  wds(t->psy_lowpass,m+1);
  int g=ceilmax(t->global_mapping,m+1);
  wi(g+1); for(int i=0;i<=g;i++) psy_global(&t->global_params[i]);
  wds(t->global_mapping,m+1);
  wi(t->stereo_modes!=NULL);
  if(t->stereo_modes) for(int i=0;i<=m;i++){
    wis(t->stereo_modes[i].pre,PACKETBLOBS); wis(t->stereo_modes[i].post,PACKETBLOBS);
    for(int k=0;k<PACKETBLOBS;k++) wf(t->stereo_modes[i].kHz[k]);
    for(int k=0;k<PACKETBLOBS;k++) wf(t->stereo_modes[i].lowpasskHz[k]);
  }
  wi(t->floor_mappings);
  int used[64]={0};
  for(int k=0;k<t->floor_mappings;k++){ wis(t->floor_mapping_list[k],m); for(int i=0;i<m;i++) used[t->floor_mapping_list[k][i]]=1; }
  int nf=0; for(int i=0;i<64;i++) nf+=used[i];
  wi(nf);
  for(int x=0;x<64;x++) if(used[x]){
    const vorbis_info_floor1 *f=t->floor_params+x;
    wi(x); floor1(f);
    int maxclass=-1,maxbook=-1;
    for(int i=0;i<f->partitions;i++) if(f->partitionclass[i]>maxclass)maxclass=f->partitionclass[i];
    for(int i=0;i<=maxclass;i++){
      if(f->class_book[i]>maxbook)maxbook=f->class_book[i];
      for(int k=0;k<(1<<f->class_subs[i]);k++) if(f->class_subbook[i][k]>maxbook)maxbook=f->class_subbook[i][k];
    }
    wi(maxbook+1); for(int i=0;i<=maxbook;i++) wi(book(t->floor_books[x][i]));
  }
  for(int i=0;i<m;i++){
    const vorbis_info_mapping0 *map=t->maps[i].map;
    const vorbis_residue_template *res=t->maps[i].res;
    int modes=t->blocksize_short[i]==t->blocksize_long[i]?1:2;
    wi(modes);
    mapping0(&map[0]); if(modes==2) mapping0(&map[1]);
    int nr=0;
    for(int k=0;k<modes;k++) for(int s=0;s<map[k].submaps;s++) if(map[k].residuesubmap[s]+1>nr) nr=map[k].residuesubmap[s]+1;
    wi(nr);
    for(int r=0;r<nr;r++){
      wi(res[r].res_type); wi(res[r].limit_type); wi(res[r].grouping); residue0(res[r].res);
      wi(book(res[r].book_aux)); wi(book(res[r].book_aux_managed));
      bookblock(res[r].books_base); bookblock(res[r].books_base_managed);
    }
  }
}

int main(int argc,char **argv){
  int count=0; while(setup_list[count]) count++;
  wi(count);
  for(int i=0;i<count;i++) template(setup_list[i]);
  psy_info(&_psy_info_template);
  wi(MAX_ATH); for(int i=0;i<MAX_ATH;i++) wf(ATH[i]);
  for(int i=0;i<P_BANDS;i++) for(int j=0;j<6;j++) for(int k=0;k<EHMER_MAX;k++) wf(tonemasks[i][j][k]);
  for(int w=0;w<8;w++){ const float *win=_vorbis_window_get(w); int len=32<<w; for(int k=0;k<len;k++) wf(win[k]); }
  FILE *out=fopen(argv[1],"wb");
  int magic=0x56534D44; fwrite(&magic,4,1,out);
  fwrite(&nbooks,4,1,out);
  for(int i=0;i<nbooks;i++){
    const static_codebook *b=books[i];
    int v[8]={(int)b->dim,(int)b->entries,b->maptype,(int)b->q_min,(int)b->q_delta,b->q_quant,b->q_sequencep,0};
    int q=0; if(b->maptype==1) q=(int)_book_maptype1_quantvals(b); else if(b->maptype==2) q=(int)(b->entries*b->dim);
    v[7]=q;
    fwrite(v,4,8,out);
    fwrite(b->lengthlist,1,b->entries,out);
    for(int k=0;k<q;k++){ int x=(int)b->quantlist[k]; fwrite(&x,4,1,out); }
  }
  fwrite(buf,1,len,out);
  fclose(out);
  printf("books=%d templates=%d bytes=%zu\n",nbooks,count,len);
  return 0;
}
