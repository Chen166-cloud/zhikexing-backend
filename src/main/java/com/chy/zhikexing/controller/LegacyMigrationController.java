package com.chy.zhikexing.controller;

import com.chy.zhikexing.entity.vo.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 保留旧入口的迁移提示，避免历史链接变成没有说明的 404 页面。 */
@RestController
public class LegacyMigrationController {
    @RequestMapping("/ai/**")
    public ResponseEntity<Result> migrated() {
        return ResponseEntity.status(410).body(Result.fail("此功能已迁入 Agent 工作台，请使用新的会话与知识库入口"));
    }
}
