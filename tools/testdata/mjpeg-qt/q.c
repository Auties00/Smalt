/* Black-box driver for QuickTime 7: compresses and decompresses frames and exports and renders movies, through the
   public Image Compression Manager and Movie Toolbox only. */
#define _WIN32_WINNT 0x0A00
#define WINVER 0x0A00
#include <windows.h>
#include <winreg.h>
void* memset(void* d,int c,unsigned n){volatile char* p=(volatile char*)d;while(n--)*p++=(char)c;return d;}
void* memcpy(void* d,const void* s,unsigned n){volatile char* p=(volatile char*)d;const volatile char* q=(const volatile char*)s;while(n--)*p++=*q++;return d;}
typedef short OSErr;
typedef char** Handle;
typedef void* GW;
typedef void** PMH;
#pragma pack(push,2)
typedef struct { short top,left,bottom,right; } QRect;
#pragma pack(pop)
typedef OSErr (__cdecl *InitQTML)(long);
typedef OSErr (__cdecl *EnterM)(void);
typedef long (__cdecl *QTNewGW)(GW*,unsigned long,const QRect*,void*,void*,unsigned long,void*,long);
typedef PMH (__cdecl *GetPM)(GW);
typedef unsigned char (__cdecl *LockP)(PMH);
typedef long (__cdecl *MaxSz)(PMH,const QRect*,short,unsigned long,unsigned long,void*,long*);
typedef long (__cdecl *CompImg)(PMH,const QRect*,unsigned long,unsigned long,Handle,void*);
typedef long (__cdecl *DecImg)(void*,Handle,PMH,const QRect*,const QRect*,short,void*);
typedef Handle (__cdecl *NewH)(long);
typedef long (__cdecl *SizeH)(Handle);
typedef void (__cdecl *HL)(Handle);
typedef long (__cdecl *AddExt)(Handle,Handle,long);
typedef long (__cdecl *CountExt)(Handle,long,long*);
typedef long (__cdecl *GetExt)(Handle,Handle*,long,long);
typedef OSErr (__cdecl *MemE)(void);
static HANDLE so;
static void ps(const char*s){DWORD w;WriteFile(so,s,lstrlenA(s),&w,0);}
static void pi(long v){char b[32];wsprintfA(b,"%ld ",v);ps(b);}
static unsigned long fcc(const char*s){return ((unsigned long)(unsigned char)s[0]<<24)|((unsigned long)(unsigned char)s[1]<<16)|((unsigned long)(unsigned char)s[2]<<8)|(unsigned char)s[3];}
static long atoi_(const char*s){long v=0;while(*s>='0'&&*s<='9')v=v*10+*s++-'0';return v;}
static LPSTR* argvA(int*n){static char*a[64];static char b[8192];lstrcpynA(b,GetCommandLineA(),8192);char*p=b;int c=0;while(*p&&c<64){while(*p==' ')p++;if(!*p)break;if(*p=='"'){p++;a[c++]=p;while(*p&&*p!='"')p++;}else{a[c++]=p;while(*p&&*p!=' ')p++;}if(*p)*p++=0;}*n=c;return a;}
static void setv(HKEY k,const char*n,const char*v){RegSetValueExA(k,n,0,REG_SZ,(const BYTE*)v,lstrlenA(v)+1);}
static void setd(HKEY k,const char*n,DWORD v){RegSetValueExA(k,n,0,REG_DWORD,(const BYTE*)&v,4);}
static void fake(HKEY root,const char*sub,const char*d){
  HKEY k;DWORD dsp;LONG r=RegCreateKeyExA(root,sub,0,0,0,KEY_ALL_ACCESS,0,&k,&dsp);
  if(r){ps("create ");pi(r);ps("\n");return;}
  char b[600];
  wsprintfA(b,"%s\\",d);
  setv(k,"InstallDir",b);setv(k,"QuickTime.qts folder",b);setv(k,"QTSysDir",b);setv(k,"QTExtDir",b);setv(k,"QTComponentsDir",b);
  setd(k,"Version",0x07798000);setd(k,"WinVersion",0x07798000);setd(k,"QTEssentialsInstalled",1);
  RegCloseKey(k);
}
static void virtReg(const char*d,const char*hive){
  HKEY h;LONG r=RegLoadAppKeyA(hive,&h,KEY_ALL_ACCESS,0,0);
  if(r){ps("appkey ");pi(r);ps("\n");return;}
  fake(h,"SOFTWARE\\Apple Computer, Inc.\\QuickTime",d);
  fake(h,"SOFTWARE\\WOW6432Node\\Apple Computer, Inc.\\QuickTime",d);
  r=RegOverridePredefKey(HKEY_LOCAL_MACHINE,h);
  if(r){ps("override ");pi(r);ps("\n");}
}
static void* rf(const char*p,DWORD*sz){HANDLE f=CreateFileA(p,GENERIC_READ,FILE_SHARE_READ,0,OPEN_EXISTING,0,0);if(f==INVALID_HANDLE_VALUE){ps("open fail ");ps(p);ps("\n");TerminateProcess(GetCurrentProcess(),9);}DWORD s=GetFileSize(f,0),r;void*b=VirtualAlloc(0,s+65536,MEM_COMMIT,PAGE_READWRITE);ReadFile(f,b,s,&r,0);CloseHandle(f);*sz=s;return b;}
static void wf(const char*p,const void*d,DWORD n){DWORD r;HANDLE f=CreateFileA(p,GENERIC_WRITE,0,0,CREATE_ALWAYS,0,0);WriteFile(f,d,n,&r,0);CloseHandle(f);}
static NewH newH;static SizeH sizeH;static HL hlock;
static Handle toH(const void*d,long n){Handle h=newH(n);memcpy(*h,d,n);return h;}
#define Q(T,N,X) T N=(T)GetProcAddress(q,X);if(!N){ps("missing ");ps(X);ps("\n");TerminateProcess(GetCurrentProcess(),4);}
typedef long (__cdecl *NP)(const char*,void*,long);
typedef short (__cdecl *OMF)(const void*,short*,signed char);
typedef short (__cdecl *NMF)(void**,short,short*,unsigned char*,short,unsigned char*);
typedef long (__cdecl *OADC)(unsigned long,unsigned long,void**);
typedef long (__cdecl *GS)(void*,Handle*);
typedef long (__cdecl *SS)(void*,Handle);
typedef long (__cdecl *FC)(Handle,long,unsigned long,long,short*);
typedef short (__cdecl *SAD)(Handle,long,long,const void*);
typedef short (__cdecl *GADP)(Handle,long,long*,char**);
typedef short (__cdecl *CMTF)(void*,void*,void*,unsigned long,unsigned long,short,short*,long,void*);
typedef void (__cdecl *SMG)(void*,GW,void*);
typedef short (__cdecl *SMB)(void*,const QRect*);
typedef void (__cdecl *SMT)(void*,long);
typedef void (__cdecl *MT)(void*,long);
typedef short (__cdecl *UM)(void*);
typedef long (__cdecl *GD)(void*);
typedef void (__cdecl *SGW)(GW,void*);
typedef short (__cdecl *GME)(void);
/* usage:
   c ctype w h quality src.argb out.frame out.idesc   compresses one picture
   d frame idesc w h fmt out.bin rowBytes [extType extFile]...   decompresses one frame, extensions added first
   x idesc extType out   dumps an extension
   e in.mov out.mov codec quality   exports a movie through the QuickTime Movie exporter
   r in.mov w h out.argb index step   renders the movie at time index*step into a block preset to 0xA5
*/
void __stdcall start(void){
  so=GetStdHandle(STD_OUTPUT_HANDLE);
  SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
  int n;LPSTR*a=argvA(&n);
  if(n<2){ps("usage\n");TerminateProcess(GetCurrentProcess(),2);}
  {char d[520];GetModuleFileNameA(0,d,520);int l=lstrlenA(d);while(l>0&&d[l-1]!='\\')l--;d[l-1]=0;char hv[600];wsprintfA(hv,"%s\\qt.hive",d);virtReg(d,hv);}
  LoadLibraryA("QuickTime.qts");
  LoadLibraryA("CoreFoundation.dll");
  HMODULE q=LoadLibraryA("QTMLClient.dll");
  if(!q){ps("load fail ");pi(GetLastError());ps("\n");TerminateProcess(GetCurrentProcess(),3);}
  Q(InitQTML,init,"InitializeQTML");Q(EnterM,enter,"EnterMovies");
  Q(NP,np,"NativePathNameToFSSpec");Q(OMF,omf,"OpenMovieFile");Q(NMF,nmf,"NewMovieFromFile");
  OSErr e=init(0);if(e){ps("InitializeQTML ");pi(e);ps("\n");TerminateProcess(GetCurrentProcess(),5);}
  e=enter();if(e){ps("EnterMovies ");pi(e);ps("\n");}
  if(a[1][0]=='c'||a[1][0]=='d'||a[1][0]=='x'){
  long r;
  Q(QTNewGW,newgw,"QTNewGWorldFromPtr");Q(GetPM,getpm,"GetGWorldPixMap");Q(LockP,lockp,"LockPixels");
  Q(MaxSz,maxsz,"GetMaxCompressionSize");Q(CompImg,comp,"CompressImage");Q(DecImg,dec,"DecompressImage");
  Q(NewH,nh,"NewHandleClear");Q(SizeH,sh,"GetHandleSize");Q(HL,hl,"HLock");Q(AddExt,addx,"AddImageDescriptionExtension");
  Q(CountExt,cntx,"CountImageDescriptionExtensionType");Q(GetExt,getx,"GetImageDescriptionExtension");
  newH=nh;sizeH=sh;hlock=hl;
  if(a[1][0]=='c'){
    long w=atoi_(a[3]),h=atoi_(a[4]);unsigned long ql=atoi_(a[5]);DWORD sz;void*src=rf(a[6],&sz);
    QRect rc;rc.top=0;rc.left=0;rc.bottom=(short)h;rc.right=(short)w;
    GW gw=0;r=newgw(&gw,32,&rc,0,0,0,src,w*4);if(r){ps("gw ");pi(r);ps("\n");TerminateProcess(GetCurrentProcess(),6);}
    PMH pm=getpm(gw);lockp(pm);
    long mx=0;r=maxsz(pm,&rc,32,ql,fcc(a[2]),0,&mx);if(r){ps("maxsz ");pi(r);ps("\n");TerminateProcess(GetCurrentProcess(),7);}
    void*out=VirtualAlloc(0,mx+65536,MEM_COMMIT,PAGE_READWRITE);
    Handle desc=nh(4);
    r=comp(pm,&rc,ql,fcc(a[2]),desc,out);if(r){ps("compress ");pi(r);ps("\n");TerminateProcess(GetCurrentProcess(),8);}
    long idsz=sh(desc);unsigned char*id=(unsigned char*)*desc;
    long dataSize=*(long*)(id+44);
    ps("idesc ");pi(idsz);ps(" data ");pi(dataSize);ps("\n");
    wf(a[7],out,dataSize);wf(a[8],id,idsz);
  } else if(a[1][0]=='d'){
    DWORD fsz,dsz;void*fr=rf(a[2],&fsz);void*ds=rf(a[3],&dsz);
    long w=atoi_(a[4]),h=atoi_(a[5]);unsigned long fmt=lstrlenA(a[6])==4?fcc(a[6]):atoi_(a[6]);long rb=atoi_(a[8]);
    Handle desc=toH(ds,dsz);
    *(long*)(*desc+44)=fsz;
    for(int i=9;i+1<n;i+=2){DWORD xs;void*xd=rf(a[i+1],&xs);Handle xh=toH(xd,xs);r=(short)addx(desc,xh,(long)fcc(a[i]));if(r){ps("addext ");pi(r);ps("\n");}}
    QRect rc;rc.top=0;rc.left=0;rc.bottom=(short)h;rc.right=(short)w;
    long cap=rb*h+65536;void*out=VirtualAlloc(0,cap,MEM_COMMIT,PAGE_READWRITE);
    for(long i=0;i<cap;i+=1)((char*)out)[i]=(char)0xA5;
    GW gw=0;r=newgw(&gw,fmt,&rc,0,0,0,out,rb);if(r){ps("gw ");pi(r);ps("\n");TerminateProcess(GetCurrentProcess(),6);}
    PMH pm=getpm(gw);lockp(pm);
    {typedef void (__cdecl *SetGW)(GW,void*);Q(SetGW,sgw,"SetGWorld");sgw(gw,0);}r=dec(fr,desc,pm,&rc,&rc,0x40,0);
    r=(short)r;ps("decompress ");pi(r);ps("\n");
    if(!r)wf(a[7],out,rb*h);
    TerminateProcess(GetCurrentProcess(),r?1:0);
  } else if(a[1][0]=='x'){
    DWORD dsz;void*ds=rf(a[2],&dsz);Handle desc=toH(ds,dsz);long ct=0;r=cntx(desc,(long)fcc(a[3]),&ct);ps("count ");pi(r);pi(ct);ps("\n");
    if(ct>0){Handle x=0;r=getx(desc,&x,(long)fcc(a[3]),1);ps("get ");pi(r);ps("\n");if(!r&&x)wf(a[4],*x,sh(x));}
  }
  TerminateProcess(GetCurrentProcess(),0);
  }
  static char spec[2048],spec2[2048];
  long r=(short)np(a[2],spec,0);ps("spec ");pi(r);ps("\n");
  short ref=0;e=omf(spec,&ref,1);ps("open ");pi(e);ps("\n");if(e)TerminateProcess(GetCurrentProcess(),6);
  void*mv=0;short rid=0;e=nmf(&mv,ref,&rid,0,1,0);ps("newmovie ");pi(e);ps("\n");if(e)TerminateProcess(GetCurrentProcess(),7);
  if(a[1][0]=='e'){
    Q(OADC,oadc,"OpenADefaultComponent");Q(GS,gs,"MovieExportGetSettingsAsAtomContainer");Q(SS,ss,"MovieExportSetSettingsFromAtomContainer");
    Q(FC,fc,"QTFindChildByID");Q(SAD,sad,"QTSetAtomData");Q(GADP,gadp,"QTGetAtomDataPtr");Q(CMTF,cmtf,"ConvertMovieToFile");
    void*ex=0;r=(short)oadc(fcc("spit"),fcc("MooV"),&ex);ps("exporter ");pi(r);ps("\n");if(r)TerminateProcess(GetCurrentProcess(),8);
    Handle ac=0;r=(short)gs(ex,&ac);ps("settings ");pi(r);ps("\n");if(r)TerminateProcess(GetCurrentProcess(),9);
    long vide=fc(ac,0,fcc("vide"),1,0);ps("vide ");pi(vide);
    long sptl=fc(ac,vide,fcc("sptl"),1,0);ps("sptl ");pi(sptl);ps("\n");
    long sz=0;char*p=0;e=gadp(ac,sptl,&sz,&p);ps("sptldata ");pi(e);pi(sz);ps("\n");
    unsigned char sp[14];
    for(int i=0;i<14&&i<sz;i++)sp[i]=(unsigned char)p[i];
    {char hx[64];int k=0;for(int i=0;i<14;i++){wsprintfA(hx+k,"%02x",sp[i]);k+=2;}ps("old ");ps(hx);ps("\n");}
    unsigned long c=fcc(a[4]);sp[0]=(unsigned char)(c>>24);sp[1]=(unsigned char)(c>>16);sp[2]=(unsigned char)(c>>8);sp[3]=(unsigned char)c;
    unsigned long ql=atoi_(a[5]);sp[10]=(unsigned char)(ql>>24);sp[11]=(unsigned char)(ql>>16);sp[12]=(unsigned char)(ql>>8);sp[13]=(unsigned char)ql;
    e=sad(ac,sptl,14,sp);ps("setdata ");pi(e);ps("\n");
    r=(short)ss(ex,ac);ps("setsettings ");pi(r);ps("\n");
    r=(short)np(a[3],spec2,0);ps("outspec ");pi(r);ps("\n");
    short res=0;e=cmtf(mv,0,spec2,fcc("MooV"),fcc("TVOD"),0,&res,0x80000000L|4,ex);ps("convert ");pi(e);ps("\n");
    TerminateProcess(GetCurrentProcess(),e?1:0);
  } else {
    Q(QTNewGW,newgw,"QTNewGWorldFromPtr");Q(GetPM,getpm,"GetGWorldPixMap");Q(LockP,lockp,"LockPixels");
    Q(SMG,smg,"SetMovieGWorld");Q(SMB,smb,"SetMovieBox");Q(SMT,smt,"SetMovieTimeValue");Q(MT,mt,"MoviesTask");Q(UM,um,"UpdateMovie");Q(GD,gd,"GetMovieDuration");Q(GD,gts,"GetMovieTimeScale");Q(SGW,sgw,"SetGWorld");Q(GME,gme,"GetMoviesError");
    long w=atoi_(a[3]),h=atoi_(a[4]);long idx=atoi_(a[6]),step=atoi_(a[7]);
    long cap=w*h*4+65536;void*out=VirtualAlloc(0,cap,MEM_COMMIT,PAGE_READWRITE);
    for(long i=0;i<cap;i++)((char*)out)[i]=(char)0xA5;
    QRect rc;rc.top=0;rc.left=0;rc.bottom=(short)h;rc.right=(short)w;
    GW gw=0;r=newgw(&gw,32,&rc,0,0,0,out,w*4);if(r){ps("gw ");pi(r);ps("\n");TerminateProcess(GetCurrentProcess(),6);}
    lockp(getpm(gw));sgw(gw,0);
    ps("duration ");pi(gd(mv));pi(gts(mv));ps("\n");
    smg(mv,gw,0);smb(mv,&rc);smt(mv,idx*step);um(mv);mt(mv,0);ps("moviesError ");pi(gme());ps("\n");
    wf(a[5],out,w*h*4);
  }
  TerminateProcess(GetCurrentProcess(),0);
}
