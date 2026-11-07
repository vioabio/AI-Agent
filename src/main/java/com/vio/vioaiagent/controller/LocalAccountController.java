package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.security.LocalAccountService;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/auth/local") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-accounts.enabled",havingValue="true")
public class LocalAccountController {
    private final LocalAccountService accounts;
    private final com.vio.vioaiagent.security.LocalDataClaimService claims;
    public LocalAccountController(LocalAccountService accounts,com.vio.vioaiagent.security.LocalDataClaimService claims) { this.accounts=accounts; this.claims=claims; }
    public record Register(String username,String password,String nickname) {}
    public record Login(String username,String password) {}
    public record ProfileUpdate(String nickname,String avatar) {}
    public record PasswordChange(String currentPassword,String newPassword) {}
    public record Recovery(String username,String recoveryCode,String newPassword) {}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.Operation(operationId="registerLocalAccount")
    public LocalAccountService.Registration register(@RequestBody Register body,HttpServletRequest request) {
        return accounts.register(body.username(),body.password(),body.nickname(),request.getRemoteAddr());
    }
    @PostMapping("/login")
    @io.swagger.v3.oas.annotations.Operation(operationId="loginLocalAccount")
    public LocalAccountService.Account login(@RequestBody Login body,HttpServletRequest request,HttpServletResponse response) {
        var account=accounts.login(body.username(),body.password(),request.getRemoteAddr());
        createSession(account,request,response);
        return account;
    }
    private void createSession(LocalAccountService.Account account,HttpServletRequest request,HttpServletResponse response) {
        var existing=request.getSession(false); if(existing!=null)existing.invalidate();
        var session=request.getSession(true); session.setMaxInactiveInterval(1800);
        session.setAttribute("localAccountDeadline",Instant.now().plusSeconds(28800));
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account,null,List.of(new SimpleGrantedAuthority("ROLE_TRAINER"))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
    }
    private LocalAccountService.Account account() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof LocalAccountService.Account account)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return accounts.find(account.id()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="getLocalAccountProfile")
    @GetMapping("/profile") public LocalAccountService.Account profile() { return account(); }
    @io.swagger.v3.oas.annotations.Operation(operationId="updateLocalAccountProfile")
    @PatchMapping("/profile") public LocalAccountService.Account update(@RequestBody ProfileUpdate body) { return accounts.update(account(),body.nickname(),body.avatar()); }
    @io.swagger.v3.oas.annotations.Operation(operationId="changeLocalAccountPassword")
    @PostMapping("/password") public Map<String,String> password(@RequestBody PasswordChange body,HttpServletRequest request,HttpServletResponse response) {
        var account=account();
        String code=accounts.changePassword(account,body.currentPassword(),body.newPassword());
        // Replace this browser's session so a focus refresh cannot hide the one-time code.
        // Other sessions retain the old version and are rejected by the security filter.
        createSession(accounts.find(account.id()).orElseThrow(),request,response);
        return Map.of("recoveryCode",code);
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="recoverLocalAccount")
    @PostMapping("/recover") public Map<String,String> recover(@RequestBody Recovery body,HttpServletRequest request) {
        return Map.of("recoveryCode",accounts.recover(body.username(),body.recoveryCode(),body.newPassword(),request.getRemoteAddr()));
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="previewLocalLegacyData")
    @GetMapping("/legacy-data") public Map<String,Object> legacy() { return claims.preview(account()); }
    @io.swagger.v3.oas.annotations.Operation(operationId="claimLocalLegacyData")
    @PostMapping("/legacy-data") public Map<String,Object> claim(@RequestBody Login confirmation) {
        var account=account(); accounts.confirmPassword(account,confirmation.password()); return claims.claim(account);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> failure(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason()));
    }
}
