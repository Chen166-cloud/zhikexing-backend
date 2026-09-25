package com.chy.zhikexing.service.impl;

import com.chy.zhikexing.entity.po.ZhikexingPdfFile;
import com.chy.zhikexing.entity.vo.PdfFileDownload;
import com.chy.zhikexing.service.IZhikexingPdfFileService;
import com.chy.zhikexing.service.IFileService;
import com.chy.zhikexing.util.AliyunOSSOperator;
import com.chy.zhikexing.util.OssUploadResult;
import com.chy.zhikexing.util.UserHolder;
import com.chy.zhikexing.entity.vo.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.legacy-ai-enabled", havingValue = "true")
public class FileServiceImpl implements IFileService {


    private static final int VECTOR_STATUS_NOT_STORED = 0;
    private static final int VECTOR_STATUS_STORED = 1;
    private static final int VECTOR_STATUS_FAILED = 2;
    private static final int MIN_PARAGRAPH_CHARS = 120;
    private static final int MAX_PARAGRAPH_CHARS = 800;
    private static final int LONG_PARAGRAPH_OVERLAP_CHARS = 100;
    private static final String PARAGRAPH_SEPARATOR = "\n\n";
    private static final String SENTENCE_BOUNDARIES = "。！？；.!?;";

    private final VectorStore vectorStore;

    private final AliyunOSSOperator aliyunOSSOperator;

    private final IZhikexingPdfFileService pdfFileService;

    @Value("${spring.ai.vectorstore.redis.index-name:zhikexing-pdf-index}")
    private String vectorIndexName;

    @Override
    public boolean save(String chatId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String normalizedChatId = chatId.trim();

        ZhikexingPdfFile pdfFile = null;
        try {
            String originalFilename = StringUtils.cleanPath(
                    Objects.requireNonNullElse(file.getOriginalFilename(), "document.pdf")
            );
            String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/pdf";
            byte[] content = file.getBytes();
            if (content.length < 5 || !new String(content, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new IllegalArgumentException("文件内容不是PDF");
            }
            OssUploadResult uploadResult = aliyunOSSOperator.upload(content, originalFilename, contentType, normalizedChatId);

            LocalDateTime now = LocalDateTime.now();
            pdfFile = new ZhikexingPdfFile()
                    .setChatId(normalizedChatId)
                    .setUserId(currentUserId())
                    .setOriginalFilename(originalFilename)
                    .setOssBucket(uploadResult.bucketName())
                    .setOssKey(uploadResult.ossKey())
                    .setFileSize((long) content.length)
                    .setContentType(contentType)
                    .setVectorIndexName(vectorIndexName)
                    .setVectorStatus(VECTOR_STATUS_NOT_STORED)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            pdfFileService.saveOrReplace(pdfFile);

            boolean vectorStored = writeToVectorStore(file.getResource(), normalizedChatId);
            pdfFileService.updateVectorStatus(pdfFile.getId(), vectorStored ? VECTOR_STATUS_STORED : VECTOR_STATUS_FAILED);
            return vectorStored;
        } catch (Exception e) {
            if (pdfFile != null) {
                pdfFileService.updateVectorStatus(pdfFile.getId(), VECTOR_STATUS_FAILED);
            }
            log.error("Failed to upload PDF to OSS or write vector store.", e);
            return false;
        }
    }

    @Override
    public PdfFileDownload getFile(String chatId) {
        ZhikexingPdfFile pdfFile = pdfFileService.getByChatId(chatId);
        if (pdfFile == null) {
            return null;
        }
        try {
            byte[] content = aliyunOSSOperator.download(pdfFile.getOssKey());
            Resource resource = new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return pdfFile.getOriginalFilename();
                }
            };
            String contentType = StringUtils.hasText(pdfFile.getContentType())
                    ? pdfFile.getContentType()
                    : "application/pdf";
            return new PdfFileDownload(resource, pdfFile.getOriginalFilename(), contentType, content.length);
        } catch (Exception e) {
            log.error("Failed to download PDF from OSS. chatId={}", chatId, e);
            return null;
        }
    }

    private boolean writeToVectorStore(Resource resource, String chatId) {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1)
                        .build()
        );
        List<Document> documents = splitByParagraphs(reader.read(), chatId);
        if (documents.isEmpty()) {
            log.warn("No text segments extracted from PDF resource: {}", resource.getFilename());
            return false;
        }
        vectorStore.add(documents);
        return true;
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
                    metadata.put("user_id", String.valueOf(currentUserId()));
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

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return UserHolder.requireUserId();
    }
}
