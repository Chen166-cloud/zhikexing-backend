package com.chy.ai.service.impl;

import com.chy.ai.entity.po.School;
import com.chy.ai.mapper.SchoolMapper;
import com.chy.ai.service.ISchoolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 校区表 服务实现类
 */
@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements ISchoolService {

}