package com.chy.ai.service;

import com.chy.ai.entity.po.IiipChatRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IIiipChatRecordService extends IService<IiipChatRecord> {

    void saveRecord(String type, String conversionId);

    List<String> findConversationIds(String type);

    /**
     * 根据会话id删除会话记录，同时删除iiip_chat_record和spring_ai_chat_memory两张表中
     * 该会话id对应的全部记录
     *
     * @param conversationId 会话id
     */
    void deleteByConversationId(String conversationId);
}