package com.chy.zhikexing.service;

import com.chy.zhikexing.entity.vo.PdfFileDownload;
import org.springframework.web.multipart.MultipartFile;

public interface IFileService {

    boolean save(String chatId, MultipartFile file);

    PdfFileDownload getFile(String chatId);
}
