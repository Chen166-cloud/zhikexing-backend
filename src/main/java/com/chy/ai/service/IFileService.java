package com.chy.ai.service;

import com.chy.ai.entity.vo.PdfFileDownload;
import org.springframework.web.multipart.MultipartFile;

public interface IFileService {

    boolean save(String chatId, MultipartFile file);

    PdfFileDownload getFile(String chatId);
}
