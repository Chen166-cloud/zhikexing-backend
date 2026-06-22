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
public class CustomerServiceController {

    private final ChatClient serviceChatClient;

    private final IIiipChatRecordService recordService;

    public CustomerServiceController(
            @Qualifier("serviceChatClient") ChatClient serviceChatClient,
            IIiipChatRecordService recordService) {
        this.serviceChatClient = serviceChatClient;
        this.recordService = recordService;
    }

    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
    public Flux<String> service(String prompt, String chatId) {
        // 1.保存会话id
        recordService.saveRecord("service", chatId);
        // 2.请求模型
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }
}
