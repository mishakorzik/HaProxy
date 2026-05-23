package com.heonezen.haproxy.network;

import com.heonezen.haproxy.config.PluginConfig;
import com.heonezen.haproxy.util.ReflectionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

public final class ProxyProtocolDecoder extends ByteToMessageDecoder {
    public static final AttributeKey<InetSocketAddress> REAL_ADDRESS = AttributeKey.valueOf("haproxy_real_address");
    private static final byte[] V2_SIG = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
    private static final byte[] V1_PREFIX = {'P', 'R', 'O', 'X', 'Y', ' '};
    private final PluginConfig config;
    private final Logger logger;
    public ProxyProtocolDecoder(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 12) return;
        if (matchesAt(in, 0, V2_SIG)) {
            if (config.getVersion() != 2) {
                logger.warning("[HaProxy] Expected PROXY v" + config.getVersion() + " but received v2 header from " + ctx.channel().remoteAddress());
                closeOrPassthrough(ctx, in, out);
                return;
            }
            parseV2(ctx, in, out);
            return;
        }
        if (matchesAt(in, 0, V1_PREFIX)) {
            if (config.getVersion() != 1) {
                logger.warning("[HaProxy] Expected PROXY v" + config.getVersion() + " but received v1 header from " + ctx.channel().remoteAddress());
                closeOrPassthrough(ctx, in, out);
                return;
            }
            parseV1(ctx, in, out);
            return;
        }
        if (config.isRequireProxyHeader()) {
            logger.warning("[HaProxy] No PROXY header from " + ctx.channel().remoteAddress() + " - closing");
            discard(ctx);
        } else {
            finish(ctx, in, out, null);
        }
    }
    private void parseV1(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int limit = Math.min(in.readableBytes(), 108);
        int base  = in.readerIndex();
        int crlfOffset = -1;
        for (int i = 0; i < limit - 1; i++) {
            if (in.getByte(base + i) == '\r' && in.getByte(base + i + 1) == '\n') {
                crlfOffset = i;
                break;
            }
        }
        if (crlfOffset == -1) {
            if (in.readableBytes() >= 108) {
                logger.warning("[HaProxy] PROXY v1 header exceeds 108 bytes from " + ctx.channel().remoteAddress() + " — closing");
                discard(ctx);
            }
            // else: wait for more data
            return;
        }
        byte[] lineBytes = new byte[crlfOffset];
        in.readBytes(lineBytes);
        in.skipBytes(2); // skip CRLF
        String line = new String(lineBytes, StandardCharsets.US_ASCII);
        InetSocketAddress realAddr = null;
        try {
            if (!line.startsWith("PROXY UNKNOWN")) {
                String[] parts = line.split(" ");
                if (parts.length != 6)
                    throw new IllegalArgumentException("expected 6 tokens, got " + parts.length);
                String proto = parts[1];
                if (!proto.equals("TCP4") && !proto.equals("TCP6"))
                    throw new IllegalArgumentException("unsupported protocol: " + proto);
                InetAddress addr = InetAddress.getByName(parts[2]);
                int port = Integer.parseUnsignedInt(parts[4]);
                if (port < 1 || port > 65535)
                    throw new IllegalArgumentException("port out of range: " + port);
                realAddr = new InetSocketAddress(addr, port);
            }
        } catch (Exception e) {
            logger.warning("[HaProxy] Malformed PROXY v1 header from "
                    + ctx.channel().remoteAddress() + ": " + e.getMessage());
            if (config.isRequireProxyHeader()) {
                discard(ctx);
                return;
            }
            realAddr = null;
        }
        finish(ctx, in, out, realAddr);
    }
    private void parseV2(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 16) return;
        int addrLen   = in.getUnsignedShort(in.readerIndex() + 14);
        if (in.readableBytes() < 16 + addrLen) return; // wait for full header
        int headerEnd = in.readerIndex() + 16 + addrLen;
        in.skipBytes(12);
        byte verCmd   = in.readByte();
        byte famProto = in.readByte();
        in.skipBytes(2);
        int ver = (verCmd >> 4) & 0x0F;
        int cmd = verCmd & 0x0F;
        int fam = (famProto >> 4) & 0x0F;
        if (ver != 2) {
            logger.warning("[HaProxy] Invalid PROXY v2 version nibble " + ver + " from " + ctx.channel().remoteAddress());
            in.readerIndex(headerEnd);
            closeOrPassthrough(ctx, in, out);
            return;
        }
        InetSocketAddress realAddr = null;
        // cmd=0x01 -> PROXY (forwarded connection); cmd=0x00 -> LOCAL (health-check, keep null)
        if (cmd == 0x01) {
            try {
                if (fam == 0x01 && addrLen >= 12) { // IPv4
                    byte[] src = new byte[4];
                    in.readBytes(src);
                    in.skipBytes(4);
                    int srcPort = in.readUnsignedShort();
                    in.skipBytes(2);
                    realAddr = new InetSocketAddress(InetAddress.getByAddress(src), srcPort);

                } else if (fam == 0x02 && addrLen >= 36) { // IPv6
                    byte[] src = new byte[16];
                    in.readBytes(src);
                    in.skipBytes(16);
                    int srcPort = in.readUnsignedShort();
                    in.skipBytes(2);
                    realAddr = new InetSocketAddress(InetAddress.getByAddress(src), srcPort);
                }
                // fam=0x03 -> UNIX sockets - not applicable for Minecraft; treat as LOCAL
            } catch (Exception e) {
                logger.warning("[HaProxy] Malformed PROXY v2 address block from " + ctx.channel().remoteAddress() + ": " + e.getMessage());
                if (config.isRequireProxyHeader()) {
                    in.readerIndex(headerEnd);
                    discard(ctx);
                    return;
                }
            }
        } else if (cmd != 0x00) {
            logger.warning("[HaProxy] Unknown PROXY v2 command 0x" + Integer.toHexString(cmd) + " from " + ctx.channel().remoteAddress());
            if (config.isRequireProxyHeader()) {
                in.readerIndex(headerEnd);
                discard(ctx);
                return;
            }
        }
        in.readerIndex(headerEnd);
        finish(ctx, in, out, realAddr);
    }
    private void finish(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, InetSocketAddress realAddr) {
        if (realAddr != null) {
            ctx.channel().attr(REAL_ADDRESS).set(realAddr);
            injectIntoNmsConnection(ctx, realAddr);
        }
        // Forward any bytes that came after the PROXY header in the same read.
        if (in.readableBytes() > 0) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        // Remove ourselves - every subsequent packet should skip this handler entirely.
        ctx.pipeline().remove(this);
    }
    private void closeOrPassthrough(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (config.isRequireProxyHeader()) {
            discard(ctx);
        } else {
            finish(ctx, in, out, null);
        }
    }
    private void discard(ChannelHandlerContext ctx) {
        // Remove before close so Netty cannot re-enter decode() while closing.
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
        ctx.close();
    }
    private void injectIntoNmsConnection(ChannelHandlerContext ctx, SocketAddress address) {
        io.netty.channel.ChannelHandler handler = ctx.pipeline().get("packet_handler");
        if (handler == null) return; // too early - PlayerListener will retry
        try {
            ReflectionUtil.setSocketAddress(handler, address);
        } catch (Exception e) {
            // Non-fatal: PlayerListener will retry during PlayerLoginEvent
            logger.fine("[HaProxy] Early address injection deferred: " + e.getMessage());
        }
    }
    private boolean matchesAt(ByteBuf buf, int offset, byte[] pattern) {
        if (buf.readableBytes() < offset + pattern.length) return false;
        int base = buf.readerIndex() + offset;
        for (int i = 0; i < pattern.length; i++) {
            if (buf.getByte(base + i) != pattern[i]) return false;
        }
        return true;
    }
}
