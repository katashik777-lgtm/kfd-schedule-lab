package schedule

interface CommandHandlers {
    fun period(command: Command.Period): Int
    fun byType(command: Command.ByType): Int
    fun errors(command: Command.Errors): Int
}

object StudentCommandHandlers : CommandHandlers {
    override fun period(command: Command.Period): Int = runPeriod(command)
    override fun byType(command: Command.ByType): Int = runType(command)
    override fun errors(command: Command.Errors): Int = runErrors(command)
}

private fun Session.matchesType(type: SessionType): Boolean = when (this) {
    is Lecture -> type == SessionType.LECTURE
    is Practice -> type == SessionType.PRACTICE
    is Exam -> type == SessionType.EXAM
    is Cancelled -> type == SessionType.CANCELLED
}

private fun formatForPeriod(session: Session): String = when (session) {
    is Lecture -> "${session.date} | LECTURE | ${session.topic} | ${session.location}"
    is Practice -> "${session.date} | PRACTICE | ${session.topic} | ${session.location}"
    is Exam -> "${session.date} | EXAM | ${session.topic} | ${session.location}"
    is Cancelled -> "${session.date} | CANCELLED | ${session.topic} | -"
}

private fun formatForType(session: Session): String = when (session) {
    is Lecture -> "${session.date} | LECTURE | ${session.topic} | ${session.location} | ${session.teachers.joinToString(", ")}"
    is Practice -> "${session.date} | PRACTICE | ${session.topic} | ${session.location} | ${session.teachers.joinToString(", ")}"
    is Exam -> "${session.date} | EXAM | ${session.topic} | ${session.location} | ${session.teachers.joinToString(", ")}"
    is Cancelled -> "${session.date} | CANCELLED | ${session.topic} | - | -"
}

fun runPeriod(command: Command.Period): Int {
    var matchedCount = 0
    var hasErrors = false

    forEachScheduleLine(command.file) { number, line ->
        when (val result = parseScheduleLine(number, line)) {
            is LineParseResult.Success -> {
                val session = result.session
                if (session.date in command.from..command.to) {
                    println(formatForPeriod(session))
                    matchedCount++
                }
            }
            is LineParseResult.Failure -> {
                hasErrors = true
            }
        }
    }

    println("Total: $matchedCount")
    return if (hasErrors) 1 else 0
}

fun runType(command: Command.ByType): Int {
    var matchedCount = 0
    var hasErrors = false

    forEachScheduleLine(command.file) { number, line ->
        when (val result = parseScheduleLine(number, line)) {
            is LineParseResult.Success -> {
                val session = result.session
                if (session.matchesType(command.type)) {
                    println(formatForType(session))
                    matchedCount++
                }
            }
            is LineParseResult.Failure -> {
                hasErrors = true
            }
        }
    }

    println("Total: $matchedCount")
    return if (hasErrors) 1 else 0
}

fun runErrors(command: Command.Errors): Int {
    var totalLines = 0L
    var rejectedLines = 0L

    forEachScheduleLine(command.file) { number, line ->
        totalLines++
        when (val result = parseScheduleLine(number, line)) {
            is LineParseResult.Success -> Unit
            is LineParseResult.Failure -> {
                rejectedLines++
                println(formatLineError(result))
            }
        }
    }

    println("Rejected: $rejectedLines of $totalLines")
    return if (rejectedLines > 0) 1 else 0
}