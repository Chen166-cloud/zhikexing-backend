package com.chy.ai.entity.vo;

import org.springframework.core.io.Resource;

public record PdfFileDownload(Resource resource, String filename, String contentType, long contentLength) {
}
