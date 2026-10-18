# H.264 encoder tuning measurements

The measurements behind the preset constants of `H264EncoderOptions` (`smalt-mpeg`,
`video.h264.encoder`). They record how each constant was chosen; the class doc names only what was tuned.

## Look-ahead and B pictures

Measured on 300-frame 720p and 480p clips from 750 to 6000 kbit/s (scaled by area) against x264 medium and
veryfast (Main, one thread, GOP 60), as Bjontegaard rate on PSNR-Y: without them stockholm720 needed 40% more
bits than medium and with them 13% more (6% fewer than veryfast); johnny480, kriste480, stock480 and foreman went
from 55, 62, 73 and 28% to 7, 10, 32 and 21%. The macroblock tree strength of 2 quantizer steps per doubling
trades PSNR against SSIM (3 gains PSNR and loses SSIM), two B pictures beat three on three of four clips, and a B
picture is coded 3 quantizer steps coarser than its anchor (2 and 4 were worse).

## Real-time mode

Measured on JCT-VC class E video-conference sequences at 640x480 and 1280x720 from 250 kbps to 1 Mbps against
openh264 at the same rates: adaptive quantization, which helps perceived quality in chat video, costs 13 to 20% of
the bitrate at equal PSNR-Y on such content while SSIM moves by less than 5%, so real-time mode leaves it off.

The IDR limits trade the time an IDR picture takes to transmit against quality on static scenes, whose following
pictures inherit the IDR picture's quality: on eight 240p to 720p conference and outdoor sequences from 150 kbps
to 1 Mbps with an IDR every two seconds, limiting the first IDR picture to five budgets (about the size of
openh264's first IDR picture at these rates) and the later ones to seven keeps the BD-rate on PSNR-Y within -1.7
to +1.6% (-0.4% on average) of IDR pictures that took 4 to 15 budgets, while five budgets for every IDR picture
cost up to 4.9%.

## Quantizer rounding and decimation

The inter quantizer rounds a level up from a fifth of a step instead of a sixth: on nine 120-frame clips at
constant quality the file preset needs 0.4% (PSNR-Y) and 0.2% (SSIM-Y) fewer bits, and real-time mode 0.7% and
0.9% fewer on four clips from 100 to 4000 kbit/s; a quarter saves another 0.6% in the file preset but costs 6% of
its time. SLOW zeroes isolated levels only below a block score of 3 and a macroblock score of 2 (4 and 5
elsewhere), which saves 0.9% and 0.6% of its bits for 13% of its time.

## Analysis shortcuts and B picture pyramid

B pictures of BALANCED search with the small diamond and quarter-sample diamonds and skip the intra analysis of
macroblocks the look-ahead found much cheaper to predict than to code intra: 7% of the time for 0.1% of the bits.
Seven B pictures in a pyramid with a tracked motion seed save 8.3% (PSNR-Y) and 9.0% (SSIM-Y) of the bits of two
flat B pictures on nine 120-frame clips at constant quality; a scene cut may start an IDR picture two pictures
after the previous one, and an IDR or P picture that comes out far larger than its budget is coded again at a
coarser quantizer.
