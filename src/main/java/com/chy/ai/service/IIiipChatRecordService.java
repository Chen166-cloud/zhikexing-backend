package com.chy.ai.service;

import com.chy.ai.entity.po.IiipChatRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IIiipChatRecordService extends IService<IiipChatRecord> {

    void saveRecord(String type, String conversionId);

    List<String> findConversationIds(String type);
}