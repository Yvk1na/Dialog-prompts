package cn.ykn.dialog;

import java.util.Locale;

/**
 * Per-player UI scale preset controlling width, column counts, and artwork density across all
 * Paper Dialog menus.
 */
public enum DialogSize {
    STANDARD("standard", "标准", "适合界面尺寸 1–3（完整宽屏与背景插画）", 540, true),
    COMPACT("compact", "紧凑", "适合界面尺寸 4 / 自动（420px 窄版卡片布局）", 420, true),
    MINI("mini", "超紧凑", "适合界面尺寸 5+ / 小窗口（≤300px 精简布局，关闭大插画）", 300, false);

    private final String id;
    private final String displayName;
    private final String shortDescription;
    private final int maxWidth;
    private final boolean showArtwork;

    public record GridLayout(int columns, int buttonWidth) {
    }

    DialogSize(String id, String displayName, String shortDescription, int maxWidth, boolean showArtwork) {
        this.id = id;
        this.displayName = displayName;
        this.shortDescription = shortDescription;
        this.maxWidth = maxWidth;
        this.showArtwork = showArtwork;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String shortDescription() {
        return shortDescription;
    }

    public int maxWidth() {
        return maxWidth;
    }

    public boolean showArtwork() {
        return showArtwork;
    }

    public DialogSize next() {
        return switch (this) {
            case STANDARD -> COMPACT;
            case COMPACT -> MINI;
            case MINI -> STANDARD;
        };
    }

    public static DialogSize fromId(String raw) {
        if (raw == null) {
            return STANDARD;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "compact" -> COMPACT;
            case "mini" -> MINI;
            default -> STANDARD;
        };
    }

    public GridLayout gridLayout(int preferredColumns, int standardButtonWidth, int buttonCount) {
        int safeCount = Math.max(1, buttonCount);
        int baseCols = Math.max(1, Math.min(preferredColumns, safeCount));
        return switch (this) {
            case STANDARD -> new GridLayout(baseCols, standardButtonWidth);
            case COMPACT -> {
                int cols = Math.min(baseCols, 4);
                int maxPerCol = (408 - (cols - 1) * 8) / cols;
                int width = Math.max(68, Math.min(standardButtonWidth, maxPerCol));
                yield new GridLayout(cols, width);
            }
            case MINI -> {
                int cols = Math.min(baseCols, 2);
                int maxPerCol = (292 - (cols - 1) * 8) / cols;
                int target = cols == 1
                        ? Math.min(standardButtonWidth, 220)
                        : Math.clamp(standardButtonWidth, 110, maxPerCol);
                yield new GridLayout(cols, target);
            }
        };
    }

    public int bodyWidth(int standardWidth) {
        return switch (this) {
            case STANDARD -> standardWidth;
            case COMPACT -> Math.min(standardWidth, 408);
            case MINI -> Math.min(standardWidth, 290);
        };
    }

    public int inputWidth(int standardWidth) {
        return switch (this) {
            case STANDARD -> standardWidth;
            case COMPACT -> Math.min(standardWidth, 340);
            case MINI -> Math.min(standardWidth, 240);
        };
    }

    public int exitButtonWidth(int standardWidth) {
        return switch (this) {
            case STANDARD -> standardWidth;
            case COMPACT -> Math.min(standardWidth, 150);
            case MINI -> Math.min(standardWidth, 140);
        };
    }

    public int confirmButtonWidth() {
        return switch (this) {
            case STANDARD -> 150;
            case COMPACT -> 144;
            case MINI -> 130;
        };
    }
}
