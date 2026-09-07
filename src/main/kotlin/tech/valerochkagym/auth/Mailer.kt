package tech.valerochkagym.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import tech.valerochkagym.web.ApiException

interface Mailer {
  fun sendCode(email: String, purpose: String, code: String)
}

@Component
class SmtpMailer(
  private val sender: JavaMailSender,
  @Value("\${gym.mail-from}") private val from: String,
  @Value("\${gym.mail-enabled}") private val enabled: Boolean,
) : Mailer {
  override fun sendCode(email: String, purpose: String, code: String) {
    if (!enabled) throw ApiException(503, "mail_unavailable", "Отправка писем пока не настроена")
    val action =
      when (purpose) {
        "verify" -> "Подтверждение email"
        "delete" -> "Удаление аккаунта"
        else -> "Восстановление пароля"
      }
    val message =
      SimpleMailMessage().apply {
        setFrom(from)
        setTo(email)
        subject = "$action — ValerochkaGym"
        text =
          "$action. Код: $code\nВведите его в приложении в течение 10 минут.\nЕсли вы не запрашивали это действие, проигнорируйте письмо."
      }
    try {
      sender.send(message)
    } catch (e: org.springframework.mail.MailException) {
      throw ApiException(503, "mail_unavailable", "Не удалось отправить письмо. Повторите позже")
    }
  }
}
