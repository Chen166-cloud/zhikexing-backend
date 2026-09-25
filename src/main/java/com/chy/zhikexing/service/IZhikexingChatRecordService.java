package com.chy.zhikexing.service;

import com.chy.zhikexing.entity.po.ZhikexingChatRecord;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

public interface IZhikexingChatRecordService extends IService<ZhikexingChatRecord> {

    void saveRecord(String type, String conversionId);

    List<String> findConversationIds(String type);

    String resolveConversationId(String type, String conversationId);

    /**
     * 根据会话id删除会话记录，同时删除zhikexing_chat_record和spring_ai_chat_memory两张表中
     * 该会话id对应的全部记录
     *
     * @param conversationId 会话id
     */
    void deleteByConversationId(String conversationId);

    /**
     * 更新会话标题，逻辑与saveRecord中的标题生成一致
     *
     * @param conversationId 会话id
     */
    void updateTitle(String conversationId);

    /**
     * 更新指定类型下所有会话的标题，并返回更新后的会话标题列表
     *
     * @param type 会话类型
     * @return 更新后的会话记录列表
     */
    List<ZhikexingChatRecord> updateAndListTitles(String type);
}
