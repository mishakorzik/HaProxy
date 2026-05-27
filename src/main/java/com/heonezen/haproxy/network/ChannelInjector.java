package com.heonezen.haproxy.network;

import com.heonezen.haproxy.config.PluginConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@ChannelHandler.Sharable
public final class ChannelInjector extends ChannelInboundHandlerAdapter {

    private final PluginConfig config;
    private final Logger logger;

    public ChannelInjector(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Channel child) {
            child.pipeline().addFirst("haproxy-decoder", new ProxyProtocolDecoder(config, logger));
        }
        super.channelRead(ctx, msg);
    }

    public void inject() throws ReflectiveOperationException {
        for (ChannelFuture future : getServerChannelFutures()) {
            future.channel().eventLoop().execute(() -> {
                ChannelPipeline pipeline = future.channel().pipeline();
                if (pipeline.get("haproxy-injector") == null) {
                    pipeline.addFirst("haproxy-injector", this);
                }
            });
        }
    }

    public void uninject() throws ReflectiveOperationException {
        for (ChannelFuture future : getServerChannelFutures()) {
            future.channel().eventLoop().execute(() -> {
                ChannelPipeline pipeline = future.channel().pipeline();
                if (pipeline.get("haproxy-injector") != null) {
                    pipeline.remove("haproxy-injector");
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> getServerChannelFutures() throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        Object nmsServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);

        Object serverConnection = null;
        for (Field f : allFields(nmsServer.getClass())) {
            f.setAccessible(true);
            Object val = f.get(nmsServer);
            if (val == null) continue;
            String typeName = val.getClass().getSimpleName();
            if (typeName.contains("ServerConnection") || typeName.contains("NetworkIo")) {
                serverConnection = val;
                break;
            }
        }

        if (serverConnection == null) {
            throw new IllegalStateException("Could not find ServerConnectionListener inside MinecraftServer. Please report this with your server version.");
        }

        for (Field f : allFields(serverConnection.getClass())) {
            f.setAccessible(true);
            Object val = f.get(serverConnection);
            if (!(val instanceof List<?> list)) continue;
            for (Object item : list) {
                if (item instanceof ChannelFuture) {
                    return new ArrayList<>((List<ChannelFuture>) list);
                }
            }
        }

        throw new IllegalStateException("Could not find List<ChannelFuture> inside ServerConnectionListener. Please report this with your server version.");
    }

    private static Field[] allFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                result.add(f);
            }
        }
        return result.toArray(Field[]::new);
    }
}
