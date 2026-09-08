package tech.valerochkagym.service.model

import java.util.UUID

data class Identity(val userId: UUID, val sessionId: UUID, val email: String)
