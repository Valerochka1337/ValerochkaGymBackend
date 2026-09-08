package tech.valerochkagym.controller.model

import java.time.Instant
import java.util.UUID

data class Tokens(
  val userId: UUID,
  val email: String,
  val accessToken: String,
  val refreshToken: String,
  val expiresIn: Int = 900,
)

data class SessionInfo(
  val id: UUID,
  val deviceName: String,
  val createdAt: Instant,
  val current: Boolean,
)
