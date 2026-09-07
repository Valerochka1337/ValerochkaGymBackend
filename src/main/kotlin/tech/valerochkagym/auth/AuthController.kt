package tech.valerochkagym.auth

import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.web.RateLimiter

data class Credentials(val email: String, val password: String, val deviceName: String = "Android")

data class EmailRequest(val email: String)

data class CodeRequest(val email: String, val code: String)

data class ResetRequest(val email: String, val code: String, val password: String)

data class RefreshRequest(val refreshToken: String)

data class GoogleRequest(val idToken: String, val nonce: String, val deviceName: String = "Android")

data class DeleteRequest(val code: String)

@RestController
@RequestMapping("/v1")
class AuthController(
  private val auth: AuthService,
  private val google: GoogleVerifier,
  private val nonces: GoogleIdentity,
  private val limits: RateLimiter,
) {
  private fun limited(email: String) {
    limits.check("email:${auth.email(email)}", 8)
  }

  @PostMapping("/auth/register")
  fun register(@RequestBody body: Credentials): Map<String, String> {
    limited(body.email)
    auth.register(body.email, body.password)
    return mapOf("status" to "check_email")
  }

  @PostMapping("/auth/login")
  fun login(@RequestBody body: Credentials): Tokens {
    limited(body.email)
    return auth.login(body.email, body.password, body.deviceName)
  }

  @PostMapping("/auth/verify/request")
  fun resend(@RequestBody body: EmailRequest): Map<String, String> {
    limited(body.email)
    auth.requestCode(body.email, "verify")
    return mapOf("status" to "check_email")
  }

  @PostMapping("/auth/verify")
  fun verify(@RequestBody body: CodeRequest): Map<String, String> {
    limited(body.email)
    auth.verify(body.email, body.code)
    return mapOf("status" to "verified")
  }

  @PostMapping("/auth/password/request")
  fun requestReset(@RequestBody body: EmailRequest): Map<String, String> {
    limited(body.email)
    auth.requestCode(body.email, "reset")
    return mapOf("status" to "check_email")
  }

  @PostMapping("/auth/password/reset")
  fun reset(@RequestBody body: ResetRequest): Map<String, String> {
    limited(body.email)
    auth.reset(body.email, body.code, body.password)
    return mapOf("status" to "reset")
  }

  @PostMapping("/auth/refresh")
  fun refresh(@RequestBody body: RefreshRequest) = auth.refresh(body.refreshToken)

  @PostMapping("/auth/google/nonce") fun nonce() = mapOf("nonce" to nonces.nonce())

  @PostMapping("/auth/google")
  fun google(@RequestBody body: GoogleRequest): Tokens {
    val account = google.verify(body.idToken, body.nonce)
    return auth.google(account.subject, account.email, body.deviceName)
  }

  @PostMapping("/me/google")
  fun link(@AuthenticationPrincipal identity: Identity, @RequestBody body: GoogleRequest): Tokens {
    val account = google.verify(body.idToken, body.nonce)
    return auth.google(account.subject, account.email, body.deviceName, identity)
  }

  @GetMapping("/me")
  fun me(@AuthenticationPrincipal identity: Identity) =
    mapOf("userId" to identity.userId.toString(), "email" to identity.email)

  @GetMapping("/sessions")
  fun sessions(@AuthenticationPrincipal identity: Identity) = auth.sessions(identity)

  @DeleteMapping("/sessions/{id}")
  fun revoke(@AuthenticationPrincipal identity: Identity, @PathVariable id: UUID) =
    auth.logout(identity, id)

  @PostMapping("/logout")
  fun logout(@AuthenticationPrincipal identity: Identity) = auth.logout(identity)

  @PostMapping("/logout-all")
  fun logoutAll(@AuthenticationPrincipal identity: Identity) = auth.logoutAll(identity)

  @PostMapping("/me/delete-code")
  fun deleteCode(@AuthenticationPrincipal identity: Identity): Map<String, String> {
    limited(identity.email)
    auth.requestCode(identity.email, "delete")
    return mapOf("status" to "check_email")
  }

  @DeleteMapping("/me")
  fun delete(@AuthenticationPrincipal identity: Identity, @RequestBody body: DeleteRequest) {
    limited(identity.email)
    auth.delete(identity, body.code)
  }
}
