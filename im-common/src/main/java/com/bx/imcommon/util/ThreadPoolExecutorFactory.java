package com.bx.imcommon.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 创建单例线程池
 *
 * @author Andrews
 * @date 2023/11/30 11:12
 */
@Slf4j
public final class ThreadPoolExecutorFactory {
    /**
     * 机器的CPU核数:Runtime.getRuntime().availableProcessors()
     * corePoolSize 池中所保存的线程数，包括空闲线程。
     * CPU 密集型：核心线程数 = CPU核数 + 1
     * IO 密集型：核心线程数 = CPU核数 * 2
     */
    private static final int CORE_POOL_SIZE =
        Math.min(ThreadPoolExecutorFactory.MAX_MUM_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);
    /**
     * maximumPoolSize - 池中允许的最大线程数(采用LinkedBlockingQueue时没有作用)。
     */
    private static final int MAX_MUM_POOL_SIZE = 100;
    /**
     * keepAliveTime -当线程数大于核心时，此为终止前多余的空闲线程等待新任务的最长时间，线程池维护线程所允许的空闲时间
     */
    private static final int KEEP_ALIVE_TIME = 1000;
    /**
     * 等待队列的大小。默认是无界的，性能损耗的关键
     */
    private static final int QUEUE_SIZE = 200;

    /**
     * 线程池对象
     */
    private static volatile ScheduledThreadPoolExecutor threadPoolExecutor = null;

    /**
     * 构造方法私有化
     */
    private ThreadPoolExecutorFactory() {
        if (null == threadPoolExecutor) {
            threadPoolExecutor = ThreadPoolExecutorFactory.getThreadPoolExecutor();
        }
    }


    /**
     * 双检锁创建线程安全的单例
     */
    public static ScheduledThreadPoolExecutor getThreadPoolExecutor() {
        if (null == threadPoolExecutor) {
            synchronized (ThreadPoolExecutorFactory.class) {
                if (null == threadPoolExecutor) {
                    threadPoolExecutor = new ScheduledThreadPoolExecutor(
                            //核心线程数
                            CORE_POOL_SIZE,
                            //拒绝策略
                            new ThreadPoolExecutor.CallerRunsPolicy()
                    );
                }
            }
        }
        return threadPoolExecutor;
    }

    /*
      shutDown 方法是 static 的，且操作的是静态变量 threadPoolExecutor：

      内存泄漏风险：如果这个线程池是随着 Spring Bean 生命周期的，手动管理静态线程池一定要确保在 @PreDestroy 里调用，否则多次重启单元测试或热部署时，旧线程会一直驻留在后台。
     */
    public static void shutDown() {
        if (threadPoolExecutor != null && !threadPoolExecutor.isShutdown()) {
            log.info("正在关闭消息处理线程池...");
            // 1. 停止接收新任务，但会继续处理队列中的任务
            threadPoolExecutor.shutdown();
           // 在 shutdown() 之后立刻判断 isTerminated()，它大概率返回 false（因为线程还在跑）
            try {
                // 2. 等待一段时间，让正在处理的任务完成
                // 注意：不要把这个放在 isTerminated() 的判断里
                if (!threadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("线程池在规定时间内未完全关闭，尝试强制停止...");
                    // 3. 如果等了5秒还没完，强制关闭
                    threadPoolExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("关闭线程池时被中断", e);
                threadPoolExecutor.shutdownNow();
                Thread.currentThread().interrupt(); // 保持中断状态
            }
            log.info("线程池已彻底关闭");
        }
    }

    /*
      这 5 种状态在 ThreadPoolExecutor 源码中是定义在 runState 中的，它们呈线性流转。

      1. RUNNING（运行中）
      状态描述：这是线程池最健康的状态。它能接受新任务，也能处理阻塞队列中的任务。
      触发时机：线程池一旦被 new 出来（初始化完成），就处于这个状态。

      2. SHUTDOWN（关闭中）
      状态描述：不接受新任务，但会继续处理阻塞队列中已经存在的任务。

      触发时机：调用了 shutdown() 方法。


      3. STOP（停止中）
      状态描述：不接受新任务，不处理队列中的任务，并且会中断正在处理任务的线程。

      触发时机：调用了 shutdownNow() 方法。


      4. TIDYING（整理中）
      状态描述：所有的任务都已经终止，工作线程数为 0。线程池在变成这个状态时，会执行 terminated() 这个钩子方法。

      触发时机：当线程池在 SHUTDOWN 状态下队列为空且工作线程为 0，或者在 STOP 状态下工作线程为 0。

      5. TERMINATED（彻底终止）
      状态描述：线程池的“终点站”。terminated() 方法已经执行完毕。

      触发时机：在 TIDYING 状态执行完 terminated() 之后。


     */
   //  @param runnable
    public static void execute(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        threadPoolExecutor.execute(runnable);
    }

}
