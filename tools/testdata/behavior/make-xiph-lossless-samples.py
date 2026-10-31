"""Makes the I/O behavior samples of WavPack, Speex, Theora and FFV1 under core/src/test/resources/samples.

ffmpeg synthesizes the inputs and encodes Speex (libspeex) and Theora (libtheora) in Ogg, FFV1 in Matroska and AVI,
and WavPack in
Matroska; the WavPack 5.8.1 command line encoder (BSD 3-Clause) writes the .wv file, with an APEv2 tag, from the
same input. Pin the result with pin-samples.py.

Usage: python -I make-xiph-lossless-samples.py <ffmpeg> <wavpack> <repository root>
"""
import os
import subprocess
import sys


def run(arguments):
    subprocess.run(arguments, check=True)


def main():
    ffmpeg, wavpack, root = sys.argv[1], sys.argv[2], sys.argv[3]
    samples = os.path.join(root, 'core', 'src', 'test', 'resources', 'samples')
    tone = ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=0.6', '-f', 'lavfi', '-i',
            'sine=frequency=660:sample_rate=44100:duration=0.6', '-filter_complex', '[0][1]amerge=inputs=2']
    video = ['-f', 'lavfi', '-i', 'testsrc2=size=96x64:rate=10', '-frames:v', '6']
    os.makedirs(os.path.join(samples, 'wavpack'), exist_ok=True)
    wav = os.path.join(samples, 'wavpack', 'source.wav')
    run([ffmpeg, '-v', 'error', '-y'] + tone + ['-c:a', 'pcm_s16le', wav])
    run([wavpack, '-q', '-y', '--blocksize=4410', '-w', 'Title=Behavior', wav, '-o',
         os.path.join(samples, 'wavpack', 'wavpack.wv')])
    os.remove(wav)
    run([ffmpeg, '-v', 'error', '-y'] + tone + ['-c:a', 'wavpack', '-frame_size', '4096', '-f', 'matroska',
         os.path.join(samples, 'matroska', 'wavpack.mka')])
    run([ffmpeg, '-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=300:sample_rate=16000:duration=0.6',
         '-c:a', 'libspeex', '-f', 'ogg', os.path.join(samples, 'ogg', 'speex.spx')])
    run([ffmpeg, '-v', 'error', '-y'] + video + ['-c:v', 'ffv1', '-level', '3', '-slices', '4', '-g', '2',
         '-pix_fmt', 'yuv420p', '-f', 'matroska', os.path.join(samples, 'matroska', 'ffv1.mkv')])
    run([ffmpeg, '-v', 'error', '-y'] + video + ['-c:v', 'ffv1', '-level', '1', '-coder', '1', '-g', '3',
         '-pix_fmt', 'yuv444p', '-f', 'avi', os.path.join(samples, 'avi', 'ffv1.avi')])
    run([ffmpeg, '-v', 'error', '-y'] + video + ['-c:v', 'libtheora', '-q:v', '6', '-g', '4', '-f', 'ogg',
         os.path.join(samples, 'ogg', 'theora.ogv')])


if __name__ == '__main__':
    main()
