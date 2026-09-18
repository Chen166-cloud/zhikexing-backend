package com.chy.ai.controller;

import com.chy.ai.entity.vo.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 旧入口停用时明确告知迁移位置，避免显示空白的 404 页面。 */
@RestController
@ConditionalOnProperty(name="app.legacy-ai-enabled",havingValue="false",matchIfMissing=true)
public class LegacyMigrationController {
    @RequestMapping("/ai/**")
    public ResponseEntity<Result> migrated() {
        return ResponseEntity.status(410).body(Result.fail("此功能已迁入 Agent 工作台，请使用新的会话与知识库入口"));
    }
}
