package com.heonezen.haproxy.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

public final class PluginConfig {

    private final int version;
    private final boolean requireProxyHeader;

    public PluginConfig(FileConfiguration cfg, Logger logger) {
        int v = cfg.getInt("proxy-version", 1);
        if (v != 1 && v != 2) {
            logger.warning("[HaProxy] Invalid proxy-version '" + v + "' in config.yml - defaulting to 1. Valid values: 1, 2.");
            v = 1;
        }
        this.version = v;
        this.requireProxyHeader = cfg.getBoolean("require-proxy-header", true);
    }

    public int getVersion() {
        return version;
    }

    public boolean isRequireProxyHeader() {
        return requireProxyHeader;
    }
}
