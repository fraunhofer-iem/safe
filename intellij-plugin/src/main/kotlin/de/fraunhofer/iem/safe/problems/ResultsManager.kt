package de.fraunhofer.iem.safe.problems

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.fraunhofer.iem.safe.problems.model.Finding
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ResultsManager : Disposable {

    companion object {

        fun getInstance(project: Project): ResultsManager = project.service()
    }

    private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val state = ConcurrentHashMap<VirtualFile, List<Finding>>()

    fun register(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    fun getAllFindingsSize(): Int = state.values.fold(0) { acc, cur ->
        acc + cur.size
    }

    fun getFindings(file: VirtualFile): List<Finding> {

        return state[file]?.toList() ?: emptyList()
    }

    fun getAnalyzedFiles(): Collection<VirtualFile> = state.keys.toList()

    fun put(file: VirtualFile, findings: List<Finding>) {
        state[file] = findings
    }

    override fun dispose() {
        listeners.clear()
        state.clear()
    }
}