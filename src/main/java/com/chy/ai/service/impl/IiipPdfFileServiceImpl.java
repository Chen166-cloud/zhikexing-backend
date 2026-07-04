package com.chy.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chy.ai.entity.po.IiipPdfFile;
import com.chy.ai.mapper.IiipPdfFileMapper;
import com.chy.ai.service.IIiipPdfFileService;
import org.springframework.stereotype.Service;

@Service
public class IiipPdfFileServiceImpl extends ServiceImpl<IiipPdfFileMapper, IiipPdfFile> implements IIiipPdfFileService {

    @Override
    public IiipPdfFile getByChatId(String chatId) {
        return lambdaQuery()
                .eq(IiipPdfFile::getChatId, chatId)
                .last("limit 1")
                .one();
    }

    @Override
    public void saveOrReplace(IiipPdfFile pdfFile) {
        remove(new LambdaQueryWrapper<IiipPdfFile>().eq(IiipPdfFile::getChatId, pdfFile.getChatId()));
        save(pdfFile);
    }

    @Override
    public void updateVectorStatus(Long id, int vectorStatus) {
        if (id == null) {
            return;
        }
        lambdaUpdate()
                .eq(IiipPdfFile::getId, id)
                .set(IiipPdfFile::getVectorStatus, vectorStatus)
                .update();
    }
}
