package com.chy.ai.controller;

import com.chy.ai.entity.po.IiipChatRecord;
import com.chy.ai.entity.vo.MessageVO;
import com.chy.ai.entity.vo.Result;
import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai/history")
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.legacy-ai-enabled", havingValue = "true")
public class ChatHistoryController {

    private final ChatMemoryRepository chatMemoryRepository;
    private final IIiipChatRecordService recordService;

    @RequestMapping("/{type}")
    public List<String> list(@PathVariable("type") String type) {
        return recordService.findConversationIds(type);
    }

    @PostMapping("/{type}")
    public Result createChat(@PathVariable("type") String type, @RequestBody Map<String, String> body) {
        String chatId = body.get("id");
        recordService.saveRecord(type, chatId);
        return Result.ok();
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        String conversationId = recordService.resolveConversationId(type, chatId);
        return chatMemoryRepository.findByConversationId(conversationId).stream().map(MessageVO::new).toList();
    }

    @DeleteMapping("/{type}/{chatId}")
    public Result deleteChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        recordService.deleteByConversationId(chatId);
        return Result.ok();
    }

    @GetMapping("/{type}/titles")
    public List<IiipChatRecord> listTitles(@PathVariable("type") String type) {
        return recordService.updateAndListTitles(type);
    }

}
