package tech.valerochkagym.controller.data

import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.PushRequest
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.data.SyncService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1")
class DataController(private val sync: SyncService) {
  @GetMapping("/sync")
  fun snapshot(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
  ) = sync.snapshot(identity.userId, version)

  @PostMapping("/sync")
  fun push(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody request: PushRequest,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
  ) = sync.push(identity.userId, request, version)

  @GetMapping("/sync/changes")
  fun changes(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "200") limit: Int,
  ) = sync.changes(identity.userId, after, cursor, limit, version)

  @GetMapping("/records/{kind}")
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @PathVariable kind: String,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "100") limit: Int,
  ): List<Record> {
    if (kind !in RecordValidator.kinds || offset < 0 || limit !in 1..1000)
      bad("Некорректная пагинация")
    return sync
      .snapshot(identity.userId, version)
      .records
      .filter { it.kind == kind && !it.deleted }
      .drop(offset)
      .take(limit)
  }

  @GetMapping("/records/{kind}/{id}")
  fun record(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @PathVariable kind: String,
    @PathVariable id: UUID,
  ): Record =
    sync.snapshot(identity.userId, version).records.firstOrNull {
      it.kind == kind && it.id == id && !it.deleted
    } ?: throw ApiException(404, "not_found", "Объект не найден")
}
