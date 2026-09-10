package tech.valerochkagym.controller.data

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.health.*
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1")
class HealthLedgerController(
  private val ledger: HealthLedgerService,
  private val disclosure: HealthAiDisclosureService,
  private val raw: HealthRawBodyReader,
) {
  private fun parseLimit(value: String): Int =
    value.toIntOrNull() ?: tech.valerochkagym.controller.advice.bad("Некорректный размер страницы")

  private fun response(bytes: ByteArray) =
    ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes)

  @PostMapping("/health-ledger/operations")
  fun operation(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    response(ledger.operation(identity, raw.raw(request)))

  @GetMapping("/health-ledger/snapshot")
  fun snapshot(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam(defaultValue = "500") limit: String,
    @RequestParam(required = false) pageToken: String?,
  ) = response(ledger.page(identity, "snapshot", null, pageToken, parseLimit(limit)))

  @GetMapping("/health-ledger/changes")
  fun changes(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam(required = false) after: String?,
    @RequestParam(defaultValue = "500") limit: String,
    @RequestParam(required = false) pageToken: String?,
  ) =
    response(
      ledger.page(
        identity,
        "changes",
        after ?: tech.valerochkagym.controller.advice.bad("Укажите курсор after"),
        pageToken,
        parseLimit(limit),
      )
    )

  @GetMapping("/health-ai-disclosure")
  fun read(@AuthenticationPrincipal identity: Identity) = disclosure.read(identity)

  @PostMapping("/health-ai-disclosure")
  fun consent(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    response(disclosure.mutate(identity, raw.raw(request, 4096)))
}
