package dev.xantha.vss.client.prediction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.FoliageColor;

/** Colormaps for the full NeoForge benchmark; resource loading is outside timed regions. */
final class ComparisonBootstrap {
    static void loadColors() throws IOException {
        for (String name : List.of("grass", "foliage")) {
            BufferedImage image = image(name);
            int[] values = image.getRGB(0, 0, 256, 256, null, 0, 256);
            if (name.equals("grass")) GrassColor.init(values); else FoliageColor.init(values);
        }
    }
    static BufferedImage image(String name) throws IOException {
        String path = "assets/minecraft/textures/colormap/" + name + ".png";
        try (InputStream input = ComparisonBootstrap.class.getResourceAsStream('/' + path)) {
            if (input != null) return javax.imageio.ImageIO.read(input);
        }
        try (ZipFile zip = new ZipFile(System.getProperty("vss.comparison.resources"));
             InputStream input = zip.getInputStream(zip.getEntry(path))) {
            return javax.imageio.ImageIO.read(input);
        }
    }
}
