package com.chy.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.ai.entity.po.IiipChatRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 会话历史记录 Mapper 接口
 * </p>
 */
@Mapper
public interface IiipChatRecordMapper extends BaseMapper<IiipChatRecord> {


    @Select("SELECT id FROM iiip_chat_record WHERE type = #{type} and user_id = #{userId} ORDER BY create_time DESC")
    List<String> findConversationIds(@Param("type") String type, @Param("userId") Long userId);
}
