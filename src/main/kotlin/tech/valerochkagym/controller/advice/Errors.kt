package tech.valerochkagym.controller.advice

import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: Int, val code: String, override val message: String) :
  RuntimeException(message)

data class ApiError(val code: String, val message: String)

fun bad(message: String): Nothing = throw ApiException(400, "invalid_request", message)

fun unauthorized(): Nothing = throw ApiException(401, "unauthorized", "Войдите в аккаунт заново")

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ApiException::class)
  fun api(e: ApiException, request: jakarta.servlet.http.HttpServletRequest) =
    ResponseEntity.status(e.status)
      .headers {
        if (request.requestURI == "/v1/ai/coach-turn/stream")
          it.contentType = org.springframework.http.MediaType.APPLICATION_JSON
      }
      .body(ApiError(e.code, e.message))

  @ExceptionHandler(
    MethodArgumentNotValidException::class,
    HttpMessageNotReadableException::class,
    IllegalArgumentException::class,
  )
  fun invalid(
    e: Exception,
    request: jakarta.servlet.http.HttpServletRequest,
  ): ResponseEntity<ApiError> {
    // A bounded request stream may be wrapped by Jackson's conversion exception.
    generateSequence<Throwable>(e) { it.cause }
      .filterIsInstance<ApiException>()
      .firstOrNull()
      ?.let {
        return api(it, request)
      }
    return ResponseEntity.badRequest()
      .headers {
        if (request.requestURI == "/v1/ai/coach-turn/stream")
          it.contentType = org.springframework.http.MediaType.APPLICATION_JSON
      }
      .body(ApiError("invalid_request", "Проверьте формат и значения полей"))
  }
}
