package tech.valerochkagym.service.data

import java.nio.charset.StandardCharsets
import java.util.UUID

object ProfileIdentity {
  fun syncId(owner: String): UUID =
    UUID.nameUUIDFromBytes(
      ("ValerochkaGym.profile.v1:" + owner).toByteArray(StandardCharsets.UTF_8)
    )
}
