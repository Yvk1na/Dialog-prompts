package cn.ykn.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public enum DialogArtwork {
    HUB(0xF59E0B),
    BAGS(0x38BDF8),
    LEVEL(0xA78BFA),
    WARDROBE(0xC084FC),
    BANK(0xF59E0B),
    AUCTION(0xF59E0B),
    BAZAAR(0x34D399),
    MINION(0x38BDF8);

    public static final int WIDTH = 310;
    private static final int TILE_WIDTH = WIDTH / 2;
    static final int MIN_ROWS = 9;
    static final int MAX_ROWS = 12;
    private static final int LINE_HEIGHT = 9;
    private static final int FIRST_GLYPH = 0xE500;
    private static final char ONE_PIXEL_BACK = '\uE4FF';
    private static final Key FONT = Key.key("skyblock_dialog_art:panels");
    private static final String FONT_FILE = "assets/skyblock_dialog_art/font/panels.json";
    private static final String ASSETS = "assets/skyblock_dialog_art";
    private static final String PACK_VERSION = "1.1.0";
    private static final int[] WARDROBE_COL_CENTERS = {51, 155, 259};
    private static final int[] WARDROBE_ROW_INDICES = {3, 5, 7, 9};

    private final int accentRgb;

    DialogArtwork(int accentRgb) {
        this.accentRgb = accentRgb;
    }

    public int accentRgb() {
        return accentRgb;
    }

    private String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public byte[] sourceBytes() throws IOException {
        try (var resource = DialogArtwork.class.getResourceAsStream("/dialog-art/" + id() + ".png")) {
            return resource == null ? null : resource.readAllBytes();
        }
    }

    boolean isAvailable(Set<String> files, List<Component> lines) {
        int rows = panelRows(lines);
        return files.contains(FONT_FILE) && files.contains(texturePath(rows, 0)) && files.contains(texturePath(rows, 1));
    }

    /** Wraps dialog text lines beneath the full-width artwork glyph with centered vertical padding. */
    public Component overlay(List<Component> lines) {
        List<Component> safeLines = safeLines(lines);
        int rows = panelRows(safeLines);
        int base = glyph(rows);
        var content = Component.text()
                .append(Component.text(new String(new char[]{(char) base, ONE_PIXEL_BACK,
                                (char) (base + 1), ONE_PIXEL_BACK}), NamedTextColor.WHITE)
                        .font(FONT).shadowColor(ShadowColor.none()));
        int textCount = textRows(safeLines);
        int topBlank = textCount == 0 ? 0 : Math.max(0, (rows - textCount) / 2 - 1);
        for (int i = 0; i < topBlank; i++) {
            content.appendNewline().append(Component.space());
        }
        for (Component line : safeLines) {
            content.appendNewline().append(line.equals(Component.empty()) ? Component.space() : line);
        }
        for (int line = 1 + topBlank + textCount; line < rows; line++) {
            content.appendNewline().append(Component.space());
        }
        return content.build();
    }

    private int glyph(int rows) {
        return FIRST_GLYPH + (ordinal() * (MAX_ROWS - MIN_ROWS + 1) + rows - MIN_ROWS) * 2;
    }

    /** Computes the clamped panel row count, reserving top and bottom breathing margin. */
    public static int panelRows(List<Component> lines) {
        return Math.clamp(textRows(safeLines(lines)) + 2, MIN_ROWS, MAX_ROWS);
    }

    private static List<Component> safeLines(List<Component> lines) {
        return lines == null ? List.of() : lines.stream().filter(Objects::nonNull).toList();
    }

    private static int textRows(List<Component> lines) {
        return lines.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                .mapToInt(text -> 1 + (int) text.chars().filter(ch -> ch == '\n').count()).sum();
    }

    private String textureName(int rows, int tile) {
        return id() + "_" + rows + "_" + tile;
    }

    private String texturePath(int rows, int tile) {
        return ASSETS + "/textures/font/" + textureName(rows, tile) + ".png";
    }

    public static boolean exportPack(Path target) throws IOException {
        return exportPack(target, artwork -> {
            try {
                return artwork.sourceBytes();
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    static boolean exportPack(Path target, Function<DialogArtwork, byte[]> sources) throws IOException {
        StringBuilder font = new StringBuilder("{\"providers\":[{\"type\":\"space\",\"advances\":{\"\\ue4ff\":-1}}");
        StringBuilder revision = new StringBuilder("version: " + PACK_VERSION + "\n");
        var images = new java.util.EnumMap<DialogArtwork, byte[]>(DialogArtwork.class);
        for (DialogArtwork artwork : values()) {
            if (artwork == HUB || artwork == BAGS) continue;
            byte[] image = sources.apply(artwork);
            if (image == null) continue;
            images.put(artwork, image);
            revision.append("# ").append(artwork.id()).append(" ").append(fingerprint(image)).append('\n');
        }
        if (images.isEmpty()) return false;
        Path descriptor = target.resolve("pack.yml");
        Path pack = target.resolve("resourcepack");
        if (Files.isRegularFile(descriptor) && Files.readString(descriptor).startsWith(revision.toString())
                && Files.isRegularFile(pack.resolve(FONT_FILE))) return false;
        Path texDir = pack.resolve(ASSETS + "/textures/font");
        if (Files.isDirectory(texDir)) {
            try (var files = Files.list(texDir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".png")).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        for (var entry : images.entrySet()) {
            DialogArtwork artwork = entry.getKey();
            BufferedImage source = decode(entry.getValue());
            for (int rows = MIN_ROWS; rows <= MAX_ROWS; rows++) {
                int height = rows * LINE_HEIGHT - 2;
                BufferedImage panel = renderPanel(artwork, source, WIDTH, height, rows);
                for (int tile = 0; tile < 2; tile++) {
                    BufferedImage piece = panel.getSubimage(tile * TILE_WIDTH, 0, TILE_WIDTH, height);
                    // Nonzero alpha guarantees the fixed client advance; the text shader discards alpha below 0.1.
                    if ((piece.getRGB(TILE_WIDTH - 1, 0) >>> 24) == 0) piece.setRGB(TILE_WIDTH - 1, 0, 0x01FFFFFF);
                    Path png = pack.resolve(artwork.texturePath(rows, tile));
                    Files.createDirectories(png.getParent());
                    ImageIO.write(piece, "png", png.toFile());
                    font.append(",{\"type\":\"bitmap\",\"file\":\"skyblock_dialog_art:font/")
                            .append(artwork.textureName(rows, tile)).append(".png\",\"ascent\":7,\"height\":")
                            .append(height).append(",\"chars\":[\"")
                            .append(String.format("\\u%04X", artwork.glyph(rows) + tile)).append("\"]}");
                }
            }
        }
        Path fontPath = pack.resolve(FONT_FILE);
        Files.createDirectories(fontPath.getParent());
        Files.writeString(fontPath, font.append("]}\n").toString(), StandardCharsets.UTF_8);
        Files.writeString(descriptor, revision + "author: Skyblock\nnamespace: skyblock_dialog_art\n"
                + "description: SkyBlock Dialog artwork\n", StandardCharsets.UTF_8);
        return true;
    }

    private static BufferedImage renderPanel(DialogArtwork artwork, BufferedImage source, int width, int height,
                                             int rows) {
        var content = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var contentGraphics = content.createGraphics();
        contentGraphics.setColor(new Color(14, 19, 30));
        contentGraphics.fillRect(0, 0, width, height);
        paint(contentGraphics, source, width, height);
        if (artwork == WARDROBE && rows == MAX_ROWS) {
            paintWardrobeSlotFrames(contentGraphics);
        }
        contentGraphics.dispose();

        var panel = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var panelGraphics = panel.createGraphics();
        panelGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        panelGraphics.setColor(new Color(14, 19, 30));
        panelGraphics.fill(new RoundRectangle2D.Float(0, 0, width, height, 12, 12));
        panelGraphics.setComposite(AlphaComposite.SrcIn);
        panelGraphics.drawImage(content, 0, 0, null);
        panelGraphics.setComposite(AlphaComposite.SrcOver);
        paintPanelBorder(panelGraphics, width, height, artwork.accentRgb);
        panelGraphics.dispose();
        return panel;
    }

    private static void paintPanelBorder(java.awt.Graphics2D graphics, int width, int height, int accentRgb) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0x31, 0x44, 0x66, 240));
        graphics.setStroke(new BasicStroke(1.2f));
        graphics.draw(new RoundRectangle2D.Float(0.6f, 0.6f, width - 1.2f, height - 1.2f, 12, 12));

        int ar = (accentRgb >>> 16) & 0xFF;
        int ag = (accentRgb >>> 8) & 0xFF;
        int ab = accentRgb & 0xFF;
        float startX = 24f;
        float endX = width - 24f;
        graphics.setPaint(new LinearGradientPaint(startX, 0, endX, 0, new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(0x31, 0x44, 0x66, 0), new Color(ar, ag, ab, 235),
                        new Color(0x31, 0x44, 0x66, 0)}));
        graphics.setStroke(new BasicStroke(1.3f));
        graphics.draw(new java.awt.geom.Line2D.Float(startX, 0.75f, endX, 0.75f));
    }

    private static void paintWardrobeSlotFrames(java.awt.Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setStroke(new BasicStroke(1.0f));
        Color statusFill = new Color(10, 15, 30, 170);
        Color statusBorder = new Color(110, 92, 174, 185);
        graphics.setColor(statusFill);
        graphics.fill(new RoundRectangle2D.Float(82, 6, 146, 13, 7, 7));
        graphics.setColor(statusBorder);
        graphics.draw(new RoundRectangle2D.Float(82.5f, 6.5f, 145, 12, 7, 7));

        Color slotFill = new Color(10, 15, 30, 182);
        Color slotBorder = new Color(116, 98, 182, 195);
        for (int row : WARDROBE_ROW_INDICES) {
            int y = row * LINE_HEIGHT - 1;
            for (int cx : WARDROBE_COL_CENTERS) {
                int x = cx - 11;
                graphics.setColor(slotFill);
                graphics.fill(new RoundRectangle2D.Float(x, y, 22, 17, 6, 6));
                graphics.setColor(slotBorder);
                graphics.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 21, 16, 6, 6));
            }
        }
    }

    public static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        if (image == null) throw new IOException("Invalid Dialog artwork PNG");
        return image;
    }

    public static String fingerprint(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static BufferedImage downscale(BufferedImage source, int targetW, int targetH) {
        BufferedImage current = source;
        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= targetW && h / 2 >= targetH) {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            var next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            var graphics = next.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(current, 0, 0, w, h, null);
            graphics.dispose();
            current = next;
        }
        return current;
    }

    /** Paints scaled background artwork with center scrim and edge vignette for readable foreground text. */
    public static void paint(java.awt.Graphics2D graphics, BufferedImage source, int width, int height) {
        // Preserve both decorated edges of wide artwork when adapting it to a shorter information panel.
        double scale = Math.max((double) width / source.getWidth(), (double) height / source.getHeight());
        int drawnWidth = (int) Math.round(scale * source.getWidth());
        int drawnHeight = (int) Math.round(scale * source.getHeight());
        BufferedImage scaled = downscale(source, drawnWidth, drawnHeight);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.90f));
        graphics.drawImage(scaled, (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight, null);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setPaint(new LinearGradientPaint(0, 0, width, 0, new float[]{0f, 0.24f, 0.76f, 1f},
                new Color[]{new Color(8, 12, 21, 24), new Color(8, 12, 21, 152),
                        new Color(8, 12, 21, 152), new Color(8, 12, 21, 24)}));
        graphics.fillRect(0, 0, width, height);
        graphics.setPaint(new LinearGradientPaint(0, 0, 0, height, new float[]{0f, 0.16f, 0.84f, 1f},
                new Color[]{new Color(8, 12, 21, 42), new Color(8, 12, 21, 0),
                        new Color(8, 12, 21, 0), new Color(8, 12, 21, 42)}));
        graphics.fillRect(0, 0, width, height);
    }
}
