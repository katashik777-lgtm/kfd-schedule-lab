package schedule

import java.time.LocalDate

sealed interface Session {
    val date: LocalDate
    val topic: String
}

private fun validateCommonInvariants(date: LocalDate, topic: String) {
    require(date.year in 1..9999) { "Year must be between 1 and 9999, got ${date.year}" }
    require(topic.isNotBlank()) { "Topic must not be blank" }
    require(!topic.any { it.isISOControl() }) { "Topic must not contain control characters" }
    require(topic.trim() == topic) { "Topic must not have leading or trailing whitespace" }
}

private fun validateLocation(location: String) {
    require(location.isNotBlank()) { "Location must not be blank" }
    require(location != "-") { "Location must not be '-'" }
    require(!location.any { it.isISOControl() }) { "Location must not contain control characters" }
    require(location.trim() == location) { "Location must not have leading or trailing whitespace" }
}

private fun validateAndCopyTeachers(teachers: List<String>, minTeachers: Int): List<String> {
    require(teachers.size >= minTeachers) {
        "Expected at least $minTeachers teacher(s), got ${teachers.size}"
    }

    val copied = java.util.List.copyOf(teachers)

    val seen = HashSet<String>()
    for (teacher in copied) {
        require(teacher.isNotBlank()) { "Teacher name must not be blank" }
        require(teacher != "-") { "Teacher name must not be '-'" }
        require(!teacher.any { it.isISOControl() }) { "Teacher name must not contain control characters" }
        require(teacher.trim() == teacher) { "Teacher name must not have leading or trailing whitespace" }
        require(seen.add(teacher)) { "Duplicate teacher name: $teacher" }
    }

    return copied
}

sealed class ActiveSession(
    override val date: LocalDate,
    override val topic: String,
    val location: String,
    teachers: List<String>,
    minTeachers: Int
) : Session {
    val teachers: List<String>

    init {
        validateCommonInvariants(date, topic)
        validateLocation(location)
        this.teachers = validateAndCopyTeachers(teachers, minTeachers)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false // Проверяем ТОЧНЫЙ класс
        other as ActiveSession
        return date == other.date && topic == other.topic && location == other.location && teachers == other.teachers
    }

    override fun hashCode(): Int {
        var result = date.hashCode()
        result = 31 * result + topic.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + teachers.hashCode()
        return result
    }
}

class Lecture(date: LocalDate, topic: String, location: String, teachers: List<String>) :
    ActiveSession(date, topic, location, teachers, minTeachers = 1)

class Practice(date: LocalDate, topic: String, location: String, teachers: List<String>) :
    ActiveSession(date, topic, location, teachers, minTeachers = 1)

class Exam(date: LocalDate, topic: String, location: String, teachers: List<String>) :
    ActiveSession(date, topic, location, teachers, minTeachers = 2)

class Cancelled(
    override val date: LocalDate,
    override val topic: String
) : Session {
    init {
        validateCommonInvariants(date, topic)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Cancelled) return false
        return date == other.date && topic == other.topic
    }

    override fun hashCode(): Int {
        return 31 * date.hashCode() + topic.hashCode()
    }

    override fun toString(): String =
        "Cancelled(date=$date, topic='$topic')"
}

sealed interface RescheduleResult {
    data class Success(val session: ActiveSession) : RescheduleResult
    data object CancelledSessionCannotBeRescheduled : RescheduleResult
}

fun Session.reschedule(newDate: LocalDate): RescheduleResult = when (this) {
    is Lecture -> RescheduleResult.Success(Lecture(newDate, topic, location, teachers))
    is Practice -> RescheduleResult.Success(Practice(newDate, topic, location, teachers))
    is Exam -> RescheduleResult.Success(Exam(newDate, topic, location, teachers))
    is Cancelled -> RescheduleResult.CancelledSessionCannotBeRescheduled
}