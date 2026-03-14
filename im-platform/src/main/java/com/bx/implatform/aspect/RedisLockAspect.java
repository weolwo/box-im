package com.bx.implatform.aspect;

import cn.hutool.core.util.StrUtil;
import com.bx.implatform.annotation.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * @author: blue
 * @date: 2024-06-09
 * @version: 1.0
 */

@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)//优先级必须最高，需要包裹住事务
@Component
@RequiredArgsConstructor
public class RedisLockAspect {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final RedissonClient redissonClient;

    @Around("@annotation(com.bx.implatform.annotation.RedisLock)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RedisLock annotation = method.getAnnotation(RedisLock.class);

        String dynamicKey = parseKey(joinPoint);
        String lockKey = StrUtil.join(":", annotation.prefixKey(), dynamicKey);

        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;
        try {
            long waitTime = annotation.waitTime();
            long leaseTime = annotation.leaseTime();
            // 1. 尝试加锁 (支持自定义等待时间，完美兼容 Fail-fast 和 阻塞等待 两种场景)
            try {
                if (leaseTime == -1) {
                    // 激活看门狗
                    isLocked = lock.tryLock(waitTime, annotation.unit());
                } else {
                    // 自定义租期
                    isLocked = lock.tryLock(waitTime, leaseTime, annotation.unit());
                }
            } catch (InterruptedException e) {
                // 2. 响应中断，恢复线程状态，并抛出业务异常
                Thread.currentThread().interrupt();
                log.error("尝试获取分布式锁时线程被中断，Key: {}", lockKey, e);
                throw new RuntimeException("系统繁忙，请稍后再试");
            }

            if (!isLocked) {
                log.warn("获取分布式锁失败，阻止了并发操作。Key: {}", lockKey);
                throw new RuntimeException("操作太频繁，请稍后再试");
            }

            // 3. 执行核心业务
            return joinPoint.proceed();

        } finally {
            // 去掉了冗余的 lock.isLocked() 减少一次 Redis IO
            if (isLocked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                    log.debug("释放分布式锁成功，Key: {}", lockKey);
                } catch (Exception e) {
                    //防止解锁时的网络抖动掩盖了正常业务结果或导致事务意外回滚
                    log.error("释放分布式锁出现异常，Key: {} (可能 Redis 连接异常或锁已过期自动释放)", lockKey, e);
                }
            }
        }
    }

    private String parseKey(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RedisLock annotation = method.getAnnotation(RedisLock.class);
        String key = annotation.key();
        if (StrUtil.isEmpty(key)) {
            return Strings.EMPTY;
        }
        // el解析需要的上下文对象
        EvaluationContext context = new StandardEvaluationContext();
        // 参数名
        String[] params = parameterNameDiscoverer.getParameterNames(method);
        if (Objects.isNull(params)) {
            return key;
        }
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < params.length; i++) {
            context.setVariable(params[i], args[i]);//所有参数都作为原材料扔进去
        }
        Expression expression = parser.parseExpression(key);
        return expression.getValue(context, String.class);
    }


}
