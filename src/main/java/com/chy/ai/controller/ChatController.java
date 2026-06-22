package com.chy.ai.controller;

import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;
    private final IIiipChatRecordService recordService;
    // 2. 手动构造函数，同时注入两个 Bean
    public ChatController(
            @Qualifier("normalChatClient") ChatClient chatClient,
            IIiipChatRecordService recordService) {
        this.chatClient = chatClient;
        this.recordService = recordService;
    }

    // 请求方式和路径不要改动，将来要与前端联调
    @RequestMapping(value = "/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> chat(@RequestParam(defaultValue = "你好") String prompt,
                             @RequestParam("chatId") String chatId) {

        // 保存会话记录
        recordService.saveRecord("chat", chatId);
        return chatClient
                .prompt(prompt) // 传入user提示词
                .advisors(as -> as.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content();
//                .chatResponse()
//                .mapNotNull(this::handleReasonerMessage);
    }

//    private String handleReasonerMessage(ChatResponse response) {
//        // 获取消息，转为DeepSeekAssistantMessage
//        DeepSeekAssistantMessage message = (DeepSeekAssistantMessage) response.getResult().getOutput();
//        // 获取推理结果
//        String reasoningContent = message.getReasoningContent();
//        if (reasoningContent != null && !reasoningContent.isBlank()) {
//            // 如果推理结果存在，则将其包裹上<think></think>标签，方便前端处理
//            return "<think>" + reasoningContent + "</think>";
//        }
//        // 没有推理结果，直接返回文本
//        return message.getText();
//    }
}
