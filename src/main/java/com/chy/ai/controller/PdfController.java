package com.chy.ai.controller;

import com.chy.ai.entity.vo.PdfFileDownload;
import com.chy.ai.entity.vo.Result;
import com.chy.ai.service.IFileService;
import com.chy.ai.service.IIiipChatRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/pdf")
public class PdfController {

    private final IFileService fileService;

    private final ChatClient pdfChatClient;

    private final IIiipChatRecordService recordService;

    /**
     * 文件上传
     */
    @RequestMapping("/upload/{chatId}")
    public Result uploadPdf(@PathVariable String chatId, @RequestParam("file") MultipartFile file) {
        try {
            if (!Objects.equals(file.getContentType(), "application/pdf")) {
                return Result.fail("只能上传PDF文件！");
            }
            boolean success = fileService.save(chatId, file);
            if (!success) {
                return Result.fail("保存文件失败！");
            }
            return Result.ok();
        } catch (Exception e) {
            log.error("Failed to upload PDF.", e);
            return Result.fail("上传文件失败！");
        }
    }

    /**
     * 文件下载
     */
    @GetMapping("/file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") String chatId) {
        PdfFileDownload fileDownload = fileService.getFile(chatId);
        if (fileDownload == null || !fileDownload.resource().exists()) {
            return ResponseEntity.notFound().build();
        }
        String disposition = ContentDisposition.attachment()
                .filename(fileDownload.filename(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileDownload.contentType()))
                .contentLength(fileDownload.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(fileDownload.resource());
    }

    @RequestMapping(value = "/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> chat(String prompt, String chatId) {
        recordService.saveRecord("pdf", chatId);
        String conversationId = recordService.resolveConversationId("pdf", chatId);
        return pdfChatClient
                .prompt(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "chat_id == '" + conversationId + "'"))
                .stream()
                .content();
    }
}
