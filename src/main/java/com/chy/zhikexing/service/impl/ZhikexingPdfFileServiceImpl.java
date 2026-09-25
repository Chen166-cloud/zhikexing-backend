package com.chy.zhikexing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.chy.zhikexing.entity.po.ZhikexingPdfFile;
import com.chy.zhikexing.entity.vo.UserDTO;
import com.chy.zhikexing.mapper.ZhikexingPdfFileMapper;
import com.chy.zhikexing.service.IZhikexingPdfFileService;
import com.chy.zhikexing.util.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class ZhikexingPdfFileServiceImpl extends ServiceImpl<ZhikexingPdfFileMapper, ZhikexingPdfFile> implements IZhikexingPdfFileService {



    @Override
    public ZhikexingPdfFile getByChatId(String chatId) {
        return lambdaQuery()
                .eq(ZhikexingPdfFile::getChatId, normalizeChatId(chatId))
                .eq(ZhikexingPdfFile::getUserId, currentUserId())
                .last("limit 1")
                .one();
    }

    @Override
    public void saveOrReplace(ZhikexingPdfFile pdfFile) {
        remove(new LambdaQueryWrapper<ZhikexingPdfFile>()
                .eq(ZhikexingPdfFile::getChatId, normalizeChatId(pdfFile.getChatId()))
                .eq(ZhikexingPdfFile::getUserId, currentUserId()));
        save(pdfFile);
    }

    @Override
    public void updateVectorStatus(Long id, int vectorStatus) {
        if (id == null) {
            return;
        }
        lambdaUpdate()
                .eq(ZhikexingPdfFile::getId, id)
                .set(ZhikexingPdfFile::getVectorStatus, vectorStatus)
                .update();
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return UserHolder.requireUserId();
    }

    private String normalizeChatId(String chatId) {
        return chatId == null ? "" : chatId.trim();
    }
}
