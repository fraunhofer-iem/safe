package de.fraunhofer.iem.safe.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import java.util.concurrent.ConcurrentHashMap


@Service(Service.Level.PROJECT)
@com.intellij.openapi.components.State(name = "SafeExplanationCache", storages = [Storage("saferExplanations.xml")])
class ExplanationCacheService : PersistentStateComponent<ExplanationCacheService.CacheState> {

    data class CachedExplanation(
        var inspectionId: String = "",
        var cwe: String? = null,
        var fileName: String? = null,
        var response: String = ""
    )

    class CacheState {
        var entries: MutableList<CachedExplanation> = mutableListOf()
    }

    private var myCacheState = CacheState()

    override fun getState(): CacheState = myCacheState

    override fun loadState(state: CacheState) {
        myCacheState = state
    }

    fun getAll(): List<CachedExplanation> = myCacheState.entries.toList()

    fun find(inspectionId: String, fileName: String?): CachedExplanation? {
        return myCacheState.entries.firstOrNull {
            it.inspectionId == inspectionId && it.fileName == fileName
        }
    }

    fun store(inspectionId: String, cwe: String?, fileName: String?, response: String) {
        val existing = find(inspectionId, fileName)
        if (existing != null) {
            existing.response = response
            existing.cwe = cwe
        } else {
            myCacheState.entries.add(CachedExplanation(inspectionId, cwe, fileName, response))
        }
    }

    companion object {
        fun getInstance(project: Project): ExplanationCacheService =
            project.getService(ExplanationCacheService::class.java)
    }
}

@Service(Service.Level.PROJECT)
class ExplanationStorageService {

    data class ExplanationEntry(
        val vulnerability: VulnerabilityInfo,
        val explanation: String

    )

    private val explanations = ConcurrentHashMap<String, MutableList<ExplanationEntry>>()

    fun store(entry: ExplanationEntry) {
        explanations.getOrPut(entry.vulnerability.filePath) { mutableListOf() }.add(entry)
    }

    fun getForFile(filePath: String): List<ExplanationEntry> {
        return explanations[filePath] ?: emptyList()
    }

    fun getAll(): List<ExplanationEntry> = explanations.values.flatten().toList()

    fun clear() {
        explanations.clear()
    }

    companion object {
        fun getInstance(project: Project): ExplanationStorageService = project.service()
    }
}