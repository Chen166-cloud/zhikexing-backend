package com.chy.ai.tools;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.chy.ai.entity.po.Course;
import com.chy.ai.entity.po.CourseReservation;
import com.chy.ai.entity.po.School;
import com.chy.ai.entity.query.CourseQuery;
import com.chy.ai.service.ICourseReservationService;
import com.chy.ai.service.ICourseService;
import com.chy.ai.service.ISchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class CourseTools {

    private final ICourseService courseService;
    private final ISchoolService schoolService;
    private final ICourseReservationService courseReservationService;

    @Tool(description = "根据条件查询课程")
    public List<Course> queryCourse(@ToolParam(required = false, description = "课程查询条件") CourseQuery query) {
        if (query == null) query = new CourseQuery();
        QueryChainWrapper<Course> wrapper = courseService.query();
        wrapper
                .eq(query.getType() != null, "type", query.getType())
                .le(query.getEdu() != null, "edu", query.getEdu());
        if(query.getSorts() != null) {
            for (CourseQuery.Sort sort : query.getSorts()) {
                if (!java.util.Set.of("price", "duration").contains(sort.getField())) {
                    throw new IllegalArgumentException("排序字段只允许 price 或 duration");
                }
                wrapper.orderBy(true, !Boolean.FALSE.equals(sort.getAsc()), sort.getField());
            }
        }
        return wrapper.last("LIMIT 50").list();
    }

    @Tool(description = "查询所有校区")
    public List<School> queryAllSchools() {
        return schoolService.list();
    }

    // 写操作统一走运行草稿、人工审批和事务提交。
}
