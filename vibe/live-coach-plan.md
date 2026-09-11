# Live Coach — серверный транспорт

Выбор владельца от 2026-09-11: после перехода main на серверный AI тренер также использует backend. Локальный init-промпт, ограниченный цикл и типизированные бизнес-операции остаются в Android; provider key/URL доступны только backend. Модель тренера выбирается отдельно из серверного allowlist; локальная настройка привязана к аккаунту.

## API

- `GET /v1/ai/coach-models`: `{availability: AVAILABLE|UNCONFIGURED, defaultModel: string|null, models: string[]}`. Доступен только авторизованной сессии.
- `POST /v1/ai/coach-turn`: `{requestId: UUID, model?: string|null, messages: ChatMessage[], tools: FunctionTool[]}`.
- Ответ: `{requestId: UUID, model: string, completion: {choices: [{finish_reason, message: {role, content?, tool_calls?}}]}}`.

UUID связывает ответ с обращением, не запускает backend-операцию и не означает дедупликацию оплаты провайдера. Android никогда автоматически не повторяет POST или инструменты. Сохранённые локальные command/proposal UUID обеспечивают идемпотентность изменений.

Ровно четыре инструмента: `get_workout_state`, `find_exercises`, `get_exercise_history`, `submit_workout_changes`. Сервер не выполняет их, не читает workout/profile/health records и не ожидает SyncReady. Состояние активной тренировки приходит из локального инструмента, поскольку ещё не синхронизировано. На каждой стороне проверяются границы сообщений; бизнес-аргументы и разрешения повторно проверяет приложение.

## Ограничения

512 KiB вход, 80 сообщений, 16 связанных вызовов в истории, строковый контент без изображений, 4 определения tools. Провайдер: один нестриминговый запрос, `store=false`, `n=1`, `max_completion_tokens=4096`, `tool_choice=auto`, без `response_format`. Таймаут provider/controller 45 секунд; Android network/agent 60 секунд на обмен и 180 секунд на весь цикл. Android цикл: максимум 6 обменов/16 tools/один пакет или предложение.

Ответ провайдера ограничен 240 KiB; Android envelope — 256 KiB. Сервер отдаёт только текст и allowlisted вызовы, исключая reasoning, usage и прочую метаинформацию. Неизвестные поля входного envelope, дубли JSON и несвязанные tool results отклоняются до провайдера. Глобально два одновременных coach-обмена, не более 30 за минуту на пользователя. Окончательный async HTTP dispatch повторно проходит существующую проверку авторизации backend.

## Настройка сервера

Общие `AI_ENABLED`, `AI_PROVIDER`, `AI_BASE_URL`, `AI_API_KEY`, `AI_TEXT_MODEL`, `AI_VISION_MODEL` сохраняются. `AI_COACH_MODEL` задаёт default тренера (при отсутствии используется AI_TEXT_MODEL); `AI_COACH_MODELS` — дополнительные допустимые IDs через запятую. Всего до 20 IDs, каждый до 200 символов. Модель должна поддерживать function tools в Chat Completions. Проверка из Android использует полностью синтетическую тренировку и не изменяет пользовательские записи.

Протокол 3 и локальная миграция 26→27 поставляются вместе. Серверную ветку нужно развернуть до клиентского выпуска; код не меняет текущую production-конфигурацию и не публикуется автоматически.

Источник wire-формата: [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling).

## Реализация

T001: bounded stateless provider and server model catalogue; T002: authenticated async controller with cancellation; T003: Android integration and v3 journal; T004: provider/unit/HTTP tests and independent review. No deployment or push is part of this change.

## Потоковый API

`POST /v1/ai/coach-turn/stream` принимает то же JSON-тело и `Authorization: Bearer …`.
Существующие `/coach-turn` (`stream:false`) и `/coach-models` сохраняют формат и поведение.
Новый маршрут возвращает `Content-Type: text/event-stream`, `Cache-Control: no-store`,
`X-Accel-Buffering: no`; Nginx отключает буферизацию этого маршрута.

```text
event:text_delta
data:{"requestId":"123e4567-e89b-12d3-a456-426614174000","model":"coach","delta":"Продолжим"}

event:completed
data:{"requestId":"123e4567-e89b-12d3-a456-426614174000","model":"coach","completion":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Продолжим"}}]}}

```

Вместо `completed` при ошибке:

```text
event:error
data:{"requestId":"123e4567-e89b-12d3-a456-426614174000","code":"ai_timeout","message":"AI не ответил вовремя. Попробуйте ещё раз"}

```

`text_delta` — предварительный текст. Инструменты передаются только в полном проверенном
`completed`. При доступном соединении сервер отправляет ровно одно завершающее событие
(`completed` или `error`) и закрывает поток. EOF без `completed` означает незавершённый
ответ: клиент не выполняет инструменты. Повторов, resume и `Last-Event-ID` нет.
Комментарии `:heartbeat` отправляются каждые 10 секунд; клиент игнорирует их как данные.
Проверка актуальности сессии выполняется перед каждым событием и heartbeat. Отзыв сессии
прерывает upstream и возвращает `error` с кодом `unauthorized`.

Ошибки до открытия потока возвращаются обычным HTTP/JSON: 400 (валидация), 401
(авторизация), 413 (вход больше 512 KiB), 429 (`rate_limited`), 503
(`ai_busy` или `ai_unavailable`). После открытия ошибки передаются событием `error`:
`ai_invalid_response`, `ai_unavailable`, `ai_timeout`, `unauthorized`.

Оба маршрута используют общий лимит двух обменов и 30 запросов в минуту на пользователя.
Общий deadline — 45 секунд, включая чтение upstream. Отключение клиента обнаруживается
при записи/heartbeat; timeout, ошибка и отключение отменяют upstream и освобождают слот.
Очередь передачи ограничена одним событием; медленный клиент приостанавливает чтение
провайдера. Поток upstream ограничен 2 MiB, отдельная SSE-запись — 240 KiB; ограничения
итогового текста и инструментов прежние. UTF-8 и SSE разбираются независимо от сетевых
пакетов. Успех требует `finish_reason: stop|tool_calls` и `[DONE]`. `length`, refusal,
некорректные инструменты, повреждённый JSON и преждевременный EOF отклоняются. Reasoning,
usage и метаданные провайдера не попадают в публичный поток.

Пример ручной проверки (тело прежнего coach-turn сохранено в `request.json`):

```sh
curl --no-buffer -X POST "$BASE_URL/v1/ai/coach-turn/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @request.json
```

Android, схема БД и развёртывание в эту реализацию streaming не входят.
