/* Black-box driver: feeds MACE frames through QuickTime's public SoundConverter API and writes the PCM. */
#define _WIN32_WINNT 0x0A00
#define WINVER 0x0A00
#include <windows.h>
#include <winreg.h>
void* memset(void* d,int c,unsigned n){volatile char* p=(volatile char*)d;while(n--)*p++=(char)c;return d;}
typedef short OSErr;
#pragma pack(push,2)
typedef struct { long flags; unsigned long format; short numChannels; short sampleSize; unsigned long sampleRate; long sampleCount; unsigned char* buffer; long reserved; } SCD;
#pragma pack(pop)
typedef OSErr (__cdecl *InitQTML)(long);
typedef OSErr (__cdecl *EnterM)(void);
typedef OSErr (__cdecl *ScOpen)(const SCD*, const SCD*, void**);
typedef OSErr (__cdecl *ScBegin)(void*);
typedef OSErr (__cdecl *ScConv)(void*, const void*, unsigned long, void*, unsigned long*, unsigned long*);
typedef OSErr (__cdecl *ScEnd)(void*, void*, unsigned long*, unsigned long*);
typedef OSErr (__cdecl *ScClose)(void*);
static HANDLE so;
static void ps(const char*s){DWORD w;WriteFile(so,s,lstrlenA(s),&w,0);}
static void pi(long v){char b[32];wsprintfA(b,"%ld ",v);ps(b);}
static unsigned long fcc(const char*s){return ((unsigned long)(unsigned char)s[0]<<24)|((unsigned char)s[1]<<16)|((unsigned char)s[2]<<8)|(unsigned char)s[3];}
static int atoi_(const char*s){int v=0;while(*s>='0'&&*s<='9')v=v*10+*s++-'0';return v;}
static LPSTR* argvA(int*n){static char*a[16];static char b[2048];lstrcpynA(b,GetCommandLineA(),2048);char*p=b;int c=0;while(*p&&c<16){while(*p==' ')p++;if(!*p)break;if(*p=='"'){p++;a[c++]=p;while(*p&&*p!='"')p++;}else{a[c++]=p;while(*p&&*p!=' ')p++;}if(*p)*p++=0;}*n=c;return a;}
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
#define DIE(c) TerminateProcess(GetCurrentProcess(),c)
/* args: format channels inBits inFile outFile outFormat outBits mode trialFrames
   mode: S one stream; B Begin/End around each trial; O Open/Close around each trial. */
void __stdcall start(void){
  so=GetStdHandle(STD_OUTPUT_HANDLE);
  SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
  int n;LPSTR*a=argvA(&n);
  if(n<10){ps("usage\n");DIE(2);}
  {char d[520];GetModuleFileNameA(0,d,520);int l=lstrlenA(d);while(l>0&&d[l-1]!='\\')l--;d[l-1]=0;char hv[600];wsprintfA(hv,"%s\\qt.hive",d);virtReg(d,hv);}
  if(!LoadLibraryA("CoreFoundation.dll")){ps("no CF\n");DIE(3);}
  HMODULE q=LoadLibraryA("QTMLClient.dll");
  if(!q){ps("load fail\n");DIE(3);}
  InitQTML init=(InitQTML)GetProcAddress(q,"InitializeQTML");
  EnterM enter=(EnterM)GetProcAddress(q,"EnterMovies");
  ScOpen op=(ScOpen)GetProcAddress(q,"SoundConverterOpen");
  ScBegin be=(ScBegin)GetProcAddress(q,"SoundConverterBeginConversion");
  ScConv cv=(ScConv)GetProcAddress(q,"SoundConverterConvertBuffer");
  ScEnd en=(ScEnd)GetProcAddress(q,"SoundConverterEndConversion");
  ScClose cl=(ScClose)GetProcAddress(q,"SoundConverterClose");
  OSErr e=init(0);if(e){ps("init ");pi(e);DIE(5);}
  enter();
  SCD in,out;RtlZeroMemory(&in,sizeof in);RtlZeroMemory(&out,sizeof out);
  in.format=fcc(a[1]);in.numChannels=(short)atoi_(a[2]);in.sampleSize=(short)atoi_(a[3]);in.sampleRate=22050UL<<16;
  out.format=fcc(a[6]);out.numChannels=in.numChannels;out.sampleSize=(short)atoi_(a[7]);out.sampleRate=22050UL<<16;
  char mode=a[8][0];unsigned long tr=atoi_(a[9]);
  HANDLE f=CreateFileA(a[4],GENERIC_READ,FILE_SHARE_READ,0,OPEN_EXISTING,0,0);DWORD sz=GetFileSize(f,0),r;
  unsigned char*ib=(unsigned char*)VirtualAlloc(0,sz+4096,MEM_COMMIT,PAGE_READWRITE);ReadFile(f,ib,sz,&r,0);CloseHandle(f);
  int fb=(a[1][3]=='3'?2:1)*in.numChannels;
  unsigned long tf=sz/fb;
  if(mode=='S'||tr==0)tr=tf;
  unsigned long cap=tf*6*in.numChannels*(out.sampleSize/8)+1048576;unsigned char*ob=(unsigned char*)VirtualAlloc(0,cap,MEM_COMMIT,PAGE_READWRITE);
  unsigned long tot=0;void*sc=0;
  e=op(&in,&out,&sc);if(e){ps("open ");pi(e);ps("\n");DIE(6);}
  e=be(sc);if(e){ps("begin ");pi(e);ps("\n");DIE(6);}
  for(unsigned long i=0;i<tf;i+=tr){
    unsigned long c=tf-i<tr?tf-i:tr,of=0,oby=0;
    e=cv(sc,ib+i*fb,c,ob+tot,&of,&oby);
    if(e){ps("conv ");pi(e);ps("\n");break;}
    tot+=oby;
    if(i+tr<tf||mode=='S'){
      if(mode=='B'){of=oby=0;en(sc,ob+tot,&of,&oby);if(oby){ps("endbytes ");pi(oby);ps("\n");}be(sc);}
      else if(mode=='O'){of=oby=0;en(sc,ob+tot,&of,&oby);cl(sc);e=op(&in,&out,&sc);if(e){ps("reopen ");pi(e);DIE(6);}be(sc);}
    }
  }
  {unsigned long of=0,oby=0;en(sc,ob+tot,&of,&oby);tot+=oby;}
  cl(sc);
  f=CreateFileA(a[5],GENERIC_WRITE,0,0,CREATE_ALWAYS,0,0);WriteFile(f,ob,tot,&r,0);CloseHandle(f);
  ps("Total ");pi(tot);ps("\n");
  DIE(0);
}
