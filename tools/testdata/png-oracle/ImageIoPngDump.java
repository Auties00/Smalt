import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.w3c.dom.Node;

/**
 * Reads PNG files with the JDK's own ImageIO reader, an independent reader of Smalt's PNG output
 * for the encoder tests, run as a source-file program so that no Smalt module needs java.desktop.
 *
 * <p>Usage: {@code java ImageIoPngDump.java <file>...}. For each file it writes
 * {@code <file>.imageio.txt}, the native PNG metadata tree (one line per node, attributes in
 * document order), and {@code <file>.imageio.rgba16}, the pixels as big-endian 16-bit red, green,
 * blue and alpha samples: palette entries scaled by 257, other samples scaled from their bit depth
 * by {@code v * 65535 / (2^depth - 1)}, gray replicated, and alpha 65535 when the image has no
 * alpha channel ({@code tRNS} keys are not applied).
 */
public class ImageIoPngDump {
    public static void main(String[] args) throws IOException {
        for (var name : args) {
            var path = Path.of(name);
            try (var input = ImageIO.createImageInputStream(path.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                var reader = readers.next();
                reader.setInput(input);
                var image = reader.read(0);
                var metadata = reader.getImageMetadata(0);
                var text = new StringBuilder();
                dump(metadata.getAsTree(metadata.getNativeMetadataFormatName()), 0, text);
                Files.writeString(Path.of(name + ".imageio.txt"), text);
                var raster = image.getRaster();
                var model = image.getColorModel();
                var bands = raster.getNumBands();
                var out = new ByteArrayOutputStream();
                var samples = new int[bands];
                for (var y = 0; y < image.getHeight(); y++) {
                    for (var x = 0; x < image.getWidth(); x++) {
                        raster.getPixel(x, y, samples);
                        int r, g, b, a;
                        if (model instanceof IndexColorModel index) {
                            var i = samples[0];
                            r = index.getRed(i) * 257;
                            g = index.getGreen(i) * 257;
                            b = index.getBlue(i) * 257;
                            a = index.getAlpha(i) * 257;
                        } else {
                            var scaled = new int[bands];
                            for (var c = 0; c < bands; c++) {
                                var bits = raster.getSampleModel().getSampleSize(c);
                                scaled[c] = (int) ((long) samples[c] * 65535 / ((1L << bits) - 1));
                            }
                            if (bands <= 2) {
                                r = g = b = scaled[0];
                                a = bands == 2 ? scaled[1] : 65535;
                            } else {
                                r = scaled[0];
                                g = scaled[1];
                                b = scaled[2];
                                a = bands == 4 ? scaled[3] : 65535;
                            }
                        }
                        for (var v : new int[]{r, g, b, a}) {
                            out.write(v >>> 8);
                            out.write(v);
                        }
                    }
                }
                Files.write(Path.of(name + ".imageio.rgba16"), out.toByteArray());
                reader.dispose();
            }
        }
    }

    private static void dump(Node node, int depth, StringBuilder text) {
        text.append("  ".repeat(depth)).append(node.getNodeName());
        var attributes = node.getAttributes();
        if (attributes != null) {
            for (var i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                text.append(' ').append(attribute.getNodeName()).append('=').append(attribute.getNodeValue());
            }
        }
        if (node instanceof IIOMetadataNode metadata && metadata.getUserObject() instanceof byte[] bytes) {
            text.append(" bytes=").append(bytes.length);
        }
        text.append('\n');
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            dump(child, depth + 1, text);
        }
    }
}
