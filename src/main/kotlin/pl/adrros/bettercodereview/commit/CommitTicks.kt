package pl.adrros.bettercodereview.commit

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The Commit panel's ticks of a project, kept across restarts: the IDE itself starts with every change ticked.
 * Each tick keeps the digest of the content it was given, so a file changed while the IDE was closed shows a dash.
 * Lives in the workspace file, which stays out of the repository.
 */
@Service(Service.Level.PROJECT)
@State(name = "BetterCodeReview.CommitTicks", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CommitTicks : PersistentStateComponent<CommitTicks.TicksState> {
    private var state: TicksState? = null

    /** Null until the first save: the IDE's own ticks stay as they are then. */
    val ticks: List<Tick>? get() = state?.ticks

    fun save(ticks: List<Tick>) {
        state = TicksState().apply { this.ticks = ticks.toMutableList() }
    }

    /** Back to "never saved", for tests. */
    internal fun forget() {
        state = null
    }

    override fun getState(): TicksState? = state

    override fun loadState(state: TicksState) {
        this.state = state
    }

    class TicksState {
        @XCollection
        var ticks: MutableList<Tick> = mutableListOf()
    }

    /** A ticked file's path, the hex digest of its content at the tick (empty when unknown) and whether it was flagged. */
    data class Tick(var path: String = "", var digest: String = "", var flagged: Boolean = false)
}
