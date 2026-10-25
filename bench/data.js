window.BENCHMARK_DATA = {
  "lastUpdate": 1792906717754,
  "repoUrl": "https://github.com/Auties00/Smalt",
  "entries": {
    "Smalt A/B head (hosted runner, ns per operation)": [
      {
        "commit": {
          "author": {
            "name": "Alessandro Autiero",
            "username": "Auties00",
            "email": "alautiero@gmail.com"
          },
          "committer": {
            "name": "Alessandro Autiero",
            "username": "Auties00",
            "email": "alautiero@gmail.com"
          },
          "id": "417b96e4de38b5bb7799d268a5d15a45bcb5019c",
          "message": "Snapshot of int for CI",
          "timestamp": "2026-10-25T01:17:10Z",
          "url": "https://github.com/Auties00/Smalt/commit/417b96e4de38b5bb7799d268a5d15a45bcb5019c"
        },
        "date": 1792906716423,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "com.github.auties00.smalt.audio.opus.internal.silk.SilkEncoderBenchmark.encode60s config=voiceNote",
            "value": 531354827.625,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.6%\n95% interval: -0.8% to +1.7%"
          },
          {
            "name": "com.github.auties00.smalt.audio.voice.VoiceProcessorBenchmark.frame kernels=vector scenario=double talk",
            "value": 76272.774,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +2.0%\n95% interval: +1.1% to +3.1%"
          },
          {
            "name": "com.github.auties00.smalt.capture.internal.convert.ConversionBenchmark.bgrx1080p kernel=vector",
            "value": 1347544.526,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.1%\n95% interval: -0.2% to +0.3%"
          },
          {
            "name": "com.github.auties00.smalt.capture.internal.convert.ConversionBenchmark.nv12Chroma720p kernel=vector",
            "value": 17064.646,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.6%\n95% interval: -0.6% to +7.9%"
          },
          {
            "name": "com.github.auties00.smalt.capture.internal.convert.ConversionBenchmark.yuy2720p kernel=vector",
            "value": 109443.243,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.8%\n95% interval: -0.5% to +1.6%"
          },
          {
            "name": "com.github.auties00.smalt.image.color.ColorTransformBenchmark.transform transform=p3",
            "value": 68120395.875,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.1%\n95% interval: -0.4% to +0.2%"
          },
          {
            "name": "com.github.auties00.smalt.image.color.ColorTransformBenchmark.transform transform=pq-16",
            "value": 522487005.312,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.2%\n95% interval: -0.4% to +0.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=aacenc",
            "value": 52060883.156,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +5.6%\n95% interval: +5.0% to +6.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=ac3",
            "value": 37460334.917,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.5%\n95% interval: -2.0% to +0.4%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=alacenc",
            "value": 35205727.418,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=amrnb",
            "value": 28623019.279,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +24.6%\n95% interval: +21.1% to +26.4%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=amrwb",
            "value": 66181422.197,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.1%\n95% interval: -6.8% to +1.8%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=av1enc:speed=FASTER:threads=1",
            "value": 356200711.542,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.3%\n95% interval: -3.1% to +6.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=capture",
            "value": 3769038.17,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.1%\n95% interval: -3.2% to +1.3%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=convert",
            "value": 3187709.151,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.3%\n95% interval: -0.9% to +1.2%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=dnxhddec",
            "value": 2033504.513,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.3%\n95% interval: -1.7% to +0.9%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=dnxhdenc",
            "value": 2215050.906,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.5%\n95% interval: -1.1% to +2.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=effects",
            "value": 15475916.511,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -1.0%\n95% interval: -3.1% to +3.4%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=flacenc",
            "value": 9500834.494,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.9%\n95% interval: +0.2% to +1.5%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=gifdec",
            "value": 5110736.864,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -0.8%\n95% interval: -2.9% to +2.3%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=h264dec:threads=1",
            "value": 44595072.033,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +4.1%\n95% interval: +3.7% to +4.8%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=h264dec:threads=2",
            "value": 28944417.247,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +4.0%\n95% interval: +2.8% to +4.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=h264enc:rt=true:threads=2",
            "value": 82560614.952,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.1%\n95% interval: -1.0% to +2.9%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=h264enc:speed=BALANCED:profile=main:threads=1",
            "value": 215636903.375,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.0%\n95% interval: -0.7% to +1.3%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=h264enc:speed=FASTEST:profile=baseline:threads=1",
            "value": 122254542.708,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.3%\n95% interval: -2.2% to +2.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=heaac",
            "value": 4434482.561,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -1.6%\n95% interval: -2.6% to +0.5%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=jpegsend",
            "value": 20501492.195,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.5%\n95% interval: -0.0% to +2.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=mp3enc",
            "value": 65927232.43,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +3.2%\n95% interval: +2.1% to +4.0%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=opuscall",
            "value": 93759426.352,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.5%\n95% interval: -0.5% to +3.0%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=opusvoice",
            "value": 80166187.163,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.4%\n95% interval: -1.6% to +3.8%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=pngdec",
            "value": 40800297.03,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.0%\n95% interval: -7.9% to +10.0%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=proresdec",
            "value": 2541024.837,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.0%\n95% interval: -1.2% to +2.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=proresenc",
            "value": 2636541.685,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.0%\n95% interval: -0.7% to +2.1%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=resample",
            "value": 6691566.155,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +6.3%\n95% interval: +5.3% to +8.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=voice",
            "value": 46220736.557,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +2.7%\n95% interval: +1.4% to +3.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=vp8enc:speed=6:threads=1",
            "value": 109127310.776,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.5%\n95% interval: -0.4% to +2.8%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=vp8enc:speed=8:threads=2:resilient=false",
            "value": 77274789.779,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.9%\n95% interval: -6.2% to +4.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=vp9enc:speed=6:threads=1",
            "value": 326563573.906,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): -1.1%\n95% interval: -1.4% to +2.6%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=webpanim",
            "value": 617145083.625,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.8%\n95% interval: -0.5% to +2.9%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=webpdec",
            "value": 5952393.954,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +1.4%\n95% interval: -0.2% to +2.5%"
          },
          {
            "name": "com.github.auties00.smalt.jit.JitEndToEndBenchmark.round spec=webpenc",
            "value": 225352696.525,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +10.3%\n95% interval: +3.7% to +11.5%"
          },
          {
            "name": "com.github.auties00.smalt.video.h264.H264DecoderBenchmark.decode stream=720p-baseline-cavlc threads=1",
            "value": 1119021037.125,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +3.9%\n95% interval: +2.9% to +6.5%"
          },
          {
            "name": "com.github.auties00.smalt.video.vp8.Vp8RtEncodeBenchmark.encodeFrame size=720 speed=6 threads=1",
            "value": 10792175.378,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.6%\n95% interval: -0.1% to +1.9%"
          },
          {
            "name": "com.github.auties00.smalt.video.vp9.internal.Vp9RtEncodeBenchmark.encodeFrame size=720 speed=6 threads=1",
            "value": 48129856.174,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +2.0%\n95% interval: -4.4% to +6.8%"
          },
          {
            "name": "com.github.auties00.smalt.video.vp9.internal.Vp9RtEncodeBenchmark.encodeFrameScalar size=720 speed=6 threads=1",
            "value": 240690357.137,
            "unit": "ns/op",
            "extra": "runner: AMD EPYC 7763 64-Core Processor\nforks: 5\nchange against 6d37e75bdddf (last commit at least a week old): +0.9%\n95% interval: +0.1% to +4.9%"
          }
        ]
      }
    ]
  }
}