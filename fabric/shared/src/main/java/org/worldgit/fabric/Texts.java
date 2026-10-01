package org.worldgit.fabric;

import java.util.List;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.worldgit.fabric.logic.Msg;

/** 把 Msg 依「接收者的客戶端語言」與世界的色票渲染成原版 Component 並送出。 */
public final class Texts {
    private Texts() {}

    /** 玩家的客戶端語言（單人世界就是自己目前的遊戲語言）；沒有玩家（主控台）用設定的伺服器語言。 */
    public static String locale(ServerRuntime rt, ServerPlayer player) {
        if (player == null) return rt.config().locale();
        String language = player.clientInformation().language();
        return language == null || language.isBlank() ? rt.config().locale() : language;
    }

    public static Component component(ServerRuntime rt, String locale, Msg msg) {
        var adventure = Mini.render(rt.catalog(), locale, msg, rt.palette());
        return MinecraftServerAudiences.of(rt.server()).asNative(adventure);
    }

    public static void send(CommandSourceStack source, ServerRuntime rt, List<Msg> lines) {
        String locale = locale(rt, source.getPlayer());
        for (var line : lines) {
            Component component = component(rt, locale, line);
            source.sendSuccess(() -> component, false);
        }
    }

    public static void failure(CommandSourceStack source, ServerRuntime rt, Msg line) {
        source.sendFailure(component(rt, locale(rt, source.getPlayer()), line));
    }
}
