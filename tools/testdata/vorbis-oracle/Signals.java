import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.util.SplittableRandom;

/**
 * Writes the test signals of VorbisEncoding.signal (the same arithmetic, with StrictMath, so the samples are
 * bit-identical on every platform) as planar float32 little-endian, channels in Vorbis order, for vbm.c.
 * Usage: java Signals.java SHAPE CHANNELS RATE LENGTH SEED OUT.
 */
public class Signals {
    public static void main(String[] args) throws Exception {
        var shape = args[0];
        var channels = Integer.parseInt(args[1]);
        var rate = Integer.parseInt(args[2]);
        var length = Integer.parseInt(args[3]);
        var seed = Long.parseLong(args[4]);
        var random = new SplittableRandom(seed);
        var out = new float[channels][length];
        for (var c = 0; c < channels; c++) {
            var lowpass = 0.0;
            for (var i = 0; i < length; i++) {
                var t = (double) i / rate;
                double value = switch (shape) {
                    case "MUSIC" -> {
                        lowpass = 0.9 * lowpass + 0.1 * (random.nextDouble() - 0.5);
                        var vibrato = 1 + 0.003 * StrictMath.sin(2 * Math.PI * 5 * t);
                        yield 0.25 * StrictMath.sin(2 * Math.PI * (220 + 110 * c) * vibrato * t)
                                + 0.12 * StrictMath.sin(2 * Math.PI * 1320 * t + c)
                                + 0.06 * StrictMath.sin(2 * Math.PI * Math.min(5300, rate * 0.3) * t)
                                + 0.5 * lowpass;
                    }
                    case "TRANSIENTS" -> {
                        var phase = t % 0.37;
                        var burst = phase < 0.15 ? 0.6 * (random.nextDouble() * 2 - 1) * StrictMath.exp(-phase * 60) : 0;
                        yield 0.05 * StrictMath.sin(2 * Math.PI * 440 * t) + (c == 0 ? burst : 0.9 * burst);
                    }
                    case "NOISE" -> random.nextDouble() * 2 - 1;
                    case "SILENCE" -> 0;
                    case "SINE" -> 0.5 * StrictMath.sin(2 * Math.PI * (500 + 250 * c) * t);
                    case "FULL_SCALE" -> StrictMath.sin(2 * Math.PI * 100 * t) >= 0 ? 1 : -1;
                    case "IMPULSE" -> i == length / 3 ? 0.9 : 0;
                    default -> throw new IllegalArgumentException(shape);
                };
                out[c][i] = (float) value;
            }
        }
        int[] toWave = switch (channels) {
            case 3 -> new int[]{0, 2, 1};
            case 5 -> new int[]{0, 2, 1, 3, 4};
            case 6 -> new int[]{0, 2, 1, 4, 5, 3};
            default -> null;
        };
        try (var stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[5])))) {
            for (var c = 0; c < channels; c++) {
                var plane = out[toWave == null ? c : toWave[c]];
                for (var i = 0; i < length; i++) {
                    stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(plane[i])));
                }
            }
        }
    }
}
