package com.chy.ai.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.chy.ai.entity.po.IiipPdfFile;

public interface IIiipPdfFileService extends IService<IiipPdfFile> {

    IiipPdfFile getByChatId(String chatId);

    void saveOrReplace(IiipPdfFile pdfFile);

    void updateVectorStatus(Long id, int vectorStatus);
}
