package com.bx.imserver.netty.tcp.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.util.JsonUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
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
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) throws Exception {
        if (byteBuf.readableBytes() < 4) {
            return;
        }

        // 获取包体的真实长度
        int length = byteBuf.readInt();

        // 如果读到的长度是非法的（比如被恶意攻击发了负数，或者超大包），直接丢弃或断开连接
        if (length <= 0 || length > 1024 * 1024 * 10) { // 假设最大限制 10MB
            log.warn("收到非法的数据包长度: {}", length);
            ctx.close();
            return;
        }

        // 2. 性能巅峰：使用 ByteBufInputStream 包装
        // 传入 length 后，它会自动在内部限制最多只读 length 个字节，并推动 byteBuf 的 readerIndex
        try (ByteBufInputStream inputStream = new ByteBufInputStream(byteBuf, length)) {
            // 3. 让单例的 Jackson 直接怼着 Netty 的底层内存吸数据！
            // 全程没有 new byte[]，也没有 new String()，干净利落！
            IMSendInfo sendInfo = JsonUtils.getMapper().readValue((InputStream) inputStream, IMSendInfo.class);
            out.add(sendInfo);

        } catch (Exception e) {
            // 捕获反序列化异常，防止脏数据把整个 Netty 线程搞崩溃
            log.error("JSON 反序列化失败，数据格式错误", e);
            // 通常解析失败意味着协议被破坏，建议直接关闭连接
            ctx.close();
        }
    }
}
