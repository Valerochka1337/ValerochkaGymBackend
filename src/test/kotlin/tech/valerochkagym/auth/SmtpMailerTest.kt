package tech.valerochkagym.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.service.auth.SmtpMailer

class SmtpMailerTest {
  private class Sender : JavaMailSenderImpl() {
    val messages = mutableListOf<SimpleMailMessage>()
    var fail = false

    override fun send(message: SimpleMailMessage) {
      if (fail) throw MailAuthenticationException("secret provider diagnostic")
      messages += message
    }
  }

  @Test
  fun `disabled mail rejects delivery without contacting SMTP`() {
    val sender = Sender()
    val error =
      assertThrows(ApiException::class.java) {
        SmtpMailer(sender, "noreply@example.com", false)
          .sendCode("user@example.com", "verify", "12345678")
      }
    assertEquals(503, error.status)
    assertTrue(sender.messages.isEmpty())
  }

  @Test
  fun `verification sends the code with the configured sender`() {
    val sender = Sender()
    SmtpMailer(sender, "noreply@example.com", true)
      .sendCode("user@example.com", "verify", "12345678")
    val message = sender.messages.single()
    assertEquals("noreply@example.com", message.from)
    assertArrayEquals(arrayOf("user@example.com"), message.to)
    assertTrue(message.text!!.contains("12345678"))
    assertTrue(message.subject!!.contains("Подтверждение email"))
  }

  @Test
  fun `SMTP failure returns a retryable error without exposing provider details`() {
    val sender = Sender().apply { fail = true }
    val error =
      assertThrows(ApiException::class.java) {
        SmtpMailer(sender, "noreply@example.com", true)
          .sendCode("user@example.com", "verify", "12345678")
      }
    assertEquals(503, error.status)
    assertFalse(error.message.contains("secret"))
  }
}
