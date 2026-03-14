package com.bx.implatform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解
 */
@Retention(RetentionPolicy.RUNTIME)//运行时生效
@Target(ElementType.METHOD)//作用在方法上
public @interface RedisLock {

    /**
     * key的前缀,prefixKey+key就是redis的key
     */
    String prefixKey() ;

    /**
     * spel 表达式
     */
    String key() default "";

    // 等待锁的时间，默认 0（拿不到立刻失败，不阻塞）
    long waitTime() default 0;

    /**
     * 锁的租期/持有时间，默认-1，没有租期或者持续时间直接失败失败,redisson默认也是-1
     * 这里的 -1 是 Redisson 中一个极其关键的魔法值，它专门用来激活 Watchdog（看门狗）机制。
     * 如果传入具体的数值（比如 3000 毫秒）： 拿到锁之后，3 秒一到，不管你的业务有没有执行完，Redis 都会无情地把锁删掉。
     * 如果传入 -1： Redisson 就会触发看门狗。它会在后台启动一个定时任务（默认每 10 秒跑一次），只要你的业务代码还没执行完，
     * 它就会自动把锁的过期时间重新续满（默认续到 30 秒）。这就完美解决了“数据库操作太慢导致锁提前释放”的超级大坑！
     */
    int leaseTime() default -1;

    /**
     * 等待锁的时间单位，默认毫秒
     *
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;

}
