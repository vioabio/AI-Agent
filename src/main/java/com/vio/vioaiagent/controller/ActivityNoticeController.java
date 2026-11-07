package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeNotFoundException;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeService;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeUnavailableException;
import com.vio.vioaiagent.pokemon.service.PokemonVersionContextValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * N4 活动资讯只读 API。写入必须通过受审核的摄取/发布流程，Controller
 * 不提供任意用户提交活动内容的入口。
 */
@Tag(name = "Pokemon Activities", description = "版本化、带来源和有效期的活动资讯")
@RestController
@RequestMapping("/pokemon/activities")
public class ActivityNoticeController {
    private final ActivityNoticeService service;
    private final PokemonVersionContextValidator versionContextValidator;

    @org.springframework.beans.factory.annotation.Autowired
    public ActivityNoticeController(ActivityNoticeService service) {
        this(service, new PokemonVersionContextValidator());
    }

    /** 可注入 registry 快照的构造器；单参数程序化构造保持兼容。 */
    public ActivityNoticeController(ActivityNoticeService service,
                                    PokemonVersionContextValidator versionContextValidator) {
        if (service == null) throw new IllegalArgumentException("service 不能为空");
        this.service = service;
        this.versionContextValidator = versionContextValidator == null
                ? new PokemonVersionContextValidator() : versionContextValidator;
    }

    @Operation(summary = "查询当前有效活动资讯")
    @GetMapping
    public ResponseEntity<Result<ActivityNoticeService.ActivityQueryResult>> list(
            @RequestParam("gameReleaseId") String gameReleaseId,
            @RequestParam(value = "dlcId", required = false) String dlcId,
            @RequestParam("language") String language,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        PokemonVersionContextValidator.VersionedContext versioned =
                versionContextValidator.normalize(gameReleaseId, dlcId, language, null);
        return ResponseEntity.ok(Result.success(service.search(versioned.gameReleaseId(), versioned.dlcId(),
                versioned.language(), region, limit)));
    }

    @Operation(summary = "按版本 ID 获取当前有效活动资讯")
    @GetMapping("/{noticeId}")
    public ResponseEntity<Result<ActivityNotice>> get(@PathVariable String noticeId) {
        return ResponseEntity.ok(Result.success(service.find(noticeId)));
    }

    @ExceptionHandler(ActivityNoticeNotFoundException.class)
    public ResponseEntity<Result<Void>> notFound(ActivityNoticeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(ActivityNoticeUnavailableException.class)
    public ResponseEntity<Result<Void>> unavailable(ActivityNoticeUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Result.error(HttpStatus.GONE.value(), "活动资讯暂不可用"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

}
