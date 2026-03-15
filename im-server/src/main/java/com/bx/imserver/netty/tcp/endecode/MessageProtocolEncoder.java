package com.bx.imserver.netty.tcp.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.util.JsonUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;

@Slf4j
public class MessageProtocolEncoder extends MessageToByteEncoder<IMSendInfo> {

    /*
    // 这是 Netty MessageToByteEncoder 底层真实的逻辑（伪代码简化版）
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    ByteBuf buf = null;
    try {
        // 1. Netty 主动去内存池里，给你申请了一块空闲的堆外内存（空盘子）
        buf = allocateBuffer(ctx, msg);

        // 2. 核心时刻：Netty 调用了你写的、那个返回值为 void 的 encode 方法！
        // 把你的对象和空盘子一起传给你
        encode(ctx, (IMSendInfo) msg, buf);

        // 3. 你执行完了。Netty 开始检查这个盘子
        if (buf.isReadable()) {
            // 如果 buf 的 writerIndex 大于 0，说明你往里面写东西了！
            // Netty 就会把这个 buf 传递给下一个 Handler，最终发送出去
            ctx.write(buf, promise);
        } else {
            // 如果你啥也没写，Netty 就会把空 buf 释放掉
            buf.release();
            ctx.write(Unpooled.EMPTY_BUFFER, promise);
        }
    } catch (Exception e) {
        // ... 处理异常，释放内存
    }
}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, IMSendInfo sendInfo, ByteBuf byteBuf) throws Exception {
        // 1. 记录下当前要写数据的起始位置（极其重要）
        int startIndex = byteBuf.writerIndex();

        // 2. 占坑：先写入一个假的长度（4个字节的 0），把这 4 个字节的位置空出来
        byteBuf.writeInt(0);

        // 3. 性能巅峰：用 ByteBufOutputStream 包装 Netty 的缓冲区
        try (ByteBufOutputStream outputStream = new ByteBufOutputStream(byteBuf)) {
            // 4. 让 Jackson 直接怼着 Netty 的底层内存输出 JSON！
            // 全程没有 new String()，没有 new byte[]！
            JsonUtils.getMapper().writeValue((OutputStream) outputStream, sendInfo);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            throw e; // 序列化失败必须抛出，让 Netty 丢弃这个包
        }

        // 5. 计算真实写入的 JSON 字节长度
        // 真实长度 = 当前的写指针位置 - 起始位置 - 占坑的4个字节
        int length = byteBuf.writerIndex() - startIndex - 4;

        // 6. 回头填坑：把真实的长度写回到刚才占坑的那个位置！
        // 注意：这里用的是 setInt，它不会移动 writerIndex 的位置，只是单纯地替换那 4 个字节
        byteBuf.setInt(startIndex, length);
    }

    //代码解析
    /*
    终极后厨画面还原
        byteBuf（Netty 给的盘子）：
        Netty（服务员）递给你的是一个极其高级、极其特殊、由外星科技（堆外直接内存）打造的盘子。这个盘子非常牛逼，但不通用。

        JsonUtils.getMapper()（Jackson 厨师）：
        这位厨师虽然手艺极高（JSON 序列化极快），但他是个**“老顽固”。他从小在 JDK 的标准体系里长大，他只认 JDK 标准的碗（OutputStream 或者 Writer）**。
        如果你直接把 Netty 那个高科技外星盘子（ByteBuf）递给大厨，大厨会直接掀桌子报错：“这什么破烂玩意儿？老子不认识，老子没法把菜装进去！”

        new ByteBufOutputStream(byteBuf)（神器：漏斗适配器）：
        眼看大厨要罢工，这时候你掏出了一个**“魔法漏斗（适配器）”**。
        你把漏斗套在 Netty 的高科技盘子上。这个漏斗的上方，长得跟大厨平时用的标准碗（OutputStream）一模一样；但它的下方，直接连接着 Netty 的盘子。

        writeValue(...)（疯狂打饭）：
        大厨一看：“哟，这不是我最熟悉的 OutputStream 标准碗吗？”
        于是大厨开开心心地抡起大勺，把做好的“JSON 浓汤”哗啦啦地往漏斗里倒。
        奇迹发生了： 大厨以为自己倒进了一个普通的碗里，但他倒出来的每一滴汤（每一个 byte），都顺着漏斗，一滴不漏地、原封不动地直接落在了 Netty 的高科技外星盘子（ByteBuf）上！
     */
}
