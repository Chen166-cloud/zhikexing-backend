package com.chy.zhikexing.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

class LegacyMigrationControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new LegacyMigrationController()).build();
    }

    @Test
    void retiredConversationAndDocumentUrlsExplainWhereToContinue() throws Exception {
        for (String path : List.of(
                "/ai/chat", "/ai/service", "/ai/game", "/ai/pdf/chat",
                "/ai/history/chat", "/ai/history/pdf/old-chat", "/ai/pdf/file/old-chat")) {
            mvc.perform(get(path).param("prompt", "旧客户端请求").param("chatId", "old-chat"))
                    .andExpect(status().isGone())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.ok").value(0))
                    .andExpect(jsonPath("$.msg", containsString("Agent 工作台")));
        }
    }

    @Test
    void retiredPdfUploadReturnsMigrationResponseInsteadOfAcceptingTheFile() throws Exception {
        var file = new MockMultipartFile("file", "old.pdf", "application/pdf",
                "%PDF-1.4 old client upload".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/ai/pdf/upload/old-chat").file(file))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg", containsString("知识库")));
    }

    @Test
    void retiredHistoryWritesReturnMigrationResponse() throws Exception {
        mvc.perform(post("/ai/history/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"old-chat\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.ok").value(0));
        mvc.perform(delete("/ai/history/chat/old-chat"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.ok").value(0));
    }
}
