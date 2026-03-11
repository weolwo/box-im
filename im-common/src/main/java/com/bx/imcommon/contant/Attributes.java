package com.bx.imcommon.contant;

import io.netty.util.AttributeKey;

/**
 * AttributeKey vs ThreadLocal
 * 特性	ThreadLocal	                            AttributeKey (Channel Attributes)
 * 绑定对象	    绑定到 当前线程 (Thread)	            绑定到 当前连接 (Channel)
 * 数据范围	    仅限当前线程访问	                    只要是同一个连接，在任何 Handler 中都能访问
 * 生命周期	    随线程销毁或手动 remove	            随连接（Socket）断开而自动销毁
 * Netty适用性	极其危险，容易导致内存泄漏或数据污染	    官方推荐，线程安全且逻辑隔离
 *
 * 为什么 Netty 中不能用 ThreadLocal？
 * 在传统的 Spring MVC（Servlet）模型中，一个请求由一个线程从头负责到底（Thread-per-Request）。但在 Netty 中：
 *
 *     线程与请求不绑定： 一个 EventLoop 线程可能同时处理成千上万个 Channel（连接）。
 *
 *     同一个连接跨线程： 虽然 Netty 保证同一个 Channel 的事件由同一个线程处理，但如果你在业务逻辑中使用了自定义线程池，处理过程就会跨越线程。
 *
 * 风险： 如果你在登录时把 userId 存入 ThreadLocal，当这个线程去处理另一个用户的消息时，就会发生数据串透（Data Leaking），导致 A 用户的消息被误认为是 B 用户发的。
 */
public class Attributes {

    // 使用 static final 保证只初始化一次，性能最高
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf(ChannelAttrKey.USER_ID);
    public static final AttributeKey<Integer> TERMINAL_TYPE = AttributeKey.valueOf(ChannelAttrKey.TERMINAL_TYPE);
    public static final AttributeKey<Long> HEARTBEAT_TIMES = AttributeKey.valueOf(ChannelAttrKey.HEARTBEAT_TIMES);

}
