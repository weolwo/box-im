package com.bx.implatform.config;

import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class MybatisPlusConfig {

    /**
     * 自定义 SQL 注入器
     */
    @Bean
    public DefaultSqlInjector customSqlInjector() {
        return new DefaultSqlInjector() {
            @Override
            public List<AbstractMethod> getMethodList(Class<?> mapperClass, TableInfo tableInfo) {
                // 1. 先拿到原版自带的所有基础方法 (insert, selectById 等)
                List<AbstractMethod> methodList = super.getMethodList(mapperClass, tableInfo);

                // 第一个参数："insertBatch" 就是你最终在 Mapper 里要用的名字。
                // 第二个参数：过滤规则。t -> !t.isLogicDelete() 意思是忽略逻辑删除字段。你也可以直接传 t -> true（全字段插入）。
                methodList.add(new InsertBatchSomeColumn("insertBatch", t -> true));
                return methodList;
            }
        };
    }
}