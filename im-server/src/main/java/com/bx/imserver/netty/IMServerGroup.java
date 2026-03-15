package com.bx.imserver.netty;

import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.mq.RedisMQPullTask;
import com.bx.imcommon.mq.RedisMQTemplate;
import com.bx.imserver.util.SpringContextHolder;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@AllArgsConstructor
public class IMServerGroup implements CommandLineRunner {


    @Setter
    @Getter
    private static int serverId;

    private final RedisMQTemplate redisMQTemplate;

    private final List<IMServer> imServers;

    @Getter
    private static volatile CountDownLatch countDownLatch = null;


    /***
     * 判断服务器是否就绪
     *
     **/
    public boolean isReady() {
        return countDownLatch.getCount() == 0;
    }

    @Override
    public void run(String... args) throws InterruptedException {
        //引入状态管理
        // 初始化SERVER_ID
        if (countDownLatch == null) {
            synchronized (this) {
                if (countDownLatch == null) {
                    countDownLatch = new CountDownLatch(imServers.size());
                }
            }
        }
        setServerId(initServerId());
        log.info("服务器id {}",getServerId());
        // 启动服务
        for (IMServer imServer : imServers) {
            imServer.start();
        }
        // 稍微等一下，确保 Netty 真的 Ready
        countDownLatch.await(30, TimeUnit.SECONDS);

        SpringContextHolder.getBean(RedisMQPullTask.class).run();

    }

    @PreDestroy
    public void destroy() {

        //停止拉取消息
        SpringContextHolder.getBean(RedisMQPullTask.class).destroy();
        // 停止服务
        for (IMServer imServer : imServers) {
            imServer.stop();
        }
        //先删除redis中的服务id,让其他服务知道我下线了
        // 3. 主动释放 serverId 锁
        redisMQTemplate.delete(IMRedisKey.IM_MAX_SERVER_ID + getServerId());
        log.info(">>> serverId {} 已释放", getServerId());

    }

    private int initServerId() {

        for (int i = 1; i <= 10; i++) { // 假设集群最大1000台
            // 尝试锁定一个 ID，有效期 60 秒（配合心跳续租）
            String redisKey = IMRedisKey.IM_MAX_SERVER_ID + i;
            Boolean success = redisMQTemplate.opsForValue().setIfAbsent(IMRedisKey.IM_MAX_SERVER_ID + i, "ALIVE", 60, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(success)) {
                log.info("抢占 serverId 成功: {}", i);
                // 开启一个定时任务，每 30 秒续租一次这个 key
                startHeartbeatTask(redisKey);
                return i;
            }
        }
        throw new RuntimeException("无可用 serverId，集群规模已达上限");
    }

    private void startHeartbeatTask(String lockKey) {
        // 使用一个专门的小型调度线程池
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                redisMQTemplate.expire(lockKey, 60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("serverId 续租失败", e);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }
}
