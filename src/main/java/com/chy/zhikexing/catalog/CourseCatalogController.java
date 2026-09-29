package com.chy.zhikexing.catalog;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CourseCatalogController {
    private final CourseCatalogService catalog;

    public CourseCatalogController(CourseCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/courses")
    public CourseCatalogService.CoursePage courses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer edu,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "true") boolean ascending,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        return catalog.page(new CourseCatalogService.Filter(keyword, type, edu, sortBy, ascending), page, pageSize);
    }

    @GetMapping("/courses/{id}")
    public CourseCatalogService.Course course(@PathVariable long id) {
        return catalog.course(id);
    }

    @GetMapping("/campuses")
    public List<CourseCatalogService.Campus> campuses() {
        return catalog.campuses();
    }
}
