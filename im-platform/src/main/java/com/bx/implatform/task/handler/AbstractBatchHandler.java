package com.bx.implatform.task.handler;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;


@Slf4j
public abstract class AbstractBatchHandler<T> implements ApplicationRunner {

    // 1. 本地内存 MQ：存放尚未入库的消息 (容量 10 万，防止 OOM)
    private final BlockingQueue<T> messageQueue = new LinkedBlockingQueue<>(100000);

    // 2. 专属单线程：负责死循环打包数据
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Msg-Batch-Saver"));

    private volatile boolean running = true;

    // ⚡️ 暴露给子类的方法：把数据扔进队列
    public void submit(T entity) {
        if (!messageQueue.offer(entity)) {
            log.error("【极其危险】{} 内存队列已满，丢弃数据！", this.getClass().getSimpleName());
        }
    }

    /**
     * 批量执行 Insert
     */
    public abstract void flushToDb(List<T> messages);
    /**
     * 🚀 核心引擎：微批处理循环
     */
    @Override
    public void run(ApplicationArguments args) {
        worker.execute(() -> {
            log.info("🚀 消息微批处理引擎已启动...");
            List<T> buffer = new ArrayList<>(1000);

            while (running || !messageQueue.isEmpty()) {
                try {
                    // 1. 尝试从队列拿数据，最多等 1 秒。
                    // 这样设计保证了哪怕系统不忙，消息最多也就延迟 1 秒入库
                    T msg = messageQueue.poll(1, TimeUnit.SECONDS);

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
