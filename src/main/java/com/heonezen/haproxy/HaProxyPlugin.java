package com.heonezen.haproxy;

import com.heonezen.haproxy.config.PluginConfig;
import com.heonezen.haproxy.listener.PlayerListener;
import com.heonezen.haproxy.network.ChannelInjector;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class HaProxyPlugin extends JavaPlugin {

    private static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
    }

    private PluginConfig pluginConfig;
    private ChannelInjector channelInjector;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = new PluginConfig(getConfig(), getLogger());
        channelInjector = new ChannelInjector(pluginConfig, getLogger());
        try {
            channelInjector.inject();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to inject PROXY-protocol channel handler", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        String mode = switch (pluginConfig.getVersion()) {
            case 1 -> "PROXY Protocol v1 (text)";
            case 2 -> "PROXY Protocol v2 (binary)";
            default -> "unknown";
        };
        String platform = FOLIA ? "Folia" : "Bukkit";
        getLogger().info("Enabled on " + platform + " - mode: " + mode + ", require-header: " + pluginConfig.isRequireProxyHeader());
    }

    @Override
    public void onDisable() {
        if (channelInjector != null) {
            try {
                channelInjector.uninject();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to remove PROXY-protocol channel handler", e);
            }
        }
        getLogger().info("Disabled.");
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public static boolean isFolia() {
        return FOLIA;
    }
}
