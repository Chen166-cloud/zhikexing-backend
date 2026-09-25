package com.chy.zhikexing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.zhikexing.entity.po.ZhikexingChatRecord;
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
public interface ZhikexingChatRecordMapper extends BaseMapper<ZhikexingChatRecord> {


    @Select("SELECT conversation_id FROM zhikexing_chat_record WHERE type = #{type} and user_id = #{userId} ORDER BY create_time DESC")
    List<String> findConversationIds(@Param("type") String type, @Param("userId") Long userId);

    /**
     * 根据客户端会话编号删除会话记录。
     *
     * @param conversationId 会话id
     * @return 删除的记录数
     */
    int deleteByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);
}
