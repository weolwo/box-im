package com.bx.imcommon.config;

import com.bx.imcommon.mq.RedisMQTemplate;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Resource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@EnableCaching
@Configuration
public class RedisConfig extends CachingConfigurerSupport {

    @Resource
    private RedisConnectionFactory factory;


    @Primary
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // 设置值（value）的序列化采用jackson2JsonRedisSerializer
        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer());
        redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer());
        // 设置键（key）的序列化采用StringRedisSerializer。
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean
    public RedisMQTemplate redisMQTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisMQTemplate redisTemplate = new RedisMQTemplate();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // 设置值（value）的序列化采用jackson2JsonRedisSerializer
        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer());
        redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer());
        // 设置键（key）的序列化采用StringRedisSerializer。
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager() {
        // 设置redis缓存管理器
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(600))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(factory).cacheDefaults(cacheConfiguration).build();
    }


    @Bean
    public Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer() {
        ObjectMapper om = new ObjectMapper();
        //告诉 Jackson：“别管字段是 private 还是 public，也别管有没有 getter/setter 方法，给我直接暴力反射，把对象里所有的属性全盘转化成 JSON！”
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 默认把 LocalDateTime 变成时间戳的恶心设定关掉，引入时间模块，让时间在 Redis 里以 2026-03-14 18:00:00 这种人类友好的字符串形式存在。
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.registerModule(new JavaTimeModule());
        //如果 Java 对象里某个字段是 null，存进 Redis 的时候就干脆别写这个字段了。这在海量数据的 Redis 里能省下巨量的内存空间。
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL); // 忽略空值
       // om.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        //忽略无效字段
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new Jackson2JsonRedisSerializer<>(om, Object.class);
    }
}
/*
    om.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    这一行是这段代码的灵魂，也是 Redis 反序列化不报错的救命稻草！

    如果不加这行会怎样？
    你往 Redis 里存了一个 User 对象，Redis 里保存成了 {"name":"Tom", "age":18}。
    当你从 Redis 里往外读数据，准备强转回 User 对象时，直接报 ClassCastException 异常挂掉！
    因为在读取的一瞬间，Jackson 只看到了一个普通的 JSON 字符串，它根本不知道这玩意原本是个 User 类，所以它会默认把它反序列化成一个 LinkedHashMap。

    加了这行之后发生了什么？
    它会强行在生成的 JSON 里塞入一个特殊的标记（通常是 @class），记录这个对象真实的 Java 类全限定名。
    存进 Redis 的数据变成了：{"@class":"com.bx.implatform.entity.User", "name":"Tom", "age":18}。
    这样取出来的时候，Jackson 看到 @class，就能完美地帮你还原成 User 对象了。

    🚨 架构师的 Code Review 吐槽时刻
    这段代码虽然解决了痛点，但作为一个“吹毛求疵”的架构师，一眼就能看出它身上带着时代的眼泪（和安全漏洞）：

    1. 这是一个被废弃的“危险方法”
    你如果在现代的 IDE 里看这行代码，enableDefaultTyping 绝对是被画上一条横线的（Deprecated 废弃的）。
    为什么被废弃？因为它是 Jackson 历史上最著名的反序列化 RCE（远程代码执行）漏洞的罪魁祸首！黑客可以故意在 JSON 的 @class 里伪造一个恶意的 Java 核心类（比如 JNDI 相关的类），当你的服务器反序列化这个 JSON 时，直接就能控制你的服务器。

    现代满分写法： 在比较新的 Spring Boot 中，应该换成更加安全的激活方式：
    om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

 */