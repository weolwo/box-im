package com.bx.imserver.netty.ws.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.util.JsonUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;

public class MessageProtocolEncoder extends MessageToMessageEncoder<IMSendInfo> {

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, IMSendInfo sendInfo, List<Object> list) throws Exception {
        String text = JsonUtils.getMapper().writeValueAsString(sendInfo);
        TextWebSocketFrame frame = new TextWebSocketFrame(text);
        list.add(frame);
    }
}
