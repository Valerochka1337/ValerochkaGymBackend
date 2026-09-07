# Эксплуатация

## Размещение

Сервер: Ubuntu 24.04, `valerochka@62.84.122.55`, 2 GB RAM. Каталог `/opt/valerochkagym`.
Nginx/Certbot существовали до backend; отдельный серверный блок API сохранён и обновлён.
Конфигурация прежнего server block сохраняется в `/opt/valerochkagym/nginx-before-backend.*`.
Соседний контейнер MTProto не изменяется.

```bash
ssh valerochka@62.84.122.55
cd /opt/valerochkagym
sudo docker compose --env-file .env -f compose.production.yaml ps
curl --fail https://api.valerochkagym.tech/health
sudo docker compose --env-file .env -f compose.production.yaml logs --tail=100 backend
```

Секреты создаёт bootstrap в root-only `.env` (0600): случайный пароль PostgreSQL и
TOKEN_PEPPER. Не выводить содержимое файла в логи/чат и не коммитить. Контейнер backend
не имеет root-прав, файловая система read-only, временные файлы в tmpfs; БД не публикует порт.
Приложение доступно на host loopback 18080, снаружи — только Nginx HTTPS.
Readiness проверяет БД. Наружные `/actuator/*` закрыты; `/health` проксируется к readiness.
Публичного диагностического endpoint с environment или содержимым БД нет.

Для повторного размещения на таком же подготовленном сервере скопировать `infra/` и
`scripts/`, выполнить `sudo bash scripts/bootstrap.sh`, загрузить образ и запустить Compose.
После успешного readiness — `sudo bash scripts/activate-proxy.sh`. Существующий `.env`
bootstrap не перезаписывает. Он не устанавливает Docker, Nginx или сертификаты за владельца.

## Почта

Сейчас `MAIL_ENABLED=false`: регистрация/восстановление, требующие отправки кода,
возвращают `503 mail_unavailable`. Google-вход от SMTP не зависит.

Через редактор на сервере задать в `.env`: `MAIL_ENABLED=true`, `MAIL_FROM`, `SMTP_HOST`,
`SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH=true`, `SMTP_TLS=true`.
Текущая конфигурация рассчитана на SMTP STARTTLS (обычно 587). Для implicit TLS/465
нужна отдельная настройка JavaMail; не выключать TLS ради обхода ошибки.
Затем пересоздать backend:

```bash
sudo docker compose --env-file .env -f compose.production.yaml up -d --no-deps backend
```

Проверить регистрацию, получение кода, подтверждение email и сброс пароля реальным
тестовым адресом владельца. Эти письма не отправлялись агентом; необходимы настройки SMTP.
DNS-подтверждение отправителя (SPF/DKIM и прочее) выполняется по требованиям выбранного сервиса.

## Backup и восстановление

```bash
sudo /opt/valerochkagym/backup.sh
sudo /opt/valerochkagym/verify-restore.sh backups/gym-YYYYMMDDTHHMMSS.dump
sudo systemctl list-timers valerochkagym-backup.timer
sudo journalctl -u valerochkagym-backup.service --since yesterday
```

Ежедневно около 03:30 UTC создаётся `pg_dump -Fc`, сначала `.partial`, затем атомарный rename.
Локальные файлы хранятся 14 дней. Проверка restore создаёт отдельную временную БД,
восстанавливает все объекты с `--exit-on-error`, проверяет таблицы и удаляет тестовую БД.
Production-БД этим скриптом не перезаписывается.

Для внешней копии установить/настроить rclone и задать `GYM_BACKUP_REMOTE` в root-only
`/opt/valerochkagym/backup.env`. Пример: `GYM_BACKUP_REMOTE=s3:gym-backups/production`.
Настроить lifecycle внешнего хранилища отдельно. Пока off-host destination не предоставлен,
локальный backup не защищает от потери самого VPS. Цель RPO — до 24 часов после настройки
внешних ежедневных копий. Время полного восстановления сервера зависит от провайдера и данных.

Для аварийного восстановления: остановить backend, сохранить текущую БД отдельно,
восстановить выбранный backup в новую БД, проверить его и переключить `DATABASE_URL`.
Использовать совместимый со схемой образ. TOKEN_PEPPER также нужен для действующих
сессий; его восстановить из защищённой копии конфигурации либо сменить и потребовать новый вход.
Не запускать `pg_restore --clean` непосредственно на живой production-БД.

## Обновление и откат

CI собирает один JAR после тестов и публикует образ по SHA коммита. Deploy использует
`ghcr.io/valerochka1337/valerochkagymbackend@sha256:...`, а не изменяемый `latest`.
`deploy.sh` сериализует обновления через flock, скачивает образ, делает backup перед
миграциями, запускает Compose с ожиданием health и проверяет HTTPS. При ошибке возвращает
предыдущий образ. Старый image digest также сохраняется в `previous-image`.

Миграции Liquibase только вперёд и обратно совместимые: сначала добавить новое поле,
обновить клиентов/код и лишь в отдельном обслуживании удалить старое. Автоматический откат
приложения не отменяет DDL и не восстанавливает данные. Разрушительные миграции не должны
попадать в обычный pipeline без отдельного плана backup/restore.

Первый запуск сделан загруженным вручную образом `valerochkagym-backend:mvp` из локально
проверенного JAR. После включения GitHub CD следующие запуски будут использовать GHCR digest.

## Включение GitHub CI/CD

1. Опубликовать и согласовать обе feature-ветки. В backend защитить main обязательным
   статусом `Backend checks`; в Android использовать существующий Android CI.
2. После отдельного разрешения создать выделенный SSH-ключ Actions и добавить его к
   серверному пользователю с ограничениями `restrict`. Этот пользователь имеет sudo:
   ключ нужно считать привилегированным production-секретом и держать только в environment.
3. Создать environment `production`, secrets `DEPLOY_SSH_KEY` и `DEPLOY_KNOWN_HOSTS`
   (проверенные host keys). Установить repository variable `DEPLOY_ENABLED=true`.
4. Main pipeline: check → publish GHCR → SCP конфигурации в incoming → SSH deploy helper.
   Временный GITHUB_TOKEN с packages:read подаётся на stdin SSH; одноразовый Docker config
   удаляется после deploy. Не требуется постоянный GHCR PAT на сервере.
5. Проверить первый реальный workflow, его тестовые отчёты и `/health` после deploy.

На текущем этапе шаги 2–3 отклонены автоматическим approval review и не выполнены:
проверка потребовала явного согласия на постоянный SSH-доступ с sudo. Ключ не создан.
Push ещё не выполнялся. Следовательно, нельзя считать реальный CI/CD прогон подтверждённым.

## Наблюдение

Контейнерные логи ограничены 3 × 10 MB на контейнер. Таймер Certbot продлевает существующий
сертификат. Health можно проверять любым внешним uptime-сервисом; сервис и получатель
уведомлений пока не выбраны. Ошибки backup видны в systemd journal. Полноценный внешний
мониторинг и off-host backup требуют предоставления соответствующих настроек.
