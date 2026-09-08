package tech.valerochkagym.controller.catalog

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.WebRequest
import tech.valerochkagym.controller.model.CatalogSnapshot
import tech.valerochkagym.controller.model.StandardArchive
import tech.valerochkagym.controller.model.StandardEdit
import tech.valerochkagym.service.catalog.CatalogService
import tech.valerochkagym.service.model.Identity

@RestController
class CatalogController(private val catalog: CatalogService) {
  @GetMapping("/v1/catalog")
  fun snapshot(request: WebRequest): ResponseEntity<CatalogSnapshot> {
    val snapshot = catalog.snapshot()
    val etag = "\"catalog-${snapshot.revision}-${snapshot.active}\""
    if (request.checkNotModified(etag)) return ResponseEntity.status(304).eTag(etag).build()
    return ResponseEntity.ok().eTag(etag).header("Cache-Control", "public, no-cache").body(snapshot)
  }

  @GetMapping("/admin/api/standard")
  fun list(
    @RequestParam kind: String,
    @RequestParam(defaultValue = "") q: String,
    @RequestParam(defaultValue = "false") archived: Boolean,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = catalog.list(kind, q, archived, offset, limit)

  @GetMapping("/admin/api/standard/{kind}/{id}")
  fun record(@PathVariable kind: String, @PathVariable id: String) = catalog.record(kind, id)

  @PutMapping("/admin/api/standard/{kind}/{id}")
  fun save(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable kind: String,
    @PathVariable id: String,
    @RequestBody body: StandardEdit,
  ) = catalog.save(actor, kind, id, body)

  @PostMapping("/admin/api/standard/{kind}/{id}/archive")
  fun archive(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable kind: String,
    @PathVariable id: String,
    @RequestBody body: StandardArchive,
  ) = catalog.archive(actor, kind, id, body)
}
