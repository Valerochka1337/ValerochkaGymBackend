package tech.valerochkagym.data

import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.auth.Identity
import tech.valerochkagym.web.*

@RestController
@RequestMapping("/v1")
class DataController(private val sync: SyncService) {
  @GetMapping("/sync")
  fun snapshot(@AuthenticationPrincipal identity: Identity) = sync.snapshot(identity.userId)

  @PostMapping("/sync")
  fun push(@AuthenticationPrincipal identity: Identity, @RequestBody request: PushRequest) =
    sync.push(identity.userId, request)

  @GetMapping("/sync/changes")
  fun changes(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "200") limit: Int,
  ) = sync.changes(identity.userId, after, cursor, limit)

  @GetMapping("/records/{kind}")
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable kind: String,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "100") limit: Int,
  ): List<Record> {
    if (kind !in RecordValidator.kinds || offset < 0 || limit !in 1..1000)
      bad("Некорректная пагинация")
    return sync
      .snapshot(identity.userId)
      .records
      .filter { it.kind == kind && !it.deleted }
      .drop(offset)
      .take(limit)
  }

  @GetMapping("/records/{kind}/{id}")
  fun record(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable kind: String,
    @PathVariable id: UUID,
  ): Record =
    sync.snapshot(identity.userId).records.firstOrNull {
      it.kind == kind && it.id == id && !it.deleted
    } ?: throw ApiException(404, "not_found", "Объект не найден")
}
