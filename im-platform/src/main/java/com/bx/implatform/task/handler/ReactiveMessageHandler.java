package com.bx.implatform.task.handler;

import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.event.GroupMessageEvent;
import com.bx.implatform.event.PrivateMessageEvent;
import com.bx.implatform.mapper.GroupMessageMapper;
import com.bx.implatform.mapper.PrivateMessageMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

//声明式数据流（Reactive Streams）
@RequiredArgsConstructor
@Component
@Slf4j
public class ReactiveMessageHandler implements ApplicationRunner {

    private final PrivateMessageMapper privateMessageMapper;
    private final GroupMessageMapper groupMessageMapper;

    /*
        Sinks.Many：这玩意儿就是一个高并发版的 LinkedBlockingQueue。
        bufferTimeout(500, 1秒)：这就相当于你自己写了一个后台线程，里面套了个 while(true)，不断去拉取队列里的数据。
        逻辑是：“凑够 500 条就立刻批量插数据库；如果半天凑不够 500 条，只要过了 1 秒钟，不管有几条也赶紧插数据库。”
        tryEmitNext：就是 queue.offer(message)，往队列里非阻塞地塞数据。
     */
    private final Sinks.Many<PrivateMessage> privateSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<GroupMessage> groupSink = Sinks.many().multicast().onBackpressureBuffer();
    // 🪂 降落伞锁：用于优雅停机时，阻塞 Spring 主线程，等待最后的数据落库
    private final CountDownLatch shutdownLatch = new CountDownLatch(2);

    public void run(ApplicationArguments args) throws Exception {
// 🌟 极度舒适！装配私聊流水线：只需一行代码！
        // 传入 Sink、传入要执行的方法引用 (Method Reference)、传入监控名字
        buildPipeline(privateSink, privateMessageMapper::insertBatch, "PrivateMessage");

        // 🌟 极度舒适！装配群聊流水线：同样只需一行代码！
        buildPipeline(groupSink, groupMessageMapper::insertBatch, "GroupMessage");
    }

    /**
     * 🏭 核心机密：响应式流水线装配工厂 (抽取出的超级底座)
     *
     * @param sink       发射器
     * @param saveAction 函数式接口：具体的批量保存动作
     * @param name       流水线名称（用于打日志和监控）
     */
    private <T> void buildPipeline(Sinks.Many<T> sink, Consumer<List<T>> saveAction, String name) {
        sink.asFlux()
                .bufferTimeout(500, Duration.ofSeconds(1))
                .doOnNext(messages -> safeInsert(messages, saveAction, name)) // 🛡️ 防弹衣隔离
                .checkpoint(name + "-BatchInsert-Pipeline")                  // 📍 追踪器
                .doFinally(signalType -> {
                    log.info("🛑 {} 流水线已断开，原因: {}", name, signalType);
                    shutdownLatch.countDown(); // 🪂 一条流水线安全落地，锁减 1
                })
                .subscribe();
    }

    /**
     * 🛡️ 通用隔离舱：不管是私聊还是群聊，异常全在这里被吃掉
     */
    private <T> void safeInsert(List<T> messages, Consumer<List<T>> saveAction, String name) {
        if (messages.isEmpty()) return;
        long start = System.currentTimeMillis();
        try {
            // ⚡️ 神奇的 apply：根据传进来的动作，自动决定去调哪个 Mapper！
            saveAction.accept(messages);
            log.info("✅ [{}] 批量落库 {} 条, 耗时 {} ms", name, messages.size(), (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("💥 [{}] 数据库批量写入失败！数据量: {}", name, messages.size(), e);
        }
    }

    // 3. 业务入口：监听 Spring 事件，往管道里扔数据
    //@EventListener
    public void onPrivateMessage(PrivateMessageEvent event) {
        // tryEmitNext 是非阻塞的，瞬间完成，绝不卡死 Netty 线程
        privateSink.tryEmitNext(event.getMessage());
    }

    //@EventListener
    public void onGroupMessage(GroupMessageEvent event) {
        groupSink.tryEmitNext(event.getMessage());
    }


    @PreDestroy
    public void shutdown() {
        log.info("⚠️ 准备关闭系统，正在强刷所有残余数据...");

        // 告诉所有发射器，下班了！赶紧把手里攒的数据发出去！
        privateSink.tryEmitComplete();
        groupSink.tryEmitComplete();

        try {
            // 死死等在这里，直到私聊和群聊的 doFinally 都执行完毕（倒数 2 次归零）
            boolean success = shutdownLatch.await(5, TimeUnit.SECONDS);
            if (!success) log.warn("⚠️ 停机超时，可能有残余数据未能落库！");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("✅ 所有微批处理流水线已安全落地！");
    }
}
