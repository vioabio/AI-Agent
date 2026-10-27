package com.vio.vioaiagent.pokemon.team;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile("local & !prod & !demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name={"vio.run.durable-resume","vio.agent.v1.enabled"},havingValue="false",matchIfMissing=true)
public class TeamWorkflowController {
    private final TeamWorkflowService workflow;
    private final com.vio.vioaiagent.controller.HitlController approvals;
    @org.springframework.beans.factory.annotation.Autowired
    public TeamWorkflowController(TeamWorkflowService workflow,com.vio.vioaiagent.controller.HitlController approvals) { this.workflow=workflow;this.approvals=approvals; }
    public TeamWorkflowController(TeamWorkflowService workflow) { this(workflow,null); }
    @ExceptionHandler(com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> notFound() {
        return org.springframework.http.ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404,"队伍预览不存在"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> statusError(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(Result.error(error.getStatusCode().value(),error.getReason()==null?"队伍操作未完成":error.getReason()));
    }
    @GetMapping("/chat/runs/{runId}/team-change")
    public Result<TeamWorkflowService.Outcome> outcome(@PathVariable String runId) {
        return Result.success(TeamChangePresentation.outcome(workflow.outcome(runId,owner())));
    }
    /** Local trainers may decide only their own S1 proposal. /hitl/** remains denied. */
    @PostMapping("/chat/runs/{runId}/team-change/decision")
    public Result<com.vio.vioaiagent.security.HitlManager.ApprovalDecision> decide(@PathVariable String runId,
            @RequestBody(required=false) DecisionBody body) {
        var outcome=workflow.outcome(runId,owner());
        var proposal=outcome.preview();
        if(body==null||body.approved()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择确认保存或取消");
        if(!proposal.proposalId().equals(body.approvalId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"队伍预览不存在");
        if(!java.time.Instant.now().isBefore(proposal.expiresAt()))throw new ResponseStatusException(HttpStatus.GONE,"队伍预览已过期，请重新生成");
        if(!"PENDING".equals(outcome.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已结束或预览已处理，请刷新任务并回读队伍核对");
        // Reuse the existing approval binding, expiry, final-decision checks and Run resume.
        // This adapter cannot approve any other tool or arbitrary approval id.
        return approvals.decideRunApproval(proposal.proposalId(),runId,body.approved(),body.approved()?"用户确认队伍配置":"用户取消队伍修改");
    }
    private String owner() {
        String owner=RequestContext.getPrincipalId();
        if(owner==null||owner.isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return owner;
    }
    public record DecisionBody(String approvalId,Boolean approved) { }
}
