package schedule

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SessionTest {

    private val validDate = LocalDate.of(2026, 10, 15)

    @Test
    fun cannotCreateSessionWithBlankTopic() {
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "", "Room 101", listOf("Teacher"))
        }
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "   ", "Room 101", listOf("Teacher"))
        }
    }

    @Test
    fun cannotCreateSessionWithInvalidYear() {
        assertFailsWith<IllegalArgumentException> {
            Cancelled(LocalDate.of(0, 1, 1), "Topic")
        }
        assertFailsWith<IllegalArgumentException> {
            Cancelled(LocalDate.of(10000, 1, 1), "Topic")
        }
    }

    @Test
    fun cannotCreateActiveSessionWithoutTeachers() {
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "Topic", "Room 101", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            Practice(validDate, "Topic", "Room 101", emptyList())
        }
    }

    @Test
    fun cannotCreateExamWithSingleTeacher() {
        assertFailsWith<IllegalArgumentException> {
            Exam(validDate, "Exam Topic", "Room 101", listOf("Single Teacher"))
        }
    }

    @Test
    fun cannotCreateExamWithDuplicateTeachers() {
        assertFailsWith<IllegalArgumentException> {
            Exam(validDate, "Exam Topic", "Room 101", listOf("Ivanov", "Ivanov"))
        }
    }

    @Test
    fun mutatingSourceListDoesNotAffectSession() {
        val originalList = mutableListOf("Teacher 1", "Teacher 2")
        val exam = Exam(validDate, "Exam Topic", "Room 101", originalList)

        originalList.clear()

        assertEquals(2, exam.teachers.size)
        assertEquals(listOf("Teacher 1", "Teacher 2"), exam.teachers)
    }

    @Test
    fun returnedTeachersListIsUnmodifiable() {
        val lecture = Lecture(validDate, "Topic", "Room 101", listOf("Teacher 1"))
        val returnedTeachers = lecture.teachers

        assertFailsWith<UnsupportedOperationException> {
            (returnedTeachers as MutableList<String>).add("Hacker")
        }
    }

    @Test
    fun validatesControlCharactersAndWhitespaceAndHyphen() {
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "Topic\tTab", "Room 101", listOf("Teacher"))
        }
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, " Topic", "Room 101", listOf("Teacher"))
        }
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "Topic", "-", listOf("Teacher"))
        }
        assertFailsWith<IllegalArgumentException> {
            Lecture(validDate, "Topic", "Room 101", listOf("-"))
        }
    }

    @Test
    fun createsValidSessionsAndChecksStructuralEquality() {
        val lecture1 = Lecture(validDate, "Kotlin", "Room 101", listOf("Ivanov"))
        val lecture2 = Lecture(validDate, "Kotlin", "Room 101", listOf("Ivanov"))
        val practice = Practice(validDate, "Kotlin", "Room 101", listOf("Ivanov"))
        val cancelled = Cancelled(validDate, "Kotlin")

        assertEquals(lecture1, lecture2)
        assertEquals(lecture1.hashCode(), lecture2.hashCode())

        assertNotEquals<Session>(lecture1, practice)
        assertNotEquals<Session>(lecture1, cancelled)
    }

    @Test
    fun repositoryProvidesIsolatedSnapshots() {
        val repo = InMemorySessionRepository()
        val s1 = Cancelled(validDate, "Event 1")
        repo.add(s1)

        val snapshot = repo.all()
        assertEquals(1, snapshot.size)

        val s2 = Cancelled(validDate.plusDays(1), "Event 2")
        repo.add(s2)
        assertEquals(1, snapshot.size)
        assertEquals(2, repo.all().size)

        assertFailsWith<UnsupportedOperationException> {
            (snapshot as MutableList<Session>).add(s2)
        }
    }

    @Test
    fun auditedRepositoryTracksCountsAndEventsCorrectly() {
        val baseRepo = InMemorySessionRepository()
        val auditedRepo = AuditedSessionRepository(baseRepo)

        val s1 = Cancelled(validDate, "Event 1")
        val s2 = Cancelled(validDate, "Event 2")
        val s3 = Cancelled(validDate, "Event 3")

        auditedRepo.add(s1)
        auditedRepo.addAll(listOf(s2, s3))

        assertEquals(3, auditedRepo.totalAdded)
        assertEquals(2, auditedRepo.events.size)
        assertEquals(AuditEvent("add", 1), auditedRepo.events[0])
        assertEquals(AuditEvent("addAll", 2), auditedRepo.events[1])

        assertEquals(3, auditedRepo.all().size)
        assertEquals(2, auditedRepo.events.size)
    }

    @Test
    fun reschedulesActiveSessionsAndRejectsCancelled() {
        val original = Exam(LocalDate.of(2026, 10, 15), "Oral exam", "Room 401", listOf("Portnov", "Stepanova"))
        val result = original.reschedule(LocalDate.of(2026, 10, 20))
        val moved = kotlin.test.assertIs<RescheduleResult.Success>(result).session

        assertEquals(LocalDate.of(2026, 10, 20), moved.date)
        assertEquals(LocalDate.of(2026, 10, 15), original.date) // исходное не изменилось
        assertEquals(original.topic, moved.topic)

        val cancelled = Cancelled(LocalDate.of(2026, 10, 19), "Postponed")
        assertEquals(RescheduleResult.CancelledSessionCannotBeRescheduled, cancelled.reschedule(LocalDate.of(2026, 10, 25)))

        assertFailsWith<IllegalArgumentException> {
            original.reschedule(LocalDate.of(0, 1, 1))
        }
    }
}