package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.LocalAccountService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.time.Instant;
import java.util.Set;

@Configuration @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-accounts.enabled",havingValue="true")
public class LocalAccountSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain localAccountSecurity(HttpSecurity http,LocalAccountService accounts,OidcProperties oidc, CorsProperties cors) throws Exception {
        if(oidc.enabled()) throw new IllegalStateException("本地账号与 OIDC 不能同时启用");
        CookieCsrfTokenRepository csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("CSRF-TOKEN"); csrf.setHeaderName("X-CSRF-TOKEN"); csrf.setCookiePath("/");
        csrf.setCookieCustomizer(cookie->cookie.sameSite("Lax"));
        http.authorizeHttpRequests(a->a.requestMatchers("/auth/session","/auth/local/register","/auth/local/login","/auth/local/recover","/health","/ready","/actuator/health/**","/error").permitAll()
            // Trainer accounts must never inherit permissive local operator/header shortcuts.
            .requestMatchers("/knowledge/**","/admin/**","/metrics/**","/ready/details","/users/**","/hitl/**","/ai/**").denyAll()
            .anyRequest().authenticated())
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterBefore(new OidcOriginFilter(Set.copyOf(cors.allowedOrigins())),CsrfFilter.class)
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws java.io.IOException,ServletException {
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null && auth.getPrincipal() instanceof LocalAccountService.Account account) {
                        var session=request.getSession(false);
                        var until=session==null?null:session.getAttribute("localAccountDeadline");
                        boolean valid=until instanceof Instant deadline && Instant.now().isBefore(deadline)
                            && accounts.find(account.id()).map(a->a.version()==account.version()).orElse(false);
                        if(!valid) { if(session!=null)session.invalidate(); SecurityContextHolder.clearContext(); }
                    }
                    chain.doFilter(request,response);
                }
            },AuthorizationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(401)).accessDeniedHandler((req,res,ex)->res.sendError(403)))
            .logout(l->l.logoutUrl("/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID","CSRF-TOKEN").logoutSuccessHandler((req,res,auth)->res.setStatus(204)))
            .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
