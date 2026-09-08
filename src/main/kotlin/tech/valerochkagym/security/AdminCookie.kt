package tech.valerochkagym.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

const val ADMIN_COOKIE = "__Host-gym-admin"
val publicAdminApi = setOf("/admin/api/login")

fun adminCookie(request: HttpServletRequest) =
  request.cookies?.singleOrNull { it.name == ADMIN_COOKIE }?.value
