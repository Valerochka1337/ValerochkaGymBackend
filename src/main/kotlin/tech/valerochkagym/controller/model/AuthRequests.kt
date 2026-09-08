package tech.valerochkagym.controller.model

import org.springframework.web.bind.annotation.*

data class Credentials(val email: String, val password: String, val deviceName: String = "Android")

data class EmailRequest(val email: String)

data class CodeRequest(val email: String, val code: String)

data class ResetRequest(val email: String, val code: String, val password: String)

data class RefreshRequest(val refreshToken: String)

data class GoogleRequest(val idToken: String, val nonce: String, val deviceName: String = "Android")

data class DeleteRequest(val code: String)
