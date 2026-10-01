package org.worldgit.protocol;

/** 四端共用色票；視覺記號也必須不同，不能只依賴紅綠辨識。 */
public enum DiffType {
    ADDED(0x3FB950, '+', "solid-outline"),
    REMOVED(0xF85149, '-', "ghost-model"),
    MODIFIED(0xD29922, '~', "corner-outline"),
    CONFLICT(0xA371F7, '!', "pulsing-outline");
    public final int rgb;
    public final char symbol;
    public final String style;
    DiffType(int rgb, char symbol, String style) { this.rgb = rgb; this.symbol = symbol; this.style = style; }
}
