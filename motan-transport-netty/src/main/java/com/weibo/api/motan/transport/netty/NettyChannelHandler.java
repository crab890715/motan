/*
 *  Copyright 2009-2016 Weibo, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.weibo.api.motan.transport.netty;

import com.weibo.api.motan.common.MotanConstants;
import com.weibo.api.motan.common.URLParamType;
import com.weibo.api.motan.exception.MotanErrorMsgConstant;
import com.weibo.api.motan.exception.MotanFrameworkException;
import com.weibo.api.motan.exception.MotanServiceException;
import com.weibo.api.motan.rpc.DefaultResponse;
import com.weibo.api.motan.rpc.Request;
import com.weibo.api.motan.rpc.Response;
import com.weibo.api.motan.rpc.RpcContext;
import com.weibo.api.motan.transport.Channel;
import com.weibo.api.motan.transport.MessageHandler;
import com.weibo.api.motan.util.LoggerUtil;
import com.weibo.api.motan.util.MotanFrameworkUtil;
import com.weibo.api.motan.util.NetUtils;
import com.weibo.api.motan.util.StatisticCallback;
import org.jboss.netty.channel.*;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author maijunsheng
 * @version 创建时间：2013-5-31
 */
public class NettyChannelHandler extends SimpleChannelHandler implements StatisticCallback {
    private ThreadPoolExecutor threadPoolExecutor;
    private MessageHandler messageHandler;
    private Channel serverChannel;
    private AtomicInteger rejectCounter = new AtomicInteger(0);

    public NettyChannelHandler(Channel serverChannel) {
        this.serverChannel = serverChannel;
    }

    public NettyChannelHandler(Channel serverChannel, MessageHandler messageHandler) {
        this.serverChannel = serverChannel;
        this.messageHandler = messageHandler;
    }

    public NettyChannelHandler(Channel serverChannel, MessageHandler messageHandler,
                               ThreadPoolExecutor threadPoolExecutor) {
        this.serverChannel = serverChannel;
        this.messageHandler = messageHandler;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        LoggerUtil.info("NettyChannelHandler channelConnected: remote=" + ctx.getChannel().getRemoteAddress()
                + " local=" + ctx.getChannel().getLocalAddress() + " event=" + e.getClass().getSimpleName());
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        LoggerUtil.info("NettyChannelHandler channelDisconnected: remote=" + ctx.getChannel().getRemoteAddress()
                + " local=" + ctx.getChannel().getLocalAddress() + " event=" + e.getClass().getSimpleName());
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object message = e.getMessage();

        if (message instanceof Request) {
            processRequest(ctx, e);
        } else if (message instanceof Response) {
            processResponse(ctx, e);
        } else {
            LoggerUtil.error("NettyChannelHandler messageReceived type not support: class=" + message.getClass());
            throw new MotanFrameworkException("NettyChannelHandler messageReceived type not support: class=" + message.getClass());
        }
    }

    /**
     * <pre>
     *  request process: 主要来自于client的请求，需要使用threadPoolExecutor进行处理，避免service message处理比较慢导致ioThread被阻塞
     * </pre>
     */
    private void processRequest(final ChannelHandlerContext ctx, MessageEvent e) {
        final Request request = (Request) e.getMessage();
        request.setAttachment(URLParamType.host.getName(), NetUtils.getHostName(ctx.getChannel().getRemoteAddress()));

        final long processStartTime = System.currentTimeMillis();

        // 使用线程池方式处理
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    MotanFrameworkUtil.logEvent(request, MotanConstants.TRACE_SEXECUTOR_START);
                    RpcContext.init(request);
                    processRequest(ctx, request, processStartTime);
                } finally {
                    RpcContext.destroy();
                }
            });
        } catch (RejectedExecutionException rejectException) {
            DefaultResponse response = MotanFrameworkUtil.buildErrorResponse(request, new MotanServiceException("process thread pool is full, reject",
                    MotanErrorMsgConstant.SERVICE_REJECT, false));
            response.setProcessTime(System.currentTimeMillis() - processStartTime);
            e.getChannel().write(response);

            LoggerUtil.warn("process thread pool is full, reject, active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                    threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(),
                    threadPoolExecutor.getCorePoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                    threadPoolExecutor.getTaskCount(), request.getRequestId());
            rejectCounter.incrementAndGet();
        }
    }

    private void processRequest(final ChannelHandlerContext ctx, final Request request, long processStartTime) {
        Object result;
        try {
            result = messageHandler.handle(serverChannel, request);
        } catch (Exception e) {
            LoggerUtil.error("NettyChannelHandler processRequest fail!request:" + MotanFrameworkUtil.toString(request), e);
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
        ChannelFuture channelFuture = null;
        if (ctx.getChannel().isConnected()) {
            channelFuture = ctx.getChannel().write(response);
        }
        if (channelFuture != null) {
            channelFuture.addListener(future -> {
                MotanFrameworkUtil.logEvent(response, MotanConstants.TRACE_SSEND, System.currentTimeMillis());
                response.onFinish();
            });
        } else { // execute the onFinish method of response if write fail
            response.onFinish();
        }
    }

    private void processResponse(ChannelHandlerContext ctx, MessageEvent e) {
        messageHandler.handle(serverChannel, e.getMessage());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        LoggerUtil.error("NettyChannelHandler exceptionCaught: remote=" + ctx.getChannel().getRemoteAddress()
                + " local=" + ctx.getChannel().getLocalAddress() + " event=" + e.getCause(), e.getCause());

        ctx.getChannel().close();
    }

    @Override
    public String statisticCallback() {
        int count = rejectCounter.getAndSet(0);
        if (count > 0) {
            return String.format("type: motan name: reject_request_pool total_count: %s reject_count: %s", threadPoolExecutor.getPoolSize(), count);
        } else {
            return null;
        }
    }
}
