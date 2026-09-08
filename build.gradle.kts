plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.spring") version "2.4.10"
  kotlin("plugin.jpa") version "2.4.10"
  id("org.springframework.boot") version "4.1.1"
  id("com.diffplug.spotless") version "8.10.2"
  jacoco
}

group = "tech.valerochkagym"

version = "0.1.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-liquibase")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.security:spring-security-oauth2-jose")
  implementation("org.bouncycastle:bcprov-jdk18on:1.83")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
  implementation("tools.jackson.module:jackson-module-kotlin")
  implementation(kotlin("reflect"))
  runtimeOnly("org.postgresql:postgresql")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.testcontainers:testcontainers-postgresql:2.0.3")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
  reports {
    xml.required = true
    html.required = true
  }
}

tasks.bootJar { archiveFileName = "app.jar" }

spotless {
  kotlin { ktfmt("0.61").googleStyle() }
  kotlinGradle { ktfmt("0.61").googleStyle() }
}
