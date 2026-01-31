package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Global service to manage PR comments visibility state
 * This state is persistent across tool window open/close
 */
@Service(Service.Level.PROJECT)
class CommentsVisibilityService(private val project: Project) {
    
    private val logger = Logger.getInstance(CommentsVisibilityService::class.java)
    private val properties = com.intellij.ide.util.PropertiesComponent.getInstance(project)
    private val VISIBILITY_KEY = "azuredevops.comments.visible"
    
    fun isCommentsVisible(): Boolean {
        val value = properties.getBoolean(VISIBILITY_KEY, false)
        logger.info("isCommentsVisible() returning: $value")
        return value
    }
    
    fun setCommentsVisible(visible: Boolean) {
        logger.info("setCommentsVisible() called with: $visible")
        properties.setValue(VISIBILITY_KEY, visible)
    }
    
    companion object {
        fun getInstance(project: Project): CommentsVisibilityService {
            return project.getService(CommentsVisibilityService::class.java)
        }
    }
}
