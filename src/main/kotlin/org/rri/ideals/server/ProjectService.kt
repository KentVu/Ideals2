package org.rri.ideals.server

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Arrays
import java.util.Objects

@Service//(Service.Level.PROJECT)
class ProjectService {
    private val projectHashes: MutableMap<LspPath, String> = HashMap()

    fun resolveProjectFromRoot(root: LspPath): Project {
        // TODO: in-memory virtual files for testing have temp:/// prefix, figure out how to resolve the document from them
        // otherwise it gets confusing to have to look up the line and column being tested in the test document

        require(Files.isDirectory(root.toPath())) { "Isn't a directory: $root" }

        return ensureProject(root)
    }

    fun closeProject(project: Project) {
        if (projectHashes.values.remove(project.locationHash)) {
            LOG.info("Closing project: $project")
            val closed = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                closed[0] = ProjectManagerEx.getInstanceEx().forceCloseProject(project)
            }
            if (!closed[0]) {
                LOG.warn("Closing project: Project wasn't closed: $project")
            }
        } else {
            LOG.warn("Closing project: Project wasn't opened by LSP server; do nothing: $project")
        }
    }

    private fun ensureProject(projectPath: LspPath): Project {
        val project = getProject(projectPath)
        requireNotNull(project) { "Couldn't find document at $projectPath" }
        require(!project.isDisposed) { "Project was already disposed: $project" }

        return project
    }

    private fun getProject(projectPath: LspPath): Project? {
        val mgr = ProjectManagerEx.getInstanceEx()

        val projectHash = projectHashes[projectPath]
        if (projectHash != null) {
            val project = mgr.findOpenProjectByHash(projectHash)
            if (project != null && !project.isDisposed) {
                return project
            } else {
                LOG.info("Cached document was disposed, reopening: $projectPath")
            }
        }

        if (!Files.exists(projectPath.toPath())) {  // todo VirtualFile?
            LOG.warn("Project path doesn't exist: $projectPath")
            return null
        }

        val project = findOrLoadProject(projectPath, mgr)

        if (project != null) {
            waitUntilInitialized(project)
            cacheProject(projectPath, project)
        }

        return project
    }

    private fun findOrLoadProject(projectPath: LspPath, mgr: ProjectManagerEx): Project? {
        return Arrays.stream(mgr.openProjects)
            .filter { it: Project ->
                LspPath.fromLocalPath(Paths.get(Objects.requireNonNull(it.basePath)))
                    .equals(projectPath)
            }
            .findFirst()
            .orElseGet {
                mgr.openProject(
                    projectPath.toPath(),
                    OpenProjectTask(false, null, false, false).withForceOpenInNewFrame(true)
                )
            }
    }

    private fun waitUntilInitialized(project: Project) {
        try {
            // Wait until the project is initialized to prevent invokeAndWait hangs
            // todo avoid
            while (!project.isInitialized) {
                Thread.sleep(100)
            }
        } catch (e: InterruptedException) {
            LOG.warn(
                "Interrupted while waiting for project to be initialized: " + project.basePath,
                e
            )
            throw RuntimeException(e)
        }
    }

    private fun cacheProject(projectPath: LspPath, project: Project) {
        LOG.info("Caching project: $projectPath")
        projectHashes[projectPath] = project.locationHash
    }

    companion object {
        private val LOG = Logger.getInstance(
            ProjectService::class.java
        )

        @JvmStatic
        val instance: ProjectService
            get() = service()    }
}
