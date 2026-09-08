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
