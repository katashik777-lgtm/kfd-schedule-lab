package schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LineParserTest {

    @Test
    fun respectsErrorPriorityOnMultipleDefects() {
        // Нет разделителя '|' + плохая дата -> приоритет 1 (invalid field separator)
        val r1 = assertIs<LineParseResult.Failure>(parseScheduleLine(1, "2026-13-45;SEMINAR;Topic;Room 1;Ivanov"))
        assertEquals("line 1: invalid field separator", formatLineError(r1))

        // Неверное число полей (6) + пустое поле + плохая дата -> приоритет 2 (got 6 fields)
        val r2 = assertIs<LineParseResult.Failure>(parseScheduleLine(2, "|SEMINAR|Topic|Room 1|Ivanov|extra"))
        assertEquals("line 2: got 6 fields, expected 5", formatLineError(r2))

        // Несколько пустых полей -> приоритет 3 (первое пустое поле по порядку)
        val r3 = assertIs<LineParseResult.Failure>(parseScheduleLine(3, "2026-10-05||   ||Ivanov"))
        assertEquals("line 3: empty required field \"type\"", formatLineError(r3))

        // Пример из ТЗ: плохая дата + неизвестный тип -> приоритет 4 (invalid date)
        val r4 = assertIs<LineParseResult.Failure>(parseScheduleLine(4, "2026-13-45|SEMINAR|Topic|Room 1|Ivanov"))
        assertEquals("line 4: invalid date \"2026-13-45\"", formatLineError(r4))

        // Неизвестный тип + некорректное место '-' -> приоритет 5 (unknown session type)
        val r5 = assertIs<LineParseResult.Failure>(parseScheduleLine(5, "2026-10-05|SEMINAR|Topic|-|Ivanov"))
        assertEquals("line 5: unknown session type \"SEMINAR\"", formatLineError(r5))

        // EXAM с 1 валидным преподавателем и '-' + плохая локация '-' -> приоритет 6 (requires at least 2 teachers)
        val r6 = assertIs<LineParseResult.Failure>(parseScheduleLine(6, "2026-10-05|EXAM|Topic|-|Ivanov,-"))
        assertEquals("line 6: type \"EXAM\" requires at least 2 teachers, got 1", formatLineError(r6))

        // Пример из ТЗ: EXAM с двумя одинаковыми именами -> проходит п.6 (элементов 2), ловится в п.7.6 (дубликат)
        val r7 = assertIs<LineParseResult.Failure>(parseScheduleLine(7, "2026-10-05|EXAM|Topic|Room 1|Ivanov,Ivanov"))
        assertEquals("line 7: duplicate teacher \"Ivanov\"", formatLineError(r7))
    }

    @Test
    fun respectsDomainErrorSubPrioritiesInStep7() {
        // 7.1 (управляющий символ) важнее 7.2 (внешний пробел) и 7.4 (локация '-')
        val r1 = assertIs<LineParseResult.Failure>(parseScheduleLine(1, "2026-10-05|LECTURE|Topic\t1| - |Ivanov"))
        assertEquals("line 1: control characters in field \"topic\": \"Topic\\u00091\"", formatLineError(r1))

        // 7.2 (внешние пробелы) важнее 7.3 (поля отмены)
        val r2 = assertIs<LineParseResult.Failure>(parseScheduleLine(2, "2026-10-05|CANCELLED| Topic |Room 1|Ivanov"))
        assertEquals("line 2: outer whitespace in field \"topic\": \" Topic \"", formatLineError(r2))

        // 7.3: у CANCELLED проверяется сначала location, потом teachers
        val r3 = assertIs<LineParseResult.Failure>(parseScheduleLine(3, "2026-10-05|CANCELLED|Topic|Room 1|Ivanov"))
        assertEquals("line 3: field \"location\" must be \"-\" for CANCELLED, got \"Room 1\"", formatLineError(r3))

        val r4 = assertIs<LineParseResult.Failure>(parseScheduleLine(4, "2026-10-05|CANCELLED|Topic|-|Ivanov"))
        assertEquals("line 4: field \"teachers\" must be \"-\" for CANCELLED, got \"Ivanov\"", formatLineError(r4))

        // 7.4 (недопустимое место '-') важнее 7.6 (повтор преподавателей)
        val r5 = assertIs<LineParseResult.Failure>(parseScheduleLine(5, "2026-10-05|EXAM|Topic|-|Ivanov,Ivanov"))
        assertEquals("line 5: invalid location \"-\"", formatLineError(r5))

        // 7.5 (недопустимый элемент списка преподавателей '-') важнее 7.6 (дубликат)
        val r6 = assertIs<LineParseResult.Failure>(parseScheduleLine(6, "2026-10-05|EXAM|Topic|Room 1|Ivanov,-,Ivanov"))
        assertEquals("line 6: invalid teacher name \"-\"", formatLineError(r6))
    }

    @Test
    fun escapesQuotesSlashesControlAndNonAsciiCharactersInErrorMessages() {
        val r = assertIs<LineParseResult.Failure>(
            parseScheduleLine(10, "2026-10-05|ПРАКТИКА\"\\|Topic|Room 1|Ivanov")
        )
        assertEquals(
            "line 10: unknown session type \"\\u041f\\u0420\\u0410\\u041a\\u0422\\u0418\\u041a\\u0410\\\"\\\\\"",
            formatLineError(r)
        )
    }
}