package com.weibo.api.motan.transport.netty4;

import com.weibo.api.motan.codec.Codec;
import com.weibo.api.motan.common.MotanConstants;
import com.weibo.api.motan.common.URLParamType;
import com.weibo.api.motan.core.extension.ExtensionLoader;
import com.weibo.api.motan.exception.MotanErrorMsgConstant;
import com.weibo.api.motan.exception.MotanFrameworkException;
import com.weibo.api.motan.exception.MotanServiceException;
import com.weibo.api.motan.protocol.rpc.RpcProtocolVersion;
import com.weibo.api.motan.rpc.DefaultResponse;
import com.weibo.api.motan.rpc.Request;
import com.weibo.api.motan.rpc.Response;
import com.weibo.api.motan.rpc.RpcContext;
import com.weibo.api.motan.transport.Channel;
import com.weibo.api.motan.transport.MessageHandler;
import com.weibo.api.motan.util.LoggerUtil;
import com.weibo.api.motan.util.MotanFrameworkUtil;
import com.weibo.api.motan.util.NetUtils;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author sunnights
 */
public class NettyChannelHandler extends ChannelDuplexHandler {
    private ThreadPoolExecutor threadPoolExecutor;
    private MessageHandler messageHandler;
    private Channel channel;
    private Codec codec;

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler) {
        this.channel = channel;
        this.messageHandler = messageHandler;
        codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.getValue()));
    }

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler,
                               ThreadPoolExecutor threadPoolExecutor) {
        this.channel = channel;
        this.messageHandler = messageHandler;
        this.threadPoolExecutor = threadPoolExecutor;
        codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.getValue()));
    }

    private String getRemoteIp(ChannelHandlerContext ctx) {
        String ip = "";
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote != null) {
            try {
                ip = ((InetSocketAddress) remote).getAddress().getHostAddress();
            } catch (Exception e) {
                LoggerUtil.warn("get remoteIp error! default will use. msg:{}, remote:{}", e.getMessage(), remote.toString());
            }
        }
        return ip;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof NettyMessage) {
            if (threadPoolExecutor != null) {
                try {
                    threadPoolExecutor.execute(() -> processMessage(ctx, ((NettyMessage) msg)));
                } catch (RejectedExecutionException rejectException) {
                    if (((NettyMessage) msg).isRequest()) {
                        rejectMessage(ctx, (NettyMessage) msg);
                    } else {
                        LoggerUtil.warn("process thread pool is full, run in io thread, active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                                threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(), threadPoolExecutor.getCorePoolSize(),
                                threadPoolExecutor.getMaximumPoolSize(), threadPoolExecutor.getTaskCount(), ((NettyMessage) msg).getRequestId());
                        processMessage(ctx, (NettyMessage) msg);
                    }
                }
            } else {
                processMessage(ctx, (NettyMessage) msg);
            }
        } else {
            LoggerUtil.error("NettyChannelHandler messageReceived type not support: class=" + msg.getClass());
            throw new MotanFrameworkException("NettyChannelHandler messageReceived type not support: class=" + msg.getClass());
        }
    }

    private void rejectMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        if (msg.isRequest()) {
            sendResponse(ctx, MotanFrameworkUtil.buildErrorResponse(msg.getRequestId(), msg.getVersion().getVersion(), new MotanServiceException("process thread pool is full, reject by server: " + ctx.channel().localAddress(), MotanErrorMsgConstant.SERVICE_REJECT, false)));

            LoggerUtil.error("process thread pool is full, reject, active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                    threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(), threadPoolExecutor.getCorePoolSize(),
                    threadPoolExecutor.getMaximumPoolSize(), threadPoolExecutor.getTaskCount(), msg.getRequestId());
            if (channel instanceof NettyServer) {
                ((NettyServer) channel).getRejectCounter().incrementAndGet();
            }
        }
    }

    private void processMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        long startTime = System.currentTimeMillis();
        String remoteIp = getRemoteIp(ctx);
        Object result;
        try {
            result = codec.decode(channel, remoteIp, msg.getData());
        } catch (Exception e) {
            LoggerUtil.error("NettyDecoder decode fail! requestId" + msg.getRequestId() + ", size:" + msg.getData().length + ", ip:" + remoteIp + ", e:" + e.getMessage());
            Response response = MotanFrameworkUtil.buildErrorResponse(msg.getRequestId(), msg.getVersion().getVersion(), e);
            if (msg.isRequest()) {
                sendResponse(ctx, response);
            } else {
                processResponse(response);
            }
            return;
        }
        long length = msg.getData().length;
        if (RpcProtocolVersion.VERSION_1 == msg.getVersion() || RpcProtocolVersion.VERSION_1_Compress == msg.getVersion()) {
            length += RpcProtocolVersion.VERSION_1.getHeaderLength();
        }
        if (result instanceof Request) {
            Request request = (Request) result;
            MotanFrameworkUtil.logEvent(request, MotanConstants.TRACE_SRECEIVE, msg.getStartTime());
            MotanFrameworkUtil.logEvent(request, MotanConstants.TRACE_SEXECUTOR_START, startTime);
            MotanFrameworkUtil.logEvent(request, MotanConstants.TRACE_SDECODE);
            request.setAttachment(MotanConstants.CONTENT_LENGTH, String.valueOf(length));
            processRequest(ctx, request);
        } else if (result instanceof Response) {
            Response response = (Response) result;
            MotanFrameworkUtil.logEvent(response, MotanConstants.TRACE_CRECEIVE, msg.getStartTime());
            MotanFrameworkUtil.logEvent(response, MotanConstants.TRACE_CDECODE);
            response.setAttachment(MotanConstants.CONTENT_LENGTH, String.valueOf(length));
            processResponse(result);
        }
    }

    private void processRequest(final ChannelHandlerContext ctx, final Request request) {
        request.setAttachment(URLParamType.host.getName(), NetUtils.getHostName(ctx.channel().remoteAddress()));
        final long processStartTime = System.currentTimeMillis();
        try {
            RpcContext.init(request);
            Object result;
            try {
                result = messageHandler.handle(channel, request);
            } catch (Exception e) {
                LoggerUtil.error("NettyChannelHandler processRequest fail! request:" + MotanFrameworkUtil.toString(request), e);
                result = MotanFrameworkUtil.buildErrorResponse(request, new MotanServiceException("process request fail. errMsg:" + e.getMessage()));
            }
            if (result instanceof Response) {
                MotanFrameworkUtil.logEvent((Response) result, MotanConstants.TRACE_PROCESS);
            }
            final DefaultResponse response;
            if (result instanceof DefaultResponse) {
                response = (DefaultResponse) result;
            } else {
                response = new DefaultResponse(result);
            }
            response.setRpcProtocolVersion(request.getRpcProtocolVersion());
            response.setRequestId(request.getRequestId());
            response.setProcessTime(System.currentTimeMillis() - processStartTime);

            ChannelFuture channelFuture = sendResponse(ctx, response);
            if (channelFuture != null) {
                channelFuture.addListener((ChannelFutureListener) future -> {
                    MotanFrameworkUtil.logEvent(response, MotanConstants.TRACE_SSEND, System.currentTimeMillis());
                    response.onFinish();
                });
            } else { // execute the onFinish method of response if write fail
                response.onFinish();
            }
        } finally {
            RpcContext.destroy();
        }
    }

    private ChannelFuture sendResponse(ChannelHandlerContext ctx, Response response) {
        byte[] msg = CodecUtil.encodeObjectToBytes(channel, codec, response);
        response.setAttachment(MotanConstants.CONTENT_LENGTH, String.valueOf(msg.length));
        if (ctx.channel().isActive()) {
            return ctx.channel().writeAndFlush(msg);
        }
        return null;
    }

    private void processResponse(Object msg) {
        messageHandler.handle(channel, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LoggerUtil.info("NettyChannelHandler channelActive: remote={} local={}", ctx.channel().remoteAddress(), ctx.channel().localAddress());
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LoggerUtil.info("NettyChannelHandler channelInactive: remote={} local={}", ctx.channel().remoteAddress(), ctx.channel().localAddress());
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LoggerUtil.error("NettyChannelHandler exceptionCaught: remote={} local={} event={}", ctx.channel().remoteAddress(), ctx.channel().localAddress(), cause.getMessage(), cause);
        ctx.channel().close();
    }
}
