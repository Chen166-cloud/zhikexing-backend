package com.chy.ai.service.impl;

import com.chy.ai.service.IFileService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements IFileService {

    private static final int MIN_PARAGRAPH_CHARS = 120;
    private static final int MAX_PARAGRAPH_CHARS = 800;
    private static final int LONG_PARAGRAPH_OVERLAP_CHARS = 100;
    private static final String PARAGRAPH_SEPARATOR = "\n\n";
    private static final String SENTENCE_BOUNDARIES = "。！？；.!?;";

    private final VectorStore vectorStore;

    // 会话id 与 文件名的对应关系，方便查询会话历史时重新加载文件
    private final Properties chatFiles = new Properties();

    @Override
    public boolean save(String chatId, Resource resource) {
        // 1.保存到本地磁盘
        String filename = resource.getFilename();
        File target = new File(Objects.requireNonNull(filename));
        if (!target.exists()) {
            try {
                Files.copy(resource.getInputStream(), target.toPath());
            } catch (IOException e) {
                log.error("Failed to save PDF resource.", e);
                return false;
            }
        }
        // 2.保存映射关系
        chatFiles.put(chatId, filename);
        // 3.写入向量库
        writeToVectorStore(resource, chatId);
        return true;
    }

    @Override
    public Resource getFile(String chatId) {
        return new FileSystemResource(chatFiles.getProperty(chatId));
    }

    @PostConstruct
    private void init() {
        FileSystemResource pdfResource = new FileSystemResource("chat-pdf.properties");
        if (pdfResource.exists()) {
            try {
                chatFiles.load(new BufferedReader(new InputStreamReader(pdfResource.getInputStream(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @PreDestroy
    private void persistent() {
        try {
            chatFiles.store(new FileWriter("chat-pdf.properties"), LocalDateTime.now().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeToVectorStore(Resource resource, String chatId) {
        // 1.创建PDF的读取器
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource, // 文件源
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1) // 每1页PDF作为一个Document
                        .build()
        );
        // 2.按页读取PDF，再按自然段落切分。短段落合并，长段落二次切分。
        List<Document> documents = splitByParagraphs(reader.read(), chatId);
        if (documents.isEmpty()) {
            log.warn("No text segments extracted from PDF resource: {}", resource.getFilename());
            return;
        }
        // 3.写入向量库
        vectorStore.add(documents);
    }

    private List<Document> splitByParagraphs(List<Document> pageDocuments, String chatId) {
        List<Document> paragraphDocuments = new ArrayList<>();
        int segmentIndex = 0;
        for (Document pageDocument : pageDocuments) {
            List<String> paragraphs = mergeShortParagraphs(splitParagraphs(pageDocument.getText()));
            int paragraphIndex = 0;
            for (String paragraph : paragraphs) {
                List<String> chunks = splitLongParagraph(paragraph);
                for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                    Map<String, Object> metadata = new HashMap<>(pageDocument.getMetadata());
                    metadata.put("chat_id", chatId);
                    metadata.put("paragraph_index", paragraphIndex);
                    metadata.put("chunk_index", chunkIndex);
                    metadata.put("segment_index", segmentIndex++);
                    paragraphDocuments.add(new Document(chunks.get(chunkIndex), metadata));
                }
                paragraphIndex++;
            }
        }
        return paragraphDocuments;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return paragraphs;
        }
        for (String paragraph : text.split("\\R\\s*\\R+")) {
            String normalized = normalizeParagraph(paragraph);
            if (!normalized.isBlank()) {
                paragraphs.add(normalized);
            }
        }
        return paragraphs;
    }

    private String normalizeParagraph(String paragraph) {
        return paragraph
                .replaceAll("\\R+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> mergeShortParagraphs(List<String> paragraphs) {
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (buffer.isEmpty()) {
                buffer.append(paragraph);
                continue;
            }
            int mergedLength = buffer.length() + PARAGRAPH_SEPARATOR.length() + paragraph.length();
            if (buffer.length() < MIN_PARAGRAPH_CHARS || (paragraph.length() < MIN_PARAGRAPH_CHARS && mergedLength <= MAX_PARAGRAPH_CHARS)) {
                buffer.append(PARAGRAPH_SEPARATOR).append(paragraph);
            } else {
                merged.add(buffer.toString());
                buffer.setLength(0);
                buffer.append(paragraph);
            }
        }
        if (!buffer.isEmpty()) {
            merged.add(buffer.toString());
        }
        return merged;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        if (paragraph.length() <= MAX_PARAGRAPH_CHARS) {
            chunks.add(paragraph);
            return chunks;
        }

        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + MAX_PARAGRAPH_CHARS, paragraph.length());
            if (end < paragraph.length()) {
                int boundary = findSentenceBoundary(paragraph, start, end);
                if (boundary > start) {
                    end = boundary + 1;
                }
            }

            String chunk = paragraph.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= paragraph.length()) {
                break;
            }
            start = Math.max(end - LONG_PARAGRAPH_OVERLAP_CHARS, start + 1);
            while (start < paragraph.length() && Character.isWhitespace(paragraph.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    private int findSentenceBoundary(String text, int start, int end) {
        int minBoundary = start + MIN_PARAGRAPH_CHARS;
        for (int i = end - 1; i >= minBoundary; i--) {
            if (SENTENCE_BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
