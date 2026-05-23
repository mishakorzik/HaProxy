package com.heonezen.haproxy.listener;

import com.heonezen.haproxy.HaProxyPlugin;
import com.heonezen.haproxy.config.PluginConfig;
import com.heonezen.haproxy.network.ProxyProtocolDecoder;
import com.heonezen.haproxy.util.ReflectionUtil;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.net.InetSocketAddress;

public final class PlayerListener implements Listener {

    private final HaProxyPlugin plugin;
    private final PluginConfig config;

    public PlayerListener(HaProxyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        try {
            applyRealAddress(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().fine("[HaProxy] Secondary address injection skipped for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    private void applyRealAddress(Player player) throws ReflectiveOperationException {
        Object serverPlayer = player.getClass().getMethod("getHandle").invoke(player);

        Object gameListener = ReflectionUtil.getFieldValue(serverPlayer, "connection");
        if (gameListener == null) return;

        Object nmsConnection = ReflectionUtil.getFieldValue(gameListener, "connection");
        if (nmsConnection == null) return;

        Channel channel = (Channel) ReflectionUtil.getFieldValue(nmsConnection, "channel");
        if (channel == null) return;

        InetSocketAddress realAddr = channel.attr(ProxyProtocolDecoder.REAL_ADDRESS).get();
        if (realAddr == null) return;

        try {
            ReflectionUtil.setSocketAddress(nmsConnection, realAddr);
        } catch (Exception ignored) {}
    }
}
