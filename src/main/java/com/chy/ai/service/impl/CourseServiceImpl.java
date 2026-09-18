package com.chy.ai.service.impl;

import com.chy.ai.entity.po.Course;
import com.chy.ai.mapper.CourseMapper;
import com.chy.ai.service.ICourseService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 学科表 服务实现类
 */
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements ICourseService {

}
