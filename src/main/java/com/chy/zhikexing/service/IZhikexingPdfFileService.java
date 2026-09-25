package com.chy.zhikexing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.chy.zhikexing.entity.po.ZhikexingPdfFile;

public interface IZhikexingPdfFileService extends IService<ZhikexingPdfFile> {

    ZhikexingPdfFile getByChatId(String chatId);

    void saveOrReplace(ZhikexingPdfFile pdfFile);

    void updateVectorStatus(Long id, int vectorStatus);
}
