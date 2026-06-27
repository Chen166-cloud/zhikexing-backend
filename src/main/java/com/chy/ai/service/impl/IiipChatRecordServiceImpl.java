package com.chy.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chy.ai.entity.po.IiipChatRecord;
import com.chy.ai.mapper.IiipChatRecordMapper;
import com.chy.ai.mapper.SpringAiChatMemoryMapper;
import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 会话历史记录 服务实现类
 * </p>
 *
 */
@Service
@RequiredArgsConstructor
public class IiipChatRecordServiceImpl extends ServiceImpl<IiipChatRecordMapper, IiipChatRecord> implements IIiipChatRecordService {

    private final SpringAiChatMemoryMapper springAiChatMemoryMapper;

    @Override
    public void saveRecord(String type, String conversionId) {
        // 1. 判断记录是否存在
        Long count = this.lambdaQuery()
                .eq(IiipChatRecord::getId, conversionId)
                .count();
        if (count != null && count > 0) {
            // 记录已存在，结束
            return;
        }
        // 2. 保存记录
        IiipChatRecord record = new IiipChatRecord();
        record.setType(type);
        record.setId(conversionId);
        // TODO userId暂时写死, 后续会从session中获取
        record.setUserId(1L);
        // 会话标题：取第一条USER消息内容的前6个字符，若为空则使用会话id
        String earliestUserContent = springAiChatMemoryMapper.findEarliestUserContentByConversationId(conversionId);
        String title = (earliestUserContent != null && !earliestUserContent.isEmpty())
                ? earliestUserContent.substring(0, Math.min(6, earliestUserContent.length()))
                : conversionId;
        record.setTitle(title);
        record.setCreateTime(LocalDateTime.now());
        save(record);
    }

    @Override
    public List<String> findConversationIds(String type) {
        // TODO userId暂时写死, 后续会从session中获取
        return this.getBaseMapper().findConversationIds(type, 1L);
    }

    @Override
    public void updateTitle(String conversationId) {
        // 会话标题：取第一条USER消息内容的前6个字符，若为空则使用会话id
        String earliestUserContent = springAiChatMemoryMapper.findEarliestUserContentByConversationId(conversationId);
        String title = (earliestUserContent != null && !earliestUserContent.isEmpty())
                ? earliestUserContent.substring(0, Math.min(6, earliestUserContent.length()))
                : conversationId;
        this.lambdaUpdate()
                .eq(IiipChatRecord::getId, conversationId)
                .set(IiipChatRecord::getTitle, title)
                .update();
    }

    @Override
    public List<IiipChatRecord> updateAndListTitles(String type) {
        // 1. 更新该类型下所有会话的标题
        List<String> conversationIds = this.getBaseMapper().findConversationIds(type, 1L);
        for (String conversationId : conversationIds) {
            updateTitle(conversationId);
        }
        // 2. 返回更新后的会话记录列表
        return this.lambdaQuery()
                .eq(IiipChatRecord::getType, type)
                .eq(IiipChatRecord::getUserId, 1L)
                .orderByDesc(IiipChatRecord::getCreateTime)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByConversationId(String conversationId) {
        // 1. 删除 iiip_chat_record 表中对应会话id的记录
        this.getBaseMapper().deleteByConversationId(conversationId);

        // 2. 删除 spring_ai_chat_memory 表中对应会话id的全部聊天记忆记录
        springAiChatMemoryMapper.deleteByConversationId(conversationId);
    }
}
