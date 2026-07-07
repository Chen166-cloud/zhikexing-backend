package com.chy.ai.controller;

import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class GameController {

    private final ChatClient gameChatClient;
    private final IIiipChatRecordService recordService;

    // 手动写构造函数，明确指定 Bean 的 ID
    public GameController(@Qualifier("gameChatClient") ChatClient gameChatClient,
                          IIiipChatRecordService recordService) {
        this.gameChatClient = gameChatClient;
        this.recordService = recordService;
    }



    @RequestMapping(value = "/game", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String prompt, String chatId) {
        recordService.saveRecord("game", chatId);
        String conversationId = recordService.resolveConversationId("game", chatId);
        return gameChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
