package org.rri.ideals.server.extensions

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.BaseRunConfigurationAction
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.JavaCommandLine
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.LineMarkerActionWrapper
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import org.rri.ideals.server.util.MiscUtil.distinctByKey
import java.util.Arrays
import java.util.Objects
import java.util.function.Supplier
import java.util.stream.Stream

class RunnablesCommand : LspCommand<List<Runnable?>?>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "experimental/runnables call" }

    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): List<Runnable> {
        val project: Project = ctx.editor.getProject()!!

        return DaemonCodeAnalyzerImpl.getLineMarkers(ctx.editor.getDocument(), project)
            .stream()
            .flatMap<Runnable> { lineMarkerInfo: LineMarkerInfo<*> ->
                val gutter: GutterIconRenderer = lineMarkerInfo.createGutterRenderer()
                if (gutter.getPopupMenuActions() != null) {
                    return@flatMap Arrays.stream<AnAction>((gutter.getPopupMenuActions() as DefaultActionGroup).getChildActionsOrStubs())
                        .filter { anAction: AnAction -> "Run context configuration" == anAction.getTemplateText() }
                        .filter { anAction: AnAction? -> anAction is LineMarkerActionWrapper }
                        .map<Runnable> { anAction: AnAction? ->
                            val lineMarkerActionWrapper: LineMarkerActionWrapper =
                                anAction as LineMarkerActionWrapper
                            val dataContext: DataContext = SimpleDataContext.builder()
                                .add<Project>(CommonDataKeys.PROJECT, project)
                                .add(CommonDataKeys.PSI_ELEMENT, lineMarkerInfo.getElement())
                                .build()
                            val executor =
                                (lineMarkerActionWrapper.getDelegate() as ExecutorAction).executor
                            val configurationContext: ConfigurationContext =
                                ConfigurationContext.getFromContext(dataContext, "")
                            val runnerAndConfigurationSettings: RunnerAndConfigurationSettings? =
                                configurationContext.findExisting()

                            if (runnerAndConfigurationSettings != null) {
                                val actionName =
                                    executor.actionName + " '" + BaseRunConfigurationAction.suggestRunActionName(
                                        runnerAndConfigurationSettings.getConfiguration()
                                    ) + "'"

                                try {
                                    val environment: ExecutionEnvironment =
                                        ExecutionEnvironmentBuilder.create(
                                            executor,
                                            runnerAndConfigurationSettings
                                        ).build()
                                    val currentState = environment.getState()
                                    val commandLine =
                                        (currentState as JavaCommandLine).getJavaParameters()
                                            .toCommandLine()
                                    return@map Runnable(
                                        actionName, Runnable.Arguments(
                                            commandLine.getWorkingDirectory().toString(),
                                            commandLine.getExePath(),
                                            commandLine.getParametersList().getList()
                                        )
                                    )
                                } catch (e: ExecutionException) {
                                    LOG.error("Unable to create an execution environment", e)
                                }
                            }
                            null
                        }.filter { o: Runnable? -> Objects.nonNull(o) }
                }
                Stream.of<Runnable?>()
            }
            .filter(distinctByKey(Runnable::label))
            .toList()
    }

    companion object {
        private val LOG = Logger.getInstance(
            RunnablesCommand::class.java
        )
    }
}
