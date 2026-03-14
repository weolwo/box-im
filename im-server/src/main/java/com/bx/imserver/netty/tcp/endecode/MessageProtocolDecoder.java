package com.bx.imserver.netty.tcp.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imserver.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 原理：ReplayingDecoder 会传给你一个特殊的 ByteBuf。当你调用 readInt() 但数据不够时，它不返回 null，而是直接抛出一个特殊的 Signal Error。

 回滚：ReplayingDecoder 捕获这个 Error，把 readerIndex 回滚到初始位置（Checkpoint），然后等更多数据到了，重新调用一次你的 decode 方法。


 官方说：ReplayingDecoder 适合简单的协议。

 本项目 做：用来解析复杂的、变长的 JSON，简直是让 CPU 在异常处理里“蹦迪”。

 官方说：建议使用 checkpoint() 来减少回滚范围。

     本项目 做：完全没用 checkpoint，每次都从头解析 JSON 字符串。

 官方建议：复杂的协议建议回退到 ByteToMessageDecoder。
 */
@Slf4j
public class MessageProtocolDecoder extends ReplayingDecoder {

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        if (byteBuf.readableBytes() < 4) {
            return;
        }
        // 获取到包的长度
        long length = byteBuf.readLong();
        // 转成IMSendInfo
        ByteBuf contentBuf = byteBuf.readBytes((int) length);
        String content = contentBuf.toString(CharsetUtil.UTF_8);
        IMSendInfo sendInfo = JsonUtils.getMapper().readValue(content, IMSendInfo.class);
        list.add(sendInfo);
    }
}
