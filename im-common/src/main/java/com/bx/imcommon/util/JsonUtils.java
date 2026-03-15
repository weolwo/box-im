package com.bx.imcommon.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 终极版 Jackson JSON 工具类
 */
@Slf4j
public class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    static {
        // ======================== 核心防御性配置 ========================

        // 1. 忽略 JSON 字符串中存在，但 Java 对象中不存在的属性，防止接口新增字段导致老代码反序列化崩溃（极度重要！）
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 2. 忽略空 Bean 转 JSON 报错（比如对象里没有任何属性时，默认会抛异常，关掉它）
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        // 3. 可选：序列化时忽略 null 值，能大幅节省网络带宽（如果有特殊业务需要传 null 占位，把这行注释掉即可）
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // ======================== 优雅的 Java 8 时间处理 ========================

        JavaTimeModule javaTimeModule = new JavaTimeModule();
        // 设置 LocalDateTime 的全局序列化和反序列化格式
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT);
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));

        MAPPER.registerModule(javaTimeModule);

        // 关闭将日期序列化为时间戳（默认会变成一个 long 数字），统一使用上面的标准格式字符串
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 获取 ObjectMapper 的单例对象（有时候你想借用底层的 mapper 做些复杂操作时用）
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * Object 转 JSON 字符串
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("对象转 JSON 字符串失败", e);
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /**
     * JSON 字符串转 Object
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isEmpty() || clazz == null) {
            return null;
        }
        try {
            return clazz.equals(String.class) ? (T) json : MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON 字符串转 Object 失败", e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    /**
     * JSON 字符串转 复杂泛型对象（比如 Map<String, List<User>> 这种）
     * 示例: JsonUtils.parseType(json, new TypeReference<Map<String, List<User>>>() {});
     */
    public static <T> T parseType(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty() || typeReference == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("JSON 字符串转复杂泛型失败", e);
            throw new RuntimeException("JSON 复杂泛型反序列化失败", e);
        }
    }

    /**
     * JSON 字符串转 List<T> 集合
     */
    public static <T> List<T> parseList(String json, Class<T> elementClass) {
        if (json == null || json.isEmpty() || elementClass == null) {
            return null;
        }
        try {
            // 构造出 List<T> 的泛型类型
            JavaType javaType = MAPPER.getTypeFactory().constructParametricType(List.class, elementClass);
            return MAPPER.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            log.error("JSON 字符串转 List 失败", e);
            throw new RuntimeException("JSON List 反序列化失败", e);
        }
    }
}