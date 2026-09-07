# ValerochkaGym Backend

Kotlin / Spring Boot 4.1.1 / PostgreSQL 17 / Liquibase. API для Android:
Google и email/пароль, сессии устройств, упражнения, залы, программы, тренировки,
замеры и расписание. Sheets не используется. Excel-экспорт — после MVP.

## Локальный запуск

Требуются JDK 21 и Docker с Compose.

```bash
docker compose up -d
cp .env.example .env
set -a
source .env
set +a
./gradlew bootRun
```

API: `http://localhost:8080/v1`. Тестовая почта Mailpit: `http://localhost:8025`.
Коды подтверждения действуют 10 минут; пароль — от 12 до 128 символов.

```bash
./gradlew check bootJar
docker build -t valerochkagym-backend:mvp .
```

`check` включает форматирование и HTTP-тесты с PostgreSQL/Testcontainers и Liquibase.
Google JWT проверяются локальными RSA-ключами; письма перехватывает тестовый адаптер.
Проверяется также реальный JSON-снимок, сформированный Android-кодом.
На локальной Colima несовместимый Ryuk нужно отключить (JUnit закрывает PostgreSQL):

```bash
DOCKER_HOST=unix:///Users/valerochka1337/.colima/default/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew check
```

В GitHub CI используется стандартный Docker без этого исключения.

## API и безопасность

[OpenAPI](docs/openapi.json), [протокол данных](docs/api.md).
Актуальная OpenAPI-схема — `/v3/api-docs` с Bearer-токеном, Swagger UI выключен по умолчанию.

- `/v1/auth/register`, `/v1/auth/login`, `/v1/auth/verify`, `/v1/auth/verify/request`.
- `/v1/auth/password/request`, `/v1/auth/password/reset`.
- `/v1/auth/google/nonce`, `/v1/auth/google`, `/v1/auth/refresh`.
- `/v1/me`, `/v1/me/google`, `/v1/me/delete-code`, `DELETE /v1/me`.
- `/v1/sessions`, `DELETE /v1/sessions/{id}`, `/v1/logout`, `/v1/logout-all`.
- `GET/POST /v1/sync`, `/v1/sync/changes`, `/v1/records/{kind}[/{id}]`.

Случайные access-токены живут 15 минут, refresh-сессия — 30 дней от входа.
Refresh вращается; повтор использованного токена отзывает сессию. В БД — HMAC-хеши
токенов/кодов и Argon2id-хеши паролей; `TOKEN_PEPPER` хранится отдельно от БД.
Google идентифицируется по `sub`, совпадение email не объединяет аккаунты.
Связывание способов входа требует уже авторизованной сессии.

## Production и CI/CD

[Инструкция эксплуатации](docs/operations.md).

- Ubuntu 24.04, `valerochka@62.84.122.55`, `/opt/valerochkagym`.
- Существующий Nginx/Certbot → `127.0.0.1:18080` → backend → закрытый PostgreSQL.
- Readiness: `https://api.valerochkagym.tech/health`.
- Backend: 768 MB, PostgreSQL: 384 MB; постоянный Docker volume.
- Backup ежедневно, локальное хранение 14 дней; off-host копия настраивается отдельно.
- SMTP пока не предоставлен: в production `MAIL_ENABLED=false`. Google Web client ID
  настроен; настоящий Google-вход проверяется владельцем со своим аккаунтом.

[Workflow](.github/workflows/backend.yml): PR → проверки и Docker build; main → проверки →
GHCR → deploy по digest → health-check. При неудаче возвращается предыдущий совместимый
образ; миграции БД автоматически не откатываются. Документация проходит лёгкий статус
без JVM и тестов. Для CD нужны `DEPLOY_ENABLED=true` и environment `production` с
`DEPLOY_SSH_KEY`/`DEPLOY_KNOWN_HOSTS`. Временный GITHUB_TOKEN передаётся по SSH stdin
для GHCR; Docker credentials удаляются после deploy.

Ключ Actions не установлен: автоматический review потребовал отдельного согласия на
постоянный привилегированный доступ. Ветки пока не опубликованы, реального запуска CI нет.

## Android и границы MVP

Ветка `feat/backend-integration`, рабочая копия `/private/tmp/ValerochkaGym-backend-integration`.
Версия 1.3.18 (26), Room v15. Room и очередь сохраняют работу без интернета; точный пакет
записывается в outbox перед HTTP, подтверждённые версии — в baseline. Токены зашифрованы
Android Keystore и лежат в `noBackupFilesDir`. Локальная база привязана к одному аккаунту;
для смены нужно экспортировать историю и явно очистить приложение.
Remote sync отложен до окончания активной тренировки, чтобы сохранить ID подходов,
используемые foreground-сервисом. Calendar и AI остаются в Android.

PostgreSQL хранит отдельные JSONB-агрегаты с UUID и revision; принадлежность, ссылки,
поля и предметные правила проверяются сервером в транзакции. Начальный клиент читает
полный снимок; API также предоставляет постраничные изменения. Лимиты: 1000 изменений
и 10 MB на запрос, 20000 записей с tombstone и 16 MB содержимого на аккаунт.
Tombstone и идемпотентные операции хранятся до удаления аккаунта.
Нет административной панели, тренерских ролей, MFA, высокой доступности, Sheets,
серверного Calendar/AI и Excel-экспорта. Один VPS допускает краткий простой при обновлении.
