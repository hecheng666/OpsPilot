package com.opspilot.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opspilot.demo.entity.OrderEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OrderMapper extends BaseMapper<OrderEntity> {

    /** 慢 SQL 素材 1：无索引字段 + 逐行函数，全表扫描且无法短路 */
    @Select("SELECT * FROM demo_order WHERE UPPER(remark) LIKE CONCAT('%', #{kw}, '%')")
    List<OrderEntity> slowLikeQuery(@Param("kw") String keyword);

    /** 慢 SQL 素材 2：深分页 */
    @Select("SELECT * FROM demo_order ORDER BY created_at DESC LIMIT #{offset}, 10")
    List<OrderEntity> deepPageQuery(@Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM demo_order")
    Long countAll();
}
