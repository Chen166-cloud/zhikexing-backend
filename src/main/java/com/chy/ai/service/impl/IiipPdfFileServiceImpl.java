package com.chy.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chy.ai.entity.po.IiipPdfFile;
import com.chy.ai.entity.vo.UserDTO;
import com.chy.ai.mapper.IiipPdfFileMapper;
import com.chy.ai.service.IIiipPdfFileService;
import com.chy.ai.util.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class IiipPdfFileServiceImpl extends ServiceImpl<IiipPdfFileMapper, IiipPdfFile> implements IIiipPdfFileService {

    private static final long DEFAULT_USER_ID = 1L;

    @Override
    public IiipPdfFile getByChatId(String chatId) {
        return lambdaQuery()
                .eq(IiipPdfFile::getChatId, normalizeChatId(chatId))
                .eq(IiipPdfFile::getUserId, currentUserId())
                .last("limit 1")
                .one();
    }

    @Override
    public void saveOrReplace(IiipPdfFile pdfFile) {
        remove(new LambdaQueryWrapper<IiipPdfFile>()
                .eq(IiipPdfFile::getChatId, normalizeChatId(pdfFile.getChatId()))
                .eq(IiipPdfFile::getUserId, currentUserId()));
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

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null || user.getId() == null ? DEFAULT_USER_ID : user.getId();
    }

    private String normalizeChatId(String chatId) {
        return chatId == null ? "" : chatId.trim();
    }
}
