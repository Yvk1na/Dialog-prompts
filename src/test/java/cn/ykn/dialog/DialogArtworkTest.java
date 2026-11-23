package cn.ykn.dialog;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DialogArtworkTest {
    @Test
    void bundlesAllArtworksAtExpectedResolution() throws Exception {
        for (DialogArtwork artwork : DialogArtwork.values()) {
            byte[] bytes = artwork.sourceBytes();
            assertNotNull(bytes, "Missing resource for " + artwork);
            BufferedImage image = DialogArtwork.decode(bytes);
            assertEquals(768, image.getWidth(), artwork.name() + " width");
            assertEquals(256, image.getHeight(), artwork.name() + " height");
        }
    }

    @Test
    void exportsValidFontPackWithinAtlasLimitsAndSkipsHubAndBags(@TempDir Path tempDir) throws Exception {
        assertTrue(DialogArtwork.exportPack(tempDir));
        assertFalse(DialogArtwork.exportPack(tempDir));

        assertTrue(Files.isRegularFile(tempDir.resolve("pack.yml")));
        Path fontJson = tempDir.resolve("resourcepack/assets/skyblock_dialog_art/font/panels.json");
        assertTrue(Files.isRegularFile(fontJson));
        String rawJson = Files.readString(fontJson);
        assertTrue(rawJson.contains("\\uE"), "Bitmap font glyphs must be escaped as \\uXXXX");
        assertFalse(rawJson.contains("hub_"));
        assertFalse(rawJson.contains("bags_"));

        var root = JsonParser.parseString(rawJson).getAsJsonObject();
        var providers = root.getAsJsonArray("providers");
        var spaceProvider = providers.get(0).getAsJsonObject();
        assertEquals("space", spaceProvider.get("type").getAsString());
        assertEquals(-1, spaceProvider.getAsJsonObject("advances").get("\ue4ff").getAsInt());

        Set<String> availableFiles = new HashSet<>();
        availableFiles.add("assets/skyblock_dialog_art/font/panels.json");

        int bitmapProviders = 0;
        for (int i = 1; i < providers.size(); i++) {
            var provider = providers.get(i).getAsJsonObject();
            assertEquals("bitmap", provider.get("type").getAsString());
            assertEquals(7, provider.get("ascent").getAsInt());
            int height = provider.get("height").getAsInt();
            String file = provider.get("file").getAsString();
            String rel = "assets/" + file.replace(":", "/textures/");
            availableFiles.add(rel);
            Path tilePath = tempDir.resolve("resourcepack").resolve(rel);
            BufferedImage tile = ImageIO.read(tilePath.toFile());
            assertNotNull(tile, "Missing tile " + rel);
            assertEquals(155, tile.getWidth());
            assertEquals(height, tile.getHeight());
            assertTrue(tile.getWidth() <= 256 && tile.getHeight() <= 256);
            assertTrue((tile.getRGB(154, 0) >>> 24) != 0, "Rightmost edge pixel must be non-zero alpha");
            bitmapProviders++;
        }

        int expectedThemes = DialogArtwork.values().length - 2; // excludes HUB & BAGS
        int rowsPerTheme = DialogArtwork.MAX_ROWS - DialogArtwork.MIN_ROWS + 1;
        assertEquals(expectedThemes * rowsPerTheme * 2, bitmapProviders);
        for (DialogArtwork artwork : DialogArtwork.values()) {
            if (artwork == DialogArtwork.HUB || artwork == DialogArtwork.BAGS) continue;
            assertTrue(artwork.isAvailable(availableFiles, List.of(Component.text("状态"))), artwork.name());
        }
    }

    @Test
    void clampsPanelRowsAndCentersOverlayLines() {
        assertEquals(9, DialogArtwork.panelRows(List.of()));
        assertEquals(9, DialogArtwork.panelRows(List.of(Component.text("only one line"))));
        assertEquals(9, DialogArtwork.panelRows(List.of(
                Component.text("1"), Component.text("2"), Component.text("3"),
                Component.text("4"), Component.text("5"), Component.text("6"),
                Component.text("7"))));
        assertEquals(10, DialogArtwork.panelRows(List.of(
                Component.text("1"), Component.text("2"), Component.text("3"),
                Component.text("4"), Component.text("5"), Component.text("6"),
                Component.text("7"), Component.text("8"))));
        assertEquals(12, DialogArtwork.panelRows(List.of(
                Component.text("1"), Component.text("2"), Component.text("3"),
                Component.text("4"), Component.text("5"), Component.text("6"),
                Component.text("7"), Component.text("8"), Component.text("9"),
                Component.text("10"))));

        String oneLine = PlainTextComponentSerializer.plainText()
                .serialize(DialogArtwork.BANK.overlay(List.of(Component.text("中心提示"))));
        String[] oneLines = oneLine.split("\n", -1);
        assertEquals(9, oneLines.length);
        assertEquals("中心提示", oneLines[4]);

        List<Component> wardrobeLines = new ArrayList<>();
        wardrobeLines.add(Component.text("正在穿戴预设 1"));
        wardrobeLines.add(Component.space());
        for (int i = 0; i < 4; i++) {
            wardrobeLines.add(Component.text("row-" + i));
            wardrobeLines.add(Component.space());
        }
        String wardrobeOverlay = PlainTextComponentSerializer.plainText()
                .serialize(DialogArtwork.WARDROBE.overlay(wardrobeLines));
        String[] lines = wardrobeOverlay.split("\n", -1);
        assertEquals(12, lines.length);
        assertEquals("正在穿戴预设 1", lines[1]);
        assertEquals("row-0", lines[3]);
        assertEquals("row-1", lines[5]);
        assertEquals("row-2", lines[7]);
        assertEquals("row-3", lines[9]);
        assertEquals(" ", lines[11]);
    }
}
