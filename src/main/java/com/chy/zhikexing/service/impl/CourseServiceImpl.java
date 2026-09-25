package com.chy.zhikexing.service.impl;

import com.chy.zhikexing.entity.po.Course;
import com.chy.zhikexing.mapper.CourseMapper;
import com.chy.zhikexing.service.ICourseService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 学科表 服务实现类
 */
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements ICourseService {

}
