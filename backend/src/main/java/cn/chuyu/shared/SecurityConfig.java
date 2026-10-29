package cn.chuyu.shared;

import static cn.chuyu.shared.Json.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  BCryptPasswordEncoder encoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  org.springframework.security.core.userdetails.UserDetailsService customIdentityOnly() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "请使用平台登录接口");
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Store store) throws Exception {
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            String token = req.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
              var u =
                  store.one(
                      "SELECT u.* FROM auth_sessions s JOIN app_users u ON u.id=s.user_id WHERE"
                          + " s.token_hash=#{p.hash} AND s.expires_at>CURRENT_TIMESTAMP AND"
                          + " u.active=TRUE",
                      map("hash", hash(token.substring(7))));
              if (u != null) {
                var actor =
                    new Actors.Actor(
                        u.get("id").toString(),
                        u.get("username").toString(),
                        u.get("displayName").toString(),
                        u.get("role").toString(),
                        text(u, "ownerId"),
                        bool(u.get("mustChangePassword")));
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
                if (actor.mustChangePassword()
                    && !req.getRequestURI().matches("/api/v1/auth/(me|password|logout)")) {
                  res.setStatus(403);
                  res.setContentType("application/json;charset=UTF-8");
                  res.getWriter()
                      .write(
                          write(map("message", "首次登录请修改密码", "code", "PASSWORD_CHANGE_REQUIRED")));
                  return;
                }
              }
            }
            chain.doFilter(req, res);
          }
        };
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers("/api/v1/auth/login", "/actuator/health", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json;charset=UTF-8");
                      res.getWriter().write("{\"message\":\"请先登录\"}");
                    }))
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
