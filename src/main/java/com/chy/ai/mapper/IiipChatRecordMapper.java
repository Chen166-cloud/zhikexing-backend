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

    /**
     * 根据会话id删除会话记录（iiip_chat_record表中id即为conversation_id）
     *
     * @param conversationId 会话id
     * @return 删除的记录数
     */
    int deleteByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);
}
