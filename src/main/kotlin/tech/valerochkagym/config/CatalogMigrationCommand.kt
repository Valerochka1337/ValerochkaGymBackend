package tech.valerochkagym.config

import java.util.UUID
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tech.valerochkagym.service.catalog.CatalogMigration
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnProperty(name = ["gym.catalog.migration"])
class CatalogMigrationCommand(
  private val migration: CatalogMigration,
  private val env: Environment,
  private val json: ObjectMapper,
  private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
  override fun run(args: ApplicationArguments) {
    val mode = env.getRequiredProperty("gym.catalog.migration")
    require(mode in setOf("check", "apply")) { "migration must be check or apply" }
    fun uuid(name: String) = env.getProperty("gym.catalog.$name")?.let(UUID::fromString)
    val result =
      migration.run(
        mode == "apply",
        uuid("source"),
        uuid("actor"),
        env.getProperty("gym.catalog.reason", ""),
        env.getProperty("gym.catalog.backup-confirmed", Boolean::class.java, false),
        env.getProperty("gym.catalog.client-ready", Boolean::class.java, false),
      )
    println(json.writeValueAsString(result))
    context.close()
  }
}
