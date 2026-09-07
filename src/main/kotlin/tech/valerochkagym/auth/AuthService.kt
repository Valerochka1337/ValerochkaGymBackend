package tech.valerochkagym.auth

import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.web.*

data class Identity(val userId: UUID, val sessionId: UUID, val email: String)

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

@Service
class AuthService(
  private val db: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val crypto: Crypto,
  private val mailer: Mailer,
  private val clock: Clock,
) {
  private val passwords = Argon2PasswordEncoder(16, 32, 1, 19456, 2)
  private val dummyHash = passwords.encode(crypto.token())

  private fun now() = clock.instant()

  fun email(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).also {
      if (it.length > 254 || !it.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
        bad("Некорректный email")
    }

  private fun password(raw: String) {
    if (raw.length !in 12..128) bad("Пароль должен содержать от 12 до 128 символов")
  }

  fun register(rawEmail: String, password: String) {
    val email = email(rawEmail)
    password(password)
    val hash = passwords.encode(password)
    tx.executeWithoutResult {
      db.update(
        "INSERT INTO users(id,email,password_hash) VALUES (?,?,?) ON CONFLICT(email) DO NOTHING",
        UUID.randomUUID(),
        email,
        hash,
      )
      val user = db.queryForMap("SELECT * FROM users WHERE email=? FOR UPDATE", email)
      if (user["email_verified"] == false) challenge(email, "verify")
    }
  }

  fun requestCode(rawEmail: String, purpose: String) {
    val email = email(rawEmail)
    tx.executeWithoutResult {
      val user =
        db
          .queryForList("SELECT id,email_verified FROM users WHERE email=? FOR UPDATE", email)
          .firstOrNull()
      if (user != null && (purpose != "verify" || user["email_verified"] == false))
        challenge(email, purpose)
    }
  }

  private fun challenge(email: String, purpose: String) {
    val code = crypto.code()
    db.update(
      """INSERT INTO email_challenges(email,purpose,code_hash,expires_at) VALUES (?,?,?,?)
            ON CONFLICT(email,purpose) DO UPDATE SET code_hash=EXCLUDED.code_hash,expires_at=EXCLUDED.expires_at,attempts=0""",
      email,
      purpose,
      crypto.hash("$email:$purpose:$code"),
      Timestamp.from(now().plusSeconds(600)),
    )
    mailer.sendCode(email, purpose, code)
  }

  // A failed attempt is committed before returning an error, preventing unlimited code guesses.
  private fun consume(email: String, purpose: String, code: String): Boolean {
    val challenge =
      db
        .queryForList(
          "SELECT * FROM email_challenges WHERE email=? AND purpose=? FOR UPDATE",
          email,
          purpose,
        )
        .firstOrNull() ?: return false
    db.update(
      "UPDATE email_challenges SET attempts=attempts+1 WHERE email=? AND purpose=?",
      email,
      purpose,
    )
    if (
      (challenge["attempts"] as Int) >= 5 ||
        !(challenge["expires_at"] as Timestamp).toInstant().isAfter(now()) ||
        challenge["code_hash"] != crypto.hash("$email:$purpose:$code")
    )
      return false
    db.update("DELETE FROM email_challenges WHERE email=? AND purpose=?", email, purpose)
    return true
  }

  fun verify(rawEmail: String, code: String) {
    val email = email(rawEmail)
    val ok =
      tx.execute {
        if (!consume(email, "verify", code)) false
        else {
          db.update("UPDATE users SET email_verified=true WHERE email=?", email)
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  fun reset(rawEmail: String, code: String, newPassword: String) {
    val email = email(rawEmail)
    password(newPassword)
    val hash = passwords.encode(newPassword)
    val ok =
      tx.execute {
        val user =
          db.queryForList("SELECT id FROM users WHERE email=? FOR UPDATE", email).firstOrNull()
        if (user == null || !consume(email, "reset", code)) false
        else {
          db.update(
            "UPDATE users SET password_hash=?,email_verified=true WHERE email=?",
            hash,
            email,
          )
          db.update(
            "UPDATE sessions SET revoked_at=? WHERE user_id=?",
            Timestamp.from(now()),
            user["id"],
          )
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  fun login(rawEmail: String, password: String, device: String): Tokens {
    val email = email(rawEmail)
    if (password.length > 128) unauthorized()
    return tx.execute {
      val user =
        db.queryForList("SELECT * FROM users WHERE email=? FOR UPDATE", email).firstOrNull()
      val matched = passwords.matches(password, user?.get("password_hash") as String? ?: dummyHash)
      if (!matched || user == null) unauthorized()
      if (user["email_verified"] != true)
        throw ApiException(403, "email_unverified", "Подтвердите email кодом из письма")
      issue(user["id"] as UUID, email, device)
    }!!
  }

  fun loginAdmin(username: String, password: String): Tokens {
    if (username.length !in 1..64 || password.length !in 12..128)
      throw ApiException(401, "invalid_credentials", "Неверный логин или пароль")
    return tx.execute {
      val user =
        db
          .queryForList(
            """SELECT u.id,u.email,u.email_verified,u.is_admin,c.password_hash
          FROM admin_credentials c JOIN users u ON u.id=c.user_id
          WHERE c.username=? FOR UPDATE OF u,c""",
            username,
          )
          .firstOrNull()
      val matched = passwords.matches(password, user?.get("password_hash") as String? ?: dummyHash)
      if (!matched || user == null || user["is_admin"] != true || user["email_verified"] != true)
        throw ApiException(401, "invalid_credentials", "Неверный логин или пароль")
      issue(user["id"] as UUID, user["email"] as String, "Админка · браузер")
    }
  }

  fun google(subject: String, rawEmail: String, device: String, link: Identity? = null): Tokens {
    val email = email(rawEmail)
    return tx.execute {
      // Serialize identity creation/linking without using an unverified email as an identity.
      db.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE")
      val bySubject =
        db.queryForList("SELECT * FROM users WHERE google_subject=?", subject).firstOrNull()
      if (link != null) {
        if (bySubject != null && bySubject["id"] != link.userId)
          throw ApiException(409, "identity_in_use", "Google уже связан с другим аккаунтом")
        val linked =
          db.update(
            "UPDATE users SET google_subject=? WHERE id=? AND (google_subject IS NULL OR google_subject=?)",
            subject,
            link.userId,
            subject,
          )
        if (linked != 1)
          throw ApiException(
            409,
            "identity_in_use",
            "К аккаунту уже подключён другой Google-профиль",
          )
        return@execute issue(link.userId, link.email, device)
      }
      if (bySubject != null)
        return@execute issue(bySubject["id"] as UUID, bySubject["email"] as String, device)
      if (db.queryForList("SELECT id FROM users WHERE email=?", email).isNotEmpty())
        throw ApiException(
          409,
          "link_required",
          "Войдите с паролем и подключите Google в настройках аккаунта",
        )
      val id = UUID.randomUUID()
      db.update(
        "INSERT INTO users(id,email,email_verified,google_subject) VALUES (?,?,true,?)",
        id,
        email,
        subject,
      )
      issue(id, email, device)
    }!!
  }

  private fun issue(
    user: UUID,
    email: String,
    device: String,
    session: UUID = UUID.randomUUID(),
    existing: Boolean = false,
  ): Tokens {
    if (device.length !in 1..100) bad("Некорректное название устройства")
    val access = crypto.token()
    val refresh = crypto.token()
    if (!existing)
      db.update(
        "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,?,?,?,?)",
        session,
        user,
        device,
        crypto.hash(access),
        Timestamp.from(now().plusSeconds(900)),
        Timestamp.from(now().plusSeconds(2592000)),
      )
    else
      db.update(
        "UPDATE sessions SET access_hash=?,access_expires_at=? WHERE id=?",
        crypto.hash(access),
        Timestamp.from(now().plusSeconds(900)),
        session,
      )
    db.update(
      "INSERT INTO refresh_tokens(token_hash,session_id) VALUES (?,?)",
      crypto.hash(refresh),
      session,
    )
    return Tokens(user, email, access, refresh)
  }

  fun refresh(token: String): Tokens {
    if (token.length > 100) unauthorized()
    val result =
      tx.execute {
        val row =
          db
            .queryForList(
              """SELECT s.*,r.used_at,u.email FROM refresh_tokens r JOIN sessions s ON s.id=r.session_id
                JOIN users u ON u.id=s.user_id WHERE r.token_hash=? FOR UPDATE OF s,r""",
              crypto.hash(token),
            )
            .firstOrNull() ?: return@execute null
        if (
          row["revoked_at"] != null ||
            !(row["refresh_expires_at"] as Timestamp).toInstant().isAfter(now())
        )
          return@execute null
        if (row["used_at"] != null) {
          db.update("UPDATE sessions SET revoked_at=? WHERE id=?", Timestamp.from(now()), row["id"])
          return@execute null
        }
        db.update(
          "UPDATE refresh_tokens SET used_at=? WHERE token_hash=?",
          Timestamp.from(now()),
          crypto.hash(token),
        )
        issue(
          row["user_id"] as UUID,
          row["email"] as String,
          row["device_name"] as String,
          row["id"] as UUID,
          true,
        )
      }
    return result ?: unauthorized()
  }

  fun authenticate(token: String): Identity? {
    if (token.length !in 40..100) return null
    return db
      .queryForList(
        """SELECT s.id,s.user_id,u.email FROM sessions s JOIN users u ON u.id=s.user_id
            WHERE access_hash=? AND revoked_at IS NULL AND access_expires_at>? AND refresh_expires_at>?""",
        crypto.hash(token),
        Timestamp.from(now()),
        Timestamp.from(now()),
      )
      .firstOrNull()
      ?.let { Identity(it["user_id"] as UUID, it["id"] as UUID, it["email"] as String) }
  }

  fun sessions(identity: Identity): List<SessionInfo> =
    db
      .queryForList(
        "SELECT id,device_name,created_at FROM sessions WHERE user_id=? AND revoked_at IS NULL AND refresh_expires_at>? ORDER BY created_at DESC",
        identity.userId,
        Timestamp.from(now()),
      )
      .map {
        SessionInfo(
          it["id"] as UUID,
          it["device_name"] as String,
          (it["created_at"] as Timestamp).toInstant(),
          it["id"] == identity.sessionId,
        )
      }

  fun logout(identity: Identity, session: UUID = identity.sessionId) {
    db.update(
      "UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=?",
      Timestamp.from(now()),
      session,
      identity.userId,
    )
  }

  fun logoutAll(identity: Identity) {
    db.update(
      "UPDATE sessions SET revoked_at=? WHERE user_id=?",
      Timestamp.from(now()),
      identity.userId,
    )
  }

  fun delete(identity: Identity, code: String) {
    val ok =
      tx.execute {
        if (!consume(identity.email, "delete", code)) false
        else {
          db.update("DELETE FROM email_challenges WHERE email=?", identity.email)
          db.update("DELETE FROM users WHERE id=?", identity.userId)
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  @Scheduled(fixedDelay = 3600000)
  fun cleanup() {
    db.update("DELETE FROM sessions WHERE refresh_expires_at < ?", Timestamp.from(now()))
    db.update("DELETE FROM email_challenges WHERE expires_at < ?", Timestamp.from(now()))
    db.update("DELETE FROM google_nonces WHERE expires_at < ?", Timestamp.from(now()))
    db.update(
      "DELETE FROM rate_limits WHERE window_start < ?",
      Timestamp.from(now().minusSeconds(3600)),
    )
  }
}
