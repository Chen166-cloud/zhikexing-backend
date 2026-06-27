package com.chy.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.ai.entity.po.SpringAiChatMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * Spring AI 聊天记忆 Mapper 接口
 * </p>
 */
@Mapper
public interface SpringAiChatMemoryMapper extends BaseMapper<SpringAiChatMemory> {

    /**
     * 根据会话id删除聊天记忆记录
     *
     * @param conversationId 会话id
     * @return 删除的记录数
     */
    int deleteByConversationId(@Param("conversationId") String conversationId);

    /**
     * 根据会话id查询最早的USER消息内容，用于生成会话标题
     *
     * @param conversationId 会话id
     * @return 最早的USER消息内容，若无结果则返回null
     */
    String findEarliestUserContentByConversationId(@Param("conversationId") String conversationId);
}
