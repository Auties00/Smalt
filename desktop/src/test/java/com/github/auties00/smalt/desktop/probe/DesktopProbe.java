package com.github.auties00.smalt.desktop.probe;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;

/**
 * Exercises the desktop providers through the JDK APIs alone, as an application that only added
 * the jars would; ModulePathTest runs it in a separate JVM on the module path and on the class
 * path and checks the lines it prints.
 */
public final class DesktopProbe {
    public static void main(String[] args) throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("webp").next();
        System.out.println("reader=" + reader.getClass().getName());
        System.out.println("reader-module=" + reader.getClass().getModule().getName());

        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 4, 0x80FF2010);
        var imageFile = Files.createTempFile("probe", ".webp");
        System.out.println("webp-write=" + ImageIO.write(image, "webp", imageFile.toFile()));
        var decoded = ImageIO.read(imageFile.toFile());
        System.out.println("webp-read=" + decoded.getWidth() + "x" + decoded.getHeight());
        Files.delete(imageFile);

        AudioFileFormat.Type opus = null;
        for (var type : AudioSystem.getAudioFileTypes()) {
            if (type.toString().equals("Ogg Opus")) {
                opus = type;
            }
        }
        var format = new AudioFormat(48_000, 16, 1, true, false);
        var pcm = new byte[48_000 * 2];
        for (var i = 0; i < 48_000; i++) {
            var value = (short) (8_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >> 8);
        }
        var soundFile = Files.createTempFile("probe", ".opus");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, 48_000), opus, soundFile.toFile());
        try (var encoded = AudioSystem.getAudioInputStream(soundFile.toFile());
             var decodedSound = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
            System.out.println("sound-encoding=" + encoded.getFormat().getEncoding());
            System.out.println("sound-frames=" + decodedSound.readAllBytes().length / decodedSound.getFormat().getFrameSize());
        }
        Files.delete(soundFile);
    }
}
