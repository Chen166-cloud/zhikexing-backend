package com.chy.zhikexing.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.chy.zhikexing.entity.po.ZhikexingChatRecord;
import com.chy.zhikexing.entity.vo.UserDTO;
import com.chy.zhikexing.mapper.ZhikexingChatRecordMapper;
import com.chy.zhikexing.mapper.SpringAiChatMemoryMapper;
import com.chy.zhikexing.service.IZhikexingChatRecordService;
import com.chy.zhikexing.util.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ZhikexingChatRecordServiceImpl extends ServiceImpl<ZhikexingChatRecordMapper, ZhikexingChatRecord> implements IZhikexingChatRecordService {



    private final SpringAiChatMemoryMapper springAiChatMemoryMapper;

    @Override
    public void saveRecord(String type, String conversationId) {
        Long userId = currentUserId();
        String normalizedConversationId = normalizeConversationId(conversationId);
        ZhikexingChatRecord existing = findRecord(normalizedConversationId);
        if (existing != null) {
            if (!userId.equals(existing.getUserId()) || !type.equals(existing.getType())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
            }
            return;
        }

        ZhikexingChatRecord record = new ZhikexingChatRecord();
        record.setType(type);
        record.setConversationId(normalizedConversationId);
        record.setUserId(userId);
        record.setTitle(buildTitle(normalizedConversationId));
        record.setCreateTime(LocalDateTime.now());
        save(record);
    }

    @Override
    public List<String> findConversationIds(String type) {
        return getBaseMapper().findConversationIds(type, currentUserId());
    }

    @Override
    public String resolveConversationId(String type, String conversationId) {
        Long userId = currentUserId();
        String normalizedConversationId = normalizeConversationId(conversationId);
        ZhikexingChatRecord record = findRecord(normalizedConversationId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        if (!userId.equals(record.getUserId()) || (type != null && !type.isBlank() && !type.equals(record.getType()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        return normalizedConversationId;
    }

    @Override
    public void updateTitle(String conversationId) {
        Long userId = currentUserId();
        String normalizedConversationId = resolveConversationId(null, conversationId);
        lambdaUpdate()
                .eq(ZhikexingChatRecord::getConversationId, normalizedConversationId)
                .eq(ZhikexingChatRecord::getUserId, userId)
                .set(ZhikexingChatRecord::getTitle, buildTitle(normalizedConversationId))
                .update();
    }

    @Override
    public List<ZhikexingChatRecord> updateAndListTitles(String type) {
        Long userId = currentUserId();
        List<String> conversationIds = getBaseMapper().findConversationIds(type, userId);
        for (String conversationId : conversationIds) {
            updateTitle(conversationId);
        }
        return lambdaQuery()
                .eq(ZhikexingChatRecord::getType, type)
                .eq(ZhikexingChatRecord::getUserId, userId)
                .orderByDesc(ZhikexingChatRecord::getCreateTime)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByConversationId(String conversationId) {
        Long userId = currentUserId();
        String normalizedConversationId = resolveConversationId(null, conversationId);
        getBaseMapper().deleteByConversationId(normalizedConversationId, userId);
        springAiChatMemoryMapper.deleteByConversationId(normalizedConversationId);
    }

    private String buildTitle(String conversationId) {
        String earliestUserContent = springAiChatMemoryMapper.findEarliestUserContentByConversationId(conversationId);
        return earliestUserContent != null && !earliestUserContent.isEmpty()
                ? earliestUserContent.substring(0, Math.min(6, earliestUserContent.length()))
                : conversationId;
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return UserHolder.requireUserId();
    }

    private ZhikexingChatRecord findRecord(String conversationId) {
        return lambdaQuery()
                .eq(ZhikexingChatRecord::getConversationId, conversationId)
                .last("LIMIT 1")
                .one();
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话id不能为空");
        }
        return conversationId.trim();
    }
}
