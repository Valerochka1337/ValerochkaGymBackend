package tech.valerochkagym.controller.admin

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.ai.AiSettingsEdit
import tech.valerochkagym.service.ai.AiSettingsService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/admin/api/ai-settings")
class AdminAiController(private val settings: AiSettingsService) {
  @GetMapping fun get() = settings.get()

  @PutMapping
  fun save(@RequestBody body: AiSettingsEdit, @AuthenticationPrincipal actor: Identity) =
    settings.save(body, actor.userId)
}
