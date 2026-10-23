import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads BMP files with the JDK's own ImageIO reader, an independent reader of Smalt's BMP output for the
 * encoder tests, run as a source-file program so that no Smalt module needs java.desktop.
 *
 * <p>Usage: {@code java ImageIoBmpDump.java <file>...}. For each file it writes {@code <file>.imageio.rgba},
 * the pixels as 8-bit red, green, blue and straight alpha ({@code BufferedImage.getRGB}, alpha 255 when the
 * image has none), or {@code <file>.imageio.error} with the reader's message when it cannot read the file.
 */
public class ImageIoBmpDump {
    public static void main(String[] args) throws IOException {
        for (var name : args) {
            var path = Path.of(name);
            try {
                var image = ImageIO.read(path.toFile());
                if (image == null) {
                    throw new IOException("no ImageIO reader for " + name);
                }
                var out = new ByteArrayOutputStream(image.getWidth() * image.getHeight() * 4);
                for (var y = 0; y < image.getHeight(); y++) {
                    for (var x = 0; x < image.getWidth(); x++) {
                        var argb = image.getRGB(x, y);
                        out.write(argb >>> 16);
                        out.write(argb >>> 8);
                        out.write(argb);
                        out.write(argb >>> 24);
                    }
                }
                Files.write(Path.of(name + ".imageio.rgba"), out.toByteArray());
            } catch (IOException | RuntimeException exception) {
                Files.writeString(Path.of(name + ".imageio.error"), String.valueOf(exception));
            }
        }
    }
}
