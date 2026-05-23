package com.heonezen.haproxy;

import com.heonezen.haproxy.config.PluginConfig;
import com.heonezen.haproxy.listener.PlayerListener;
import com.heonezen.haproxy.network.ChannelInjector;
import org.bukkit.plugin.java.JavaPlugin;

public final class HaProxyPlugin extends JavaPlugin {
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
            getLogger().severe("Failed to inject PROXY-protocol channel handler: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(this, pluginConfig), this);
        String mode = switch (pluginConfig.getVersion()) {
            case 1 -> "PROXY Protocol v1 (text)";
            case 2 -> "PROXY Protocol v2 (binary)";
            default -> "unknown";
        };
        getLogger().info("Enabled - mode: " + mode + ", require-header: " + pluginConfig.isRequireProxyHeader());
    }
    @Override
    public void onDisable() {
        if (channelInjector != null) {
            try {
                channelInjector.uninject();
            } catch (Exception e) {
                getLogger().warning("Failed to remove PROXY-protocol channel handler: " + e.getMessage());
            }
        }
        getLogger().info("Disabled.");
    }
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }
}
