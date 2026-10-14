package schedule

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

sealed interface LineParseResult {
    data class Success(val session: Session) : LineParseResult
    data class Failure(val lineNumber: Long, val reason: ParseErrorReason) : LineParseResult
}

sealed interface ParseErrorReason {
    data object InvalidFieldSeparator : ParseErrorReason
    data class InvalidFieldCount(val count: Int) : ParseErrorReason
    data class EmptyRequiredField(val fieldName: String) : ParseErrorReason
    data class InvalidDate(val rawDate: String) : ParseErrorReason
    data class UnknownSessionType(val rawType: String) : ParseErrorReason
    data class InsufficientExamTeachers(val count: Int) : ParseErrorReason

    // Пункт 7: Остальные нарушения домена
    data class ControlCharactersInField(val fieldName: String, val value: String) : ParseErrorReason
    data class OuterWhitespaceInField(val fieldName: String, val value: String) : ParseErrorReason
    data class InvalidCancelledFields(val fieldName: String, val value: String) : ParseErrorReason
    data class InvalidActiveLocation(val value: String) : ParseErrorReason
    data class InvalidTeacherElement(val value: String) : ParseErrorReason
    data class DuplicateTeacherName(val name: String) : ParseErrorReason
}

private val FIELD_NAMES = listOf("date", "type", "topic", "location", "teachers")

private val STRICT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

private fun parseStrictDate(raw: String): LocalDate? {
    if (raw.length != 10) return null
    for (i in raw.indices) {
        val c = raw[i]
        if (i == 4 || i == 7) {
            if (c != '-') return null
        } else {
            if (c !in '0'..'9') return null
        }
    }
    return try {
        val date = LocalDate.parse(raw, STRICT_DATE_FORMATTER)
        if (date.year in 1..9999) date else null
    } catch (_: Exception) {
        null
    }
}

private fun hasControlChars(s: String): Boolean =
    s.any { it.isISOControl() }

private fun hasOuterWhitespace(s: String): Boolean =
    s.isNotEmpty() && (s.first().isWhitespace() || s.last().isWhitespace())

fun parseScheduleLine(lineNumber: Long, line: String): LineParseResult {
    // 1. Нет '|' — неверный разделитель
    if (!line.contains('|')) {
        return LineParseResult.Failure(lineNumber, ParseErrorReason.InvalidFieldSeparator)
    }

    // 2. Полей не пять — неверное количество полей (включая конечные пустые)
    val parts = line.split('|')
    if (parts.size != 5) {
        return LineParseResult.Failure(lineNumber, ParseErrorReason.InvalidFieldCount(parts.size))
    }

    // 3. Есть пустое или полностью пробельное поле — первое такое поле в порядке следования
    for (i in 0 until 5) {
        if (parts[i].isBlank()) {
            return LineParseResult.Failure(lineNumber, ParseErrorReason.EmptyRequiredField(FIELD_NAMES[i]))
        }
    }

    val rawDate = parts[0]
    val rawType = parts[1]
    val rawTopic = parts[2]
    val rawLocation = parts[3]
    val rawTeachers = parts[4]

    // 4. Неверный формат или значение даты
    val date = parseStrictDate(rawDate)
        ?: return LineParseResult.Failure(lineNumber, ParseErrorReason.InvalidDate(rawDate))

    // 5. Неизвестный тип занятия
    val sessionType = when (rawType) {
        "LECTURE" -> SessionType.LECTURE
        "PRACTICE" -> SessionType.PRACTICE
        "EXAM" -> SessionType.EXAM
        "CANCELLED" -> SessionType.CANCELLED
        else -> return LineParseResult.Failure(lineNumber, ParseErrorReason.UnknownSessionType(rawType))
    }

    val teacherElements = rawTeachers.split(',')

    // 6. У сдачи менее двух непустых и непробельных элементов списка, отличных от '-'
    if (sessionType == SessionType.EXAM) {
        val validExamTeachersCount = teacherElements.count { it.isNotBlank() && it.trim() != "-" }
        if (validExamTeachersCount < 2) {
            return LineParseResult.Failure(
                lineNumber,
                ParseErrorReason.InsufficientExamTeachers(validExamTeachersCount)
            )
        }
    }

    // 7. Остальные нарушения домена (строго по порядку)

    // 7.1. Управляющие символы (первое поле в порядке следования)
    for (i in 0 until 5) {
        if (hasControlChars(parts[i])) {
            return LineParseResult.Failure(
                lineNumber,
                ParseErrorReason.ControlCharactersInField(FIELD_NAMES[i], parts[i])
            )
        }
    }

    // 7.2. Внешние пробельные символы
    // Проверяем поля date, type, topic, location, а для teachers — внешние пробелы поля и элементов
    for (i in 0 until 4) {
        if (hasOuterWhitespace(parts[i])) {
            return LineParseResult.Failure(
                lineNumber,
                ParseErrorReason.OuterWhitespaceInField(FIELD_NAMES[i], parts[i])
            )
        }
    }
    if (hasOuterWhitespace(rawTeachers) || (sessionType != SessionType.CANCELLED && teacherElements.any { hasOuterWhitespace(it) })) {
        return LineParseResult.Failure(
            lineNumber,
            ParseErrorReason.OuterWhitespaceInField("teachers", rawTeachers)
        )
    }

    // 7.3. Неверные поля отмены
    if (sessionType == SessionType.CANCELLED) {
        if (rawLocation != "-") {
            return LineParseResult.Failure(
                lineNumber,
                ParseErrorReason.InvalidCancelledFields("location", rawLocation)
            )
        }
        if (rawTeachers != "-") {
            return LineParseResult.Failure(
                lineNumber,
                ParseErrorReason.InvalidCancelledFields("teachers", rawTeachers)
            )
        }
        return LineParseResult.Success(Cancelled(date, rawTopic))
    }

    // 7.4. Недопустимое место действующего занятия
    if (rawLocation == "-") {
        return LineParseResult.Failure(lineNumber, ParseErrorReason.InvalidActiveLocation(rawLocation))
    }

    // 7.5. Недопустимые элементы списка преподавателей (выбираем первый недопустимый элемент)
    for (teacher in teacherElements) {
        if (teacher.isBlank() || teacher == "-" || hasControlChars(teacher) || hasOuterWhitespace(teacher)) {
            return LineParseResult.Failure(lineNumber, ParseErrorReason.InvalidTeacherElement(teacher))
        }
    }

    // 7.6. Повторные имена преподавателей
    val seenTeachers = HashSet<String>()
    for (teacher in teacherElements) {
        if (!seenTeachers.add(teacher)) {
            return LineParseResult.Failure(lineNumber, ParseErrorReason.DuplicateTeacherName(teacher))
        }
    }

    val session = when (sessionType) {
        SessionType.LECTURE -> Lecture(date, rawTopic, rawLocation, teacherElements)
        SessionType.PRACTICE -> Practice(date, rawTopic, rawLocation, teacherElements)
        SessionType.EXAM -> Exam(date, rawTopic, rawLocation, teacherElements)
        SessionType.CANCELLED -> error("Unreachable: handled above")
    }

    return LineParseResult.Success(session)
}

/**
 * Экранирует строку для вывода в сообщении об ошибке:
 * \ -> \\, " -> \", управляющие и не-ASCII символы -> \uXXXX
 */
fun escapeForErrorMessage(value: String): String {
    val sb = StringBuilder(value.length)
    for (ch in value) {
        when {
            ch == '\\' -> sb.append("\\\\")
            ch == '"' -> sb.append("\\\"")
            ch.isISOControl() || ch.code < 0x20 || ch.code > 0x7E -> {
                sb.append(String.format("\\u%04X", ch.code).lowercase())
            }
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * Отдельная функция преобразования структурированной ошибки в английское сообщение.
 */
fun formatLineError(failure: LineParseResult.Failure): String {
    val n = failure.lineNumber
    val msg = when (val r = failure.reason) {
        is ParseErrorReason.InvalidFieldSeparator ->
            "invalid field separator"
        is ParseErrorReason.InvalidFieldCount ->
            "got ${r.count} fields, expected 5"
        is ParseErrorReason.EmptyRequiredField ->
            "empty required field \"${escapeForErrorMessage(r.fieldName)}\""
        is ParseErrorReason.InvalidDate ->
            "invalid date \"${escapeForErrorMessage(r.rawDate)}\""
        is ParseErrorReason.UnknownSessionType ->
            "unknown session type \"${escapeForErrorMessage(r.rawType)}\""
        is ParseErrorReason.InsufficientExamTeachers ->
            "type \"EXAM\" requires at least 2 teachers, got ${r.count}"
        is ParseErrorReason.ControlCharactersInField ->
            "control characters in field \"${escapeForErrorMessage(r.fieldName)}\": \"${escapeForErrorMessage(r.value)}\""
        is ParseErrorReason.OuterWhitespaceInField ->
            "outer whitespace in field \"${escapeForErrorMessage(r.fieldName)}\": \"${escapeForErrorMessage(r.value)}\""
        is ParseErrorReason.InvalidCancelledFields ->
            "field \"${escapeForErrorMessage(r.fieldName)}\" must be \"-\" for CANCELLED, got \"${escapeForErrorMessage(r.value)}\""
        is ParseErrorReason.InvalidActiveLocation ->
            "invalid location \"${escapeForErrorMessage(r.value)}\""
        is ParseErrorReason.InvalidTeacherElement ->
            "invalid teacher name \"${escapeForErrorMessage(r.value)}\""
        is ParseErrorReason.DuplicateTeacherName ->
            "duplicate teacher \"${escapeForErrorMessage(r.name)}\""
    }
    return "line $n: $msg"
}