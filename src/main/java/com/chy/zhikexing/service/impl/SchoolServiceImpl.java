package com.chy.zhikexing.service.impl;

import com.chy.zhikexing.entity.po.School;
import com.chy.zhikexing.mapper.SchoolMapper;
import com.chy.zhikexing.service.ISchoolService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 校区表 服务实现类
 */
@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements ISchoolService {

}