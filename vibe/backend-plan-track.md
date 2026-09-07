# Трекер backend MVP

Проверено 7 сентября 2026 года.

- [x] Kotlin / Spring Boot, PostgreSQL и миграции Liquibase.
- [x] Google и email/password: подтверждение адреса, сброс пароля, сессии,
  ротация refresh token, отзыв доступа и удаление аккаунта.
- [x] Изоляция данных владельцев, переносимые UUID, проверка связей,
  атомарная синхронизация, идемпотентность и конфликты ревизий.
- [x] Android-интеграция в `feat/backend-integration`: Room v15, outbox,
  защищённые токены, UI аккаунта, удаление Sheets из production.
- [x] OpenAPI и инструкции разработки и эксплуатации.
- [x] Docker Compose, HTTPS через существующий Nginx, закрытый PostgreSQL.
- [x] Backend развёрнут на `api.valerochkagym.tech`; readiness UP,
  защищённые endpoints без токена возвращают 401, Google JWKS доступен.
- [x] Ежедневный локальный backup и проверка восстановления в отдельную БД.
- [x] Подготовлены GitHub Actions: тесты, сборка образа, публикация GHCR,
  deploy с проверкой здоровья и откатом образа.
- [x] Backend: `check` — 19 интеграционных тестов, 0 ошибок; `bootJar` собран.
- [x] Android: полный `testDebugUnitTest` — 919 тестов, 1 пропущен, 0 ошибок;
  `assembleDebug` успешен, версия 1.3.18 (26).
- [x] Опубликовать обе feature-ветки: backend PR #1, Android PR #37.
- [x] После согласия владельца настроить отдельный SSH-ключ Actions,
  production environment только для main, secrets и DEPLOY_ENABLED.
- [x] Защитить main обязательным Backend checks и изменениями через PR.
- [x] Проверить первый реальный backend GitHub CI: тесты, JAR и Docker build успешны.
- [ ] Проверить CD с GHCR и обновлением сервера после слияния backend PR.
- [ ] Получить настройки SMTP, включить почту и проверить доставку кода.
- [ ] Проверить Google-вход на устройстве с конфигурацией OAuth владельца.
- [ ] Выбрать внешнее backup-хранилище и получателя уведомлений мониторинга.

В production пока используется вручную загруженный образ из проверенного JAR.
Почта отключена до настройки SMTP. Excel-экспорт отложен за пределы MVP.
Подробности и ограничения: [эксплуатация](../docs/operations.md).
