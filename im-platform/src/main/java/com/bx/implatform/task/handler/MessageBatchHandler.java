package com.bx.implatform.task.handler;

import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.event.PrivateMessageEvent;
import com.bx.implatform.mapper.PrivateMessageMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@RequiredArgsConstructor
@Slf4j
@Component
@Order(100) // 数字越小，越先执行！比如先启动 Netty (@Order(10))，再启动批处理引擎 (@Order(100))
public class MessageBatchHandler implements ApplicationRunner {

    private final PrivateMessageMapper privateMessageMapper; // 或者 MyBatis Plus 的 IService

    // 1. 本地内存 MQ：存放尚未入库的消息 (容量 10 万，防止 OOM)
    private final BlockingQueue<PrivateMessage> messageQueue = new LinkedBlockingQueue<>(100000);

    // 2. 专属单线程：负责死循环打包数据
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Msg-Batch-Saver"));

    private volatile boolean running = true;

    /**
     * ⚡️ 监听器：听到广播后，什么都不干，只是把消息塞进内存队列
     * 注意：这里非常快，耗时不到 0.001 毫秒
     */
    @EventListener
    public void onMessageArrived(PrivateMessageEvent event) {
        boolean success = messageQueue.offer(event.getMessage());
        if (!success) {
            log.error("【极其危险】本地内存 MQ 已满，丢弃消息！");
            // 真实生产环境这里必须走降级逻辑，比如写本地磁盘文件
        }
    }

    /**
     * 🚀 核心引擎：微批处理循环
     */
    @Override
    public void run(ApplicationArguments args) {
        worker.execute(() -> {
            log.info("🚀 消息微批处理引擎已启动...");
            List<PrivateMessage> buffer = new ArrayList<>(1000);

            while (running || !messageQueue.isEmpty()) {
                try {
                    // 1. 尝试从队列拿数据，最多等 1 秒。
                    // 这样设计保证了哪怕系统不忙，消息最多也就延迟 1 秒入库
                    PrivateMessage msg = messageQueue.poll(1, TimeUnit.SECONDS);

                    if (msg != null) {
                        buffer.add(msg);
                        // 2. drainTo：瞬间把队列里剩下的消息全吸出来，最多吸 499 条！
                        messageQueue.drainTo(buffer, 999);
                    }

                    // 3. 触发落库条件：凑够了 500 条，或者虽然没凑够但缓冲池里有数据（因为 poll 超时了）
                    if (!buffer.isEmpty()) {
                        flushToDb(buffer);
                        buffer.clear(); // 清空缓冲池，迎接下一批
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("微批处理引擎被中断");
                } catch (Exception e) {
                    log.error("批量入库发生致命异常", e);
                }
            }
        });
    }

    /**
     * 批量执行 Insert
     */
    private void flushToDb(List<PrivateMessage> messages) {
        long start = System.currentTimeMillis();
        try {
            // 务必确保你的 Mapper 实现了真正的批量插入：
            // INSERT INTO table (a,b,c) VALUES (1,2,3), (4,5,6)...
            // 而不是在 for 循环里一条条调 insert！
            // 如果用 MyBatis Plus，可以直接调 privateMessageService.saveBatch(messages);
            privateMessageMapper.insertBatch(messages);
            log.info(" 成功批量入库 {} 条消息，耗时: {} ms", messages.size(), (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("批量保存失败！数据条数: {}", messages.size(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("关闭引擎失败", e);
        }
        log.info("消息微批处理引擎已安全关闭");
    }
}
