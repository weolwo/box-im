package com.bx.imserver.netty.processor;

import cn.hutool.core.bean.BeanUtil;
import com.bx.imcommon.contant.Attributes;
import com.bx.imcommon.contant.IMConstant;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.model.IMHeartbeatInfo;
import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.mq.RedisMQTemplate;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatProcessor extends AbstractMessageProcessor<IMHeartbeatInfo> {

    private final RedisMQTemplate redisMQTemplate;

    @Override
    public void process(ChannelHandlerContext ctx, IMHeartbeatInfo beatInfo) {
        // 响应ws
        IMSendInfo<Object> sendInfo = new IMSendInfo<>();
        sendInfo.setCmd(IMCmdType.HEART_BEAT.code());
        ctx.channel().writeAndFlush(sendInfo);
        // 设置属性
        Long heartbeatTimes = ctx.channel().attr(Attributes.HEARTBEAT_TIMES).get();
        ctx.channel().attr(Attributes.HEARTBEAT_TIMES).set(++heartbeatTimes);
        if (heartbeatTimes % 10 == 0) {
            // 每心跳10次，用户在线状态续一次命
            Long userId = ctx.channel().attr(Attributes.USER_ID).get();
            Integer terminal = ctx.channel().attr(Attributes.TERMINAL_TYPE).get();
            String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, userId.toString(), terminal.toString());
            redisMQTemplate.expire(key, IMConstant.ONLINE_TIMEOUT_SECOND, TimeUnit.SECONDS);
        }
        Long userId = ctx.channel().attr(Attributes.USER_ID).get();
        log.debug("心跳,userId:{},{}",userId,ctx.channel().id().asLongText());
    }

    @Override
    public IMHeartbeatInfo transForm(Object o) {
        HashMap map = (HashMap) o;
        return BeanUtil.fillBeanWithMap(map, new IMHeartbeatInfo(), false);
    }
}
