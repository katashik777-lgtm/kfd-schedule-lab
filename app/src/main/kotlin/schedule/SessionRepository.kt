package schedule

interface SessionRepository {
    fun add(session: Session)
    fun addAll(sessions: List<Session>)
    fun all(): List<Session>
}

class InMemorySessionRepository : SessionRepository {
    private val storage = mutableListOf<Session>()

    override fun add(session: Session) {
        storage.add(session)
    }

    override fun addAll(sessions: List<Session>) {
        storage.addAll(sessions)
    }

    override fun all(): List<Session> {
        return java.util.List.copyOf(storage)
    }
}

data class AuditEvent(
    val operation: String,
    val count: Int
)

class AuditedSessionRepository(
    private val delegate: SessionRepository
) : SessionRepository by delegate {

    private val mutableEvents = mutableListOf<AuditEvent>()

    var totalAdded: Int = 0
        private set

    val events: List<AuditEvent>
        get() = java.util.List.copyOf(mutableEvents)

    override fun add(session: Session) {
        delegate.add(session)
        totalAdded += 1
        mutableEvents.add(AuditEvent("add", 1))
    }

    override fun addAll(sessions: List<Session>) {
        delegate.addAll(sessions)
        val addedCount = sessions.size
        totalAdded += addedCount
        mutableEvents.add(AuditEvent("addAll", addedCount))
    }
}