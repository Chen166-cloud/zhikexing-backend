package com.chy.ai.controller;

import com.chy.ai.entity.vo.MessageVO;
import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ai/history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatMemoryRepository chatMemoryRepository;
    private final IIiipChatRecordService recordService;

    @RequestMapping("/{type}")
    public List<String> list(@PathVariable("type") String type) {
        return recordService.findConversationIds(type);
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        return chatMemoryRepository.findByConversationId(chatId).stream().map(MessageVO::new).toList();
    }

}
