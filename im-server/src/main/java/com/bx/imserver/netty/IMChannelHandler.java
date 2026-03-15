package com.bx.imserver.netty;

import com.bx.imcommon.contant.Attributes;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.mq.RedisMQTemplate;
import com.bx.imserver.netty.processor.AbstractMessageProcessor;
import com.bx.imserver.netty.processor.ProcessorFactory;
import com.bx.imserver.util.SpringContextHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 长连接下 文本帧的处理器
 * 实现浏览器发送文本回写
 * 浏览器连接状态监控
 */
@Slf4j
public class IMChannelHandler extends SimpleChannelInboundHandler<IMSendInfo> {

    /**
     * 读取到消息后进行处理
     *
     * @param ctx      channel上下文
     * @param sendInfo 发送消息
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, IMSendInfo sendInfo) {
        // 创建处理器进行处理
        AbstractMessageProcessor processor = ProcessorFactory.createProcessor(IMCmdType.fromCode(sendInfo.getCmd()));
        processor.process(ctx, processor.transForm(sendInfo.getData()));
    }

    /**
     * 出现异常的处理 打印报错日志
     *
     * @param ctx   channel上下文
     * @param cause 异常信息
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error(cause.getMessage());
        //关闭上下文
        //ctx.close();
    }

    /**
     * 监控浏览器上线
     *
     * @param ctx channel上下文
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        log.info(ctx.channel().id().asLongText() + "连接");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        Long userId = ctx.channel().attr(Attributes.USER_ID).get();
        Integer terminal = ctx.channel().attr(Attributes.TERMINAL_TYPE).get();
        ChannelHandlerContext context = UserChannelCtxMap.getChannelCtx(userId, terminal);
        // 判断一下，避免异地登录导致的误删
        /*
          这段逻辑主要是在保护多端登录或快速重连时的连接稳定性。我们来设想一个 IM 系统中常见的“异地登录”场景：

          连接 A 存在：用户已经在手机上登录了（我们称为连接 A）。

          连接 B 进来：用户在电脑上又登录了同一个账号。此时系统会执行 LoginProcessor 的逻辑，把新连接 B 存入 UserChannelCtxMap，覆盖掉旧的连接 A。

          踢人逻辑：系统随后会主动关闭旧的连接 A。
          触发清理：连接 A 被关闭后，Netty 会回调 handlerRemoved。
          为什么要加这个判断？
          如果没有 ctx.channel().id().equals(...) 这个 ID 校验：

          当连接 A 断开触发清理时，由于 UserChannelCtxMap 此时存的是新连接 B 的信息。

          清理代码会不分青红皂白地把连接 B 从内存里删掉，甚至把 Redis 里的在线状态也给删了。

          结果就是：用户明明刚在电脑上登录成功，连接却莫名其妙地被系统当成“下线”清理掉了，导致用户刚登上去就掉线。

          通过校验 ID，系统能确保“谁产生的断开事件，谁才有资格清理自己的数据”。这样连接 A 只能发现自己不是当前最新的连接，于是默默退出，不干扰连接 B 的正常运行
         */
        if (context != null && ctx.channel().id().equals(context.channel().id())) {
            // 移除channel
            UserChannelCtxMap.removeChannelCtx(userId, terminal);
            // 用户下线
            RedisMQTemplate redisTemplate = SpringContextHolder.getBean(RedisMQTemplate.class);
            String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, userId.toString());
            redisTemplate.opsForHash().delete(key,terminal.toString());
            log.info("断开连接,userId:{},终端类型:{},{}", userId, terminal, ctx.channel().id().asLongText());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleState state = ((IdleStateEvent) evt).state();
            if (state == IdleState.READER_IDLE) {
                // 在规定时间内没有收到客户端的上行数据, 主动断开连接
                Long userId = ctx.channel().attr(Attributes.USER_ID).get();
                Integer terminal = ctx.channel().attr(Attributes.TERMINAL_TYPE).get();
                log.info("心跳超时，即将断开连接,用户id:{},终端类型:{} ", userId, terminal);
                ctx.channel().close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }

    }
}