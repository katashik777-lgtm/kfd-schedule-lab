# Лабораторная работа «Ничего не падает»

Консольное приложение для работы с расписанием занятий: хранение доменной модели с защитой инвариантов, реестр с журналированием через делегирование, построчный разбор расписания («ошибки как данные») и CLI-команды фильтрации и отчёта об ошибках.

---

## Сборка и запуск

### 1. Сборка проекта и прогон тестов
**macOS / Linux:**
```bash
./gradlew build :app:installDist
```

**Windows (PowerShell):**
```powershell
.\gradlew.bat build :app:installDist
```

### 2. Запуск приложения
После сборки исполняемый скрипт создаётся в `app/build/install/schedule/bin/`. Запуск выполняется из корня проекта:

**macOS / Linux:**
```bash
./app/build/install/schedule/bin/schedule period --file samples/clean.txt --from 2026-09-28 --to 2027-05-13
./app/build/install/schedule/bin/schedule type --file samples/dirty.txt --type EXAM
./app/build/install/schedule/bin/schedule errors --file samples/dirty.txt
```

**Windows (PowerShell):**
```powershell
.\app\build\install\schedule\bin\schedule.bat period --file samples/clean.txt --from 2026-09-28 --to 2027-05-13
.\app\build\install\schedule\bin\schedule.bat type --file samples/dirty.txt --type EXAM
.\app\build\install\schedule\bin\schedule.bat errors --file samples/dirty.txt
```

Запуск напрямую через Gradle во время разработки:
```bash
./gradlew :app:run --args="period --file samples/clean.txt --from 2026-09-28 --to 2027-05-13"
```

### 3. Автоматическая самопроверка
```bash
python3 tools/verify.py
```

---

## Этап 1. Доменная модель, инварианты и репозиторий

### 1. Выбор между `class` и `data class`
Для классов занятий (`Lecture`, `Practice`, `Exam`, `Cancelled`) выбран **обычный `class`** (в составе закрытой иерархии `sealed interface Session` и `sealed class ActiveSession`) с явным переопределением `equals`, `hashCode` и `toString`.

**Причина отказа от `data class`:**
* Компилятор Kotlin автоматически генерирует для `data class` публичный метод `copy(...)`.
* Метод `copy` может использоваться для обхода пользовательских проверок или передачи мутабельных ссылок, если инварианты завязаны на специфичную логику конструирования.
* Использование обычного `class` полностью исключает метод `copy()` из публичного API: любой экземпляр занятия гарантированно проходит через первичный конструктор, проверяющий все инварианты.

### 2. Защита коллекций
* **Защита от изменения исходного списка:** В конструкторе `ActiveSession` переданный список преподавателей копируется через `java.util.List.copyOf(teachers)`. Если внешний код после создания объекта вызовет `names.clear()` или изменит исходный `MutableList`, состояние созданного занятия не изменится.
* **Защита от изменения через публичное свойство:** `java.util.List.copyOf` возвращает строго неизменяемый список (`unmodifiable list`). При попытке модифицировать список, полученный из `session.teachers` (например, через приведение типа к `MutableList`), выбрасывается `UnsupportedOperationException`.
* **Защита снимков реестра (`SessionRepository`):** Метод `all()` возвращает защитную копию `java.util.List.copyOf(storage)`. Последующие вызовы `add` и `addAll` не изменяют ранее выданные снимки, а модификация полученного снимка извне невозможна.

### 3. Журналирование через делегирование (`AuditedSessionRepository`)
Класс `AuditedSessionRepository` реализует интерфейс `SessionRepository` с помощью встроенного механизма делегирования Kotlin:
```kotlin
class AuditedSessionRepository(
    private val delegate: SessionRepository
) : SessionRepository by delegate
```

**Почему переопределить только `add` недостаточно для учёта вызовов `addAll`?**
1. **Отсутствие self-delegation в паттерне Декоратор:** Делегирование `by delegate` перенаправляет все непереопределённые методы напрямую внутреннему объекту `delegate`. Внутри `InMemorySessionRepository` метод `addAll` обращается к внутреннему списку напрямую (`storage.addAll(sessions)`), не вызывая `add()`. Более того, даже если бы `delegate.addAll()` вызывал `this.add()`, ссылка `this` внутри делегата указывает на сам `InMemorySessionRepository`, а не на внешнюю обёртку `AuditedSessionRepository`. Вызовы не прошли бы через переопределённый метод `AuditedSessionRepository.add()`.
2. **Семантика журнала аудита:** По контракту один вызов `addAll(sessions)` должен создавать ровно **одно** событие журнала (`operation = "addAll"`, `count = sessions.size`), в том числе событие с количеством `0` при `addAll(emptyList())`, а не серию одиночных событий `add`.

### 4. Защита инвариантов («Сломай свою модель»)

| № | Попытка нарушить ограничения | Что защищает модель |
|---|---|---|
| 1 | Создать занятие с пустой или пробельной темой | **Проверка при создании:** `require` в `init` проверяет непустоту, отсутствие управляющих символов и внешних пробелов. |
| 2 | Создать занятие с годом `0` или `10000` | **Проверка при создании:** `require(date.year in 1..9999)`. |
| 3 | Создать действующее занятие без преподавателей | **Проверка при создании:** в `ActiveSession` проверяется `require(teachers.size >= minTeachers)` (`minTeachers >= 1`). |
| 4 | Создать сдачу (`Exam`) с одним преподавателем | **Проверка при создании:** класс `Exam` передаёт `minTeachers = 2` в `ActiveSession`. |
| 5 | Создать сдачу с двумя одинаковыми преподавателями | **Проверка при создании:** проверка уникальности `require(teachers.toSet().size == teachers.size)`. |
| 6 | Передать место или преподавателей в отменённое занятие (`Cancelled`) | **Устройство типа:** у класса `Cancelled` в принципе нет свойств `location` и `teachers` (даже nullable). |
| 7 | Изменить исходный список преподавателей после создания занятия | **Защита коллекции:** создание защитной копии `java.util.List.copyOf(teachers)` в конструкторе. |
| 8 | Изменить состояние занятия через выданный список `session.teachers` | **Защита коллекции:** свойство возвращает неизменяемый список (`unmodifiable list`), бросающий `UnsupportedOperationException` при попытке мутации. |
| 9 | Обойти проверки через `copy()` | **Устройство типа:** отказ от `data class` в пользу обычного `class` исключает наличие метода `copy()`. |

---

## Этап 2. Разбор файла и ошибки как данные

* Интерфейс `Session` и базовый класс `ActiveSession` объявлены как `sealed`, что обеспечивает исчерпывающую проверку (`exhaustive when`) при форматировании и фильтрации без ветки `else`.
* Разбор одной строки файла (`parseScheduleLine`) никогда не выбрасывает исключений на некорректных входных данных и возвращает тип из закрытой иерархии `LineParseResult`:
  * `LineParseResult.Success(val session: Session)`
  * `LineParseResult.Failure(val lineNumber: Long, val reason: ParseErrorReason)`
* Причины ошибок представлены структурированной иерархией `sealed interface ParseErrorReason` (без хранения готовых строк или `Throwable`).
* Преобразование структурированной причины в итоговое сообщение вынесено в отдельную функцию `formatLineError`, а значения параметров экранируются функцией `escapeForErrorMessage` (`\\`, `\"`, `\uXXXX` для управляющих и не-ASCII символов).

### Порядок проверок и формат сообщений об ошибках

#### Обязательные сообщения (приоритеты 1–6):
1. Нет разделителя `|` (`InvalidFieldSeparator`):  
   `line N: invalid field separator`
2. Неверное количество полей (`InvalidFieldCount`):  
   `line N: got <count> fields, expected 5`
3. Пустое или пробельное обязательное поле (`EmptyRequiredField`):  
   `line N: empty required field "<field>"`
4. Неверный формат или несуществующая дата (`InvalidDate`):  
   `line N: invalid date "<rawDate>"`
5. Неизвестный тип занятия (`UnknownSessionType`):  
   `line N: unknown session type "<rawType>"`
6. У сдачи (`EXAM`) менее двух валидных преподавателей (`InsufficientExamTeachers`):  
   `line N: type "EXAM" requires at least 2 teachers, got <count>`

#### Сообщения для остальных нарушений домена (приоритет 7, подпункты 7.1–7.6):

| Приоритет | Причина (`ParseErrorReason`) | Формат сообщения |
|---|---|---|
| **7.1** | Управляющие символы в поле (`ControlCharactersInField`) | `line N: control characters in field "<field>": "<escaped_value>"` |
| **7.2** | Внешние пробельные символы (`OuterWhitespaceInField`) | `line N: outer whitespace in field "<field>": "<escaped_value>"` |
| **7.3** | Неверные поля отмены (`InvalidCancelledFields`) | `line N: field "<field>" must be "-" for CANCELLED, got "<escaped_value>"` |
| **7.4** | Недопустимое место `-` у действующего занятия (`InvalidActiveLocation`) | `line N: invalid location "<escaped_value>"` |
| **7.5** | Недопустимый элемент списка преподавателей (`InvalidTeacherElement`) | `line N: invalid teacher name "<escaped_value>"` |
| **7.6** | Повторное имя преподавателя (`DuplicateTeacherName`) | `line N: duplicate teacher "<escaped_name>"` |

---

## Дополнительное задание (со звёздочкой): перенос занятия

Реализована операция переноса занятия `Session.reschedule(newDate: LocalDate): RescheduleResult`:
* Возвращает `RescheduleResult.Success(session)` с новым объектом того же конкретного типа (`Lecture`, `Practice`, `Exam`) и сохранением темы, места и списка преподавателей.
* Исходный объект остаётся неизменным.
* При недопустимом годе новой даты конструктор модели выбрасывает `IllegalArgumentException`.
* При попытке перенести отменённое занятие (`Cancelled`) возвращается типизированный отказ `RescheduleResult.CancelledCannotBeRescheduled`.