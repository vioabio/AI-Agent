package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.run.AnswerProjection;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** N4 稳定答案投影只读入口，供反馈闭环和前端详情页回读。 */
@Tag(name = "答案投影", description = "按用户隔离读取不可变答案及其版本血缘")
@RestController
@RequestMapping("/answers")
public class AnswerProjectionController {
    private final PokemonAgentRunService runService;
    private final SecurityProperties securityProperties;

    @Autowired
    public AnswerProjectionController(PokemonAgentRunService runService,
                                      SecurityProperties securityProperties) {
        if (runService == null || securityProperties == null) {
            throw new IllegalArgumentException("答案投影依赖不能为空");
        }
        this.runService = runService;
        this.securityProperties = securityProperties;
    }

    /** 兼容本地程序化测试构造器；生产使用带 SecurityProperties 的构造器。 */
    public AnswerProjectionController(PokemonAgentRunService runService) {
        this(runService, SecurityProperties.defaults());
    }

    @Operation(summary = "读取稳定答案投影")
    @GetMapping("/{answerId}")
    public AnswerProjection get(@PathVariable String answerId,
                                @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        String userId = scopedUser(userHeader);
        return runService.findAnswer(answerId, userId)
                .orElseThrow(() -> new AnswerNotFoundException(answerId));
    }

    private String scopedUser(String userHeader) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) return String.valueOf(authenticated);
        if (securityProperties.isEnabled()) throw new UnauthenticatedException();
        if (userHeader == null || userHeader.isBlank()) return "anonymous";
        return userHeader.trim();
    }

    @ExceptionHandler(AnswerNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    private static final class AnswerNotFoundException extends RuntimeException {
        private AnswerNotFoundException(String answerId) {
            super("答案不存在或不属于当前用户: " + answerId);
        }
    }

    private static final class UnauthenticatedException extends RuntimeException {
    }
}
