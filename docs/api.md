# Протокол API

Полный список endpoints и моделей: [OpenAPI](openapi.json).
Кроме `/v1/auth/*`, запросы требуют `Authorization: Bearer <accessToken>`.
Владелец определяется только сессией. Ошибки: `{code,message}`.

## Запись и повтор запросов

`POST /v1/sync`: `{operationId,changes:[{kind,id,baseRevision,deleted,payload}]}`.
`operationId` и `id` — UUID, `baseRevision=0` для новой записи; иначе последняя известная
серверная версия объекта. `deleted=true` требует `payload=null`.
Ответ `{revision}` присваивается всем записям пакета. Пакет до 1000 изменений атомарен.

Повтор точного запроса с тем же operationId возвращает прежний результат. Повтор operationId
с другим содержимым — 409. Устаревшая baseRevision — 409 `revision_conflict`, без частичных
изменений. Клиент сохраняет локальную правку, читает актуальные версии и разрешает конфликт.
После изменения payload используется новый operationId. Часы устройства и Android updatedAt
не выбирают победителя конфликта.

## Чтение

- `GET /v1/sync` → `{revision,records:[{kind,id,revision,deleted,payload}]}`.
- `GET /v1/sync/changes?after=0&limit=200` → `{revision,records,nextCursor}`.
- `GET /v1/records/{kind}?offset=0&limit=100` → живые записи.
- `GET /v1/records/{kind}/{id}` → живая запись либо 404.

Пока nextCursor непустой, передавать его в следующий запрос с **тем же after**.
После последней страницы сохранить revision последнего ответа как новый after.
Курсор `(revision,kind,id)` не пропускает записи одного пакета с одинаковой revision.
При параллельном обновлении объект может встретиться повторно в более новой версии.
Это выдача последних состояний, не история каждой промежуточной правки. Tombstone
не очищаются в MVP. Чтение и запись сериализуются блокировкой строки владельца.

## Агрегаты payload

| kind | Поля |
|---|---|
| exercise | name, muscleGroup, type, isCustom, updatedAt, needsMuscleMapReview, equipmentRequirementState, muscles, equipmentIds |
| gym | name, updatedAt, inventoryConfigured, exerciseIds, equipmentIds |
| routine | name, note, updatedAt, gymIds, exercises |
| workout | name, note, routineId, startedAt, finishedAt, gymIds, exercises |
| measurement | measuredAt и nullable-показатели замеров Android |
| schedule | routineId, dateTimeMillis, calendarEventId |

Все ссылки — UUID, время — Unix epoch в миллисекундах. routineId и finishedAt у тренировки
nullable. equipmentRequirementState — KNOWN или UNKNOWN; KNOWN с пустым списком явно
означает отсутствие требований. muscles — `{muscle,contribution}`, contribution 100/50/0.

`routine.exercises`: `{exerciseId,position,restSeconds,plannedSets}`.
`plannedSets`: nullable `{weightKg,reps,durationSec,speedKmh,inclinePct}`.
`workout.exercises`: `{sectionId,exerciseId,position,sets}`.
`sets`: поля plannedSets плюс `{setIndex,isCompleted,completedAt}`.
Повторяющееся упражнение в разных sectionId не объединяется; sectionId уникален в аккаунте.

[Android fixture](../src/test/resources/android-snapshot.json) содержит полный пример
тренировки и nullable-замера. Сервер отклоняет неизвестные поля, нечисловые/бесконечные
значения и некорректные ссылки. Все связанные объекты должны существовать в итоговом
состоянии одного аккаунта; их можно создать одним пакетом. Для программ и активных тренировок
проверяется оснащение выбранных залов; завершённая история от текущего оснащения не зависит.

## Первичный перенос

1. Сохранить SQLite-копию. Нужные записи только из Sheets вернуть в Room старой версией
   приложения до обновления: новый backend к Google Sheets не обращается.
2. Войти, подтвердив сохранение локальных тренировок в этом аккаунте.
3. Клиент сопоставляет UUID, локальную правку, baseline и серверную версию.
4. Непересекающиеся изменения объединяются. При конфликте выбрать локальную или серверную
   версию; до выбора локальные данные сохраняются и могут быть экспортированы.
5. Проверить статус синхронизации и восстановление на чистой установке.

Токены не попадают в SQLite-экспорт. Excel-экспорт не входит в MVP.

## Общий каталог и протокол 2

Публичный `/v1/catalog`, ETag, архивы, `catalogRevision`, ошибки обновления клиента и
каталога описаны в [контракте перехода](catalog-transition.md). Личные sync/records
не включают перенесённые стандартные записи. Актуальная схема — `openapi.json`.

## Локальные календарные планы — capability `calendar-plans`

Возможность включается отдельно от существующего `X-Gym-Sync-Version: 2|3`.
Клиент передаёт `X-Gym-Capabilities: calendar-plans` для `GET/POST /v1/sync`,
`GET /v1/sync/changes` и обоих вариантов `GET /v1/records/*`. Заголовок допускает
список через запятую; сервер возвращает в `X-Gym-Capabilities` только пересечение
с поддержанными возможностями. Сейчас это `calendar-plans` либо пустое значение.
Неизвестные capability игнорируются. Клиент считает отсутствующий/пустой ответ
отсутствием поддержки и сохраняет неподдерживаемые локальные данные и outbox.

Без принятой capability сервер показывает только прежние шесть kinds: snapshot,
changes (фильтр **до** пагинации и построения курсора), список records и поиск по ID
одинаково скрывают календарные записи, включая tombstone. Список скрытого kind пуст,
поиск отдельной скрытой записи возвращает 404. Общая revision остаётся revision
аккаунта. POST с любым новым kind без capability возвращает 426 `capability_required`
до записи и до возврата сохранённого результата операции. Точный повтор с capability
возвращает прежнюю revision; прежний `operationId` с другим содержимым даёт 409.
Новый календарь не повышает минимальную версию аккаунта: прежние правила v2/v3,
поля legacy `schedule` и Live Coach сохраняются.

| kind | Поля payload |
|---|---|
| `calendar_plan` | `routineId: UUID`, `startsAtMillis: epoch-ms`, `timeZoneId: IANA ZoneId`, `legacyScheduleId: UUID?` |
| `calendar_rule` | `routineId: UUID`, `isoDay: 1..7`, `localTime: HH:mm`, `timeZoneId: IANA ZoneId`, `startLocalDate: YYYY-MM-DD`, `legacyRuleKey: String?` |
| `calendar_exception` | `ruleId: UUID`, `instanceKey: YYYY-MM-DDTHH:mm[ZoneId]`, `kind: CANCELLED|MOVED`, `movedAtMillis: epoch-ms|null` |

UUID новых календарных записей и ссылочные UUID — канонические строки в нижнем регистре. Неизвестные поля запрещены;
`legacyScheduleId` и `legacyRuleKey` можно опустить или передать null. Непустой ключ
legacy-правила ограничен 1024 символами. Ненулевые legacy-идентификаторы уникальны
среди живых записей соответствующего kind в аккаунте; ссылки на Google не передаются.
`routineId` ссылается на живую личную программу, `ruleId` — на живое правило того же
владельца. Все ссылки и уникальность проверяются в итоговом состоянии атомарного пакета.

`startsAtMillis` — фиксированный instant. Правило хранит локальное недельное время,
начиная с включительной `startLocalDate`; дата старта не обязана совпадать с `isoDay`.
Допустимы реальные ZoneId из базы временных зон, включая `UTC`, но не произвольные
смещения вроде `+03:00`. Новые даты ограничены `1970-01-01`…`2100-12-31` включительно:
instant плана проверяется после перевода в его зону, moved instant — в зону правила.
Дата старта и исходная дата ключа исключения имеют тот же диапазон. Legacy `schedule`
не получает эти ограничения и сохраняет прежний payload.

Ключ исключения содержит **исходные** дату, время и зону правила; дата соответствует
дню недели и не раньше старта. `CANCELLED` требует явного `movedAtMillis: null`, `MOVED`
— целого instant. UUID записи исключения вычисляется Java-совместимым
`UUID.nameUUIDFromBytes` от UTF-8 строки
`ValerochkaGym.calendar-exception:v1:<ruleId>:<instanceKey>`; таким образом одна пара
правило/ключ имеет ровно одну идентичность. DST gap/overlap не меняет исходный ключ.
Разворачивание повторений выполняет клиент: gap — первый допустимый instant после
пропуска, overlap — более раннее смещение. Сервер не создаёт внешние события.

Изменение дня недели, времени, зоны или старта правила требует нового UUID и атомарного
удаления старого правила с его исключениями. Изменение только `routineId` сохраняет
исключения. Удалённое правило нельзя восстановить с прежним UUID; для повторного создания
нужен новый UUID, даже если расписание совпадает. Удаление родителя без удаления живых ссылок отклоняется; все связанные
удаления можно отправить одним пакетом в любом порядке. Программа с живыми планами
или правилами также не удаляется. Завершённые тренировки календарь не изменяет.

Канонический межплатформенный [fixture](../src/test/resources/cal01-sync-contract.json)
проверяется HTTP-набором вместе с изоляцией владельцев, legacy-проекцией и ledger.
