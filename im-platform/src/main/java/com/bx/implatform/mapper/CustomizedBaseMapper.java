package com.bx.implatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CustomizedBaseMapper<T> extends BaseMapper<T> {

    // // 注意：参数名必须加 @Param("list")，这是底层解析的硬性要求
    int insertBatch(@Param("list") List<T> entityList);
}
