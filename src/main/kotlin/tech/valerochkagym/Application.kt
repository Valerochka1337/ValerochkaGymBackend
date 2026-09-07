package tech.valerochkagym

import java.time.Clock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class Application {
  @Bean fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
  runApplication<Application>(*args)
}
