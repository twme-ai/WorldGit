package org.worldgit.fabric;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.TextColor;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.i18n.MessageCatalog;
import org.worldgit.protocol.DiffPalette;

/**
 * Msg → Adventure Component（MiniMessage）。參數一律當純文字插入；{@code <wg_added>} 等自訂標籤的顏色取自
 * protocol 的 DiffPalette，所以普通／色盲色票在所有訊息裡同時生效，語言檔不寫死任何 diff 顏色。
 * Adventure Component 之後由 adventure-platform-fabric 轉成原版 Text（兩版共用同一個 API）。
 */
public final class Mini {
    private Mini() {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static TagResolver kind(String name, DiffPalette palette, ChangeKind kind) {
        return TagResolver.resolver(name, Tag.styling(TextColor.color(palette.rgb(kind))));
    }

    static TagResolver palette(DiffPalette palette) {
        return TagResolver.resolver(
                kind("wg_added", palette, ChangeKind.ADDED),
                kind("wg_removed", palette, ChangeKind.REMOVED),
                kind("wg_modified", palette, ChangeKind.MODIFIED),
                kind("wg_conflict", palette, ChangeKind.CONFLICT));
    }

    public static Component render(MessageCatalog catalog, String locale, Msg msg, DiffPalette palette) {
        var resolvers = TagResolver.builder().resolver(palette(palette));
        msg.args().forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
        Component body = MM.deserialize(catalog.raw(locale, msg.key()), resolvers.build());
        if (!msg.prefixed()) return body;
        return MM.deserialize(catalog.raw(locale, "common.prefix")).append(body);
    }

    /** 純文字樣板（寫進 git 歷史的訊息）：只替換 {@code <name>}，不解析 MiniMessage。 */
    public static String plain(MessageCatalog catalog, String locale, Msg msg) {
        String text = catalog.raw(locale, msg.key());
        for (var e : msg.args().entrySet()) text = text.replace("<" + e.getKey() + ">", e.getValue());
        return text;
    }
}
