package tech.valerochkagym.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.security.AdminFilter
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.security.publicAdminApi
import tech.valerochkagym.service.admin.AdminService
import tools.jackson.databind.ObjectMapper

@Configuration
class AdminSecurity {
  @Bean
  @Order(1)
  fun adminChain(
    http: HttpSecurity,
    admin: AdminService,
    limits: RateLimiter,
    json: ObjectMapper,
    @Value("\${gym.admin-origin:https://api.valerochkagym.tech}") origin: String,
  ): SecurityFilterChain =
    http
      .securityMatcher("/admin", "/admin/**")
      // AdminFilter enforces exact Origin and a session-bound synchronizer token for JSON
      // mutations.
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests {
        it
          .requestMatchers(
            "/admin",
            "/admin/",
            "/admin/index.html",
            "/admin/admin.js",
            "/admin/admin.css",
            *publicAdminApi.toTypedArray(),
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      }
      .addFilterBefore(
        AdminFilter(admin, limits, json, origin),
        UsernamePasswordAuthenticationFilter::class.java,
      )
      .build()
}
