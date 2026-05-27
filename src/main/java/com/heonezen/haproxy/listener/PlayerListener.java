package com.heonezen.haproxy.listener;

import com.heonezen.haproxy.HaProxyPlugin;
import com.heonezen.haproxy.network.ProxyProtocolDecoder;
import com.heonezen.haproxy.util.ReflectionUtil;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerListener implements Listener {

    private static final AtomicReference<Method> HANDLE_METHOD = new AtomicReference<>();

    private final HaProxyPlugin plugin;

    public PlayerListener(HaProxyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        try {
            applyRealAddress(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().fine("Secondary address injection skipped for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    private void applyRealAddress(Player player) throws ReflectiveOperationException {
        Method getHandle = HANDLE_METHOD.get();
        if (getHandle == null) {
            getHandle = player.getClass().getMethod("getHandle");
            HANDLE_METHOD.compareAndSet(null, getHandle);
        }

        Object serverPlayer = getHandle.invoke(player);

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
        } catch (Exception e) {
            plugin.getLogger().fine("setSocketAddress failed for " + player.getName() + ": " + e.getMessage());
        }
    }
}
