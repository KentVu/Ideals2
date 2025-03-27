package org.rri.ideals.server.references

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.FindManager
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesHandlerBase
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.impl.FindManagerImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiInvalidElementAccessException
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchRequestCollector
import com.intellij.psi.search.SearchSession
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageInfoToUsageConverter
import com.intellij.usages.UsageSearcher
import com.intellij.util.ArrayUtil
import com.intellij.util.CommonProcessors
import com.intellij.util.Processor
import com.intellij.util.containers.ContainerUtil
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import org.rri.ideals.server.util.LspProgressIndicator
import org.rri.ideals.server.util.MiscUtil
import org.rri.ideals.server.util.MiscUtil.psiElementToLocation
import java.util.Objects
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.function.Supplier
import java.util.stream.Collectors

class FindUsagesCommand : LspCommand<List<Location?>>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "References (Find usages) call" }

    override val isCancellable: Boolean
        get() = true

    override fun execute(ctx: ExecutorContext): List<Location> {
        val editor = ctx.editor
        val file = ctx.psiFile

        return Optional.ofNullable(
            TargetElementUtil.findTargetElement(
                editor,
                TargetElementUtil.getInstance().allAccepted
            )
        )
            .map { target: PsiElement ->
                findUsages(
                    file.project,
                    target,
                    ctx.cancelToken
                )
            }
            .orElse(listOf())
    }

    companion object {
        private val LOG = Logger.getInstance(
            FindUsagesCommand::class.java
        )

        private fun findUsages(
            project: Project,
            target: PsiElement,
            cancelToken: CancelChecker?
        ): List<Location> {
            val manager = (FindManager.getInstance(project) as FindManagerImpl).findUsagesManager
            val handler = manager.getFindUsagesHandler(
                target,
                FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS
            )

            return ProgressManager.getInstance().runProcess<List<Location>>(
                {
                    val result: List<Location>
                    if (handler != null) {
                        val dialog = handler.getFindUsagesDialog(false, false, false)
                        dialog.close(DialogWrapper.OK_EXIT_CODE)
                        val options = dialog.calcFindUsagesOptions()
                        val primaryElements = handler.primaryElements
                        val secondaryElements = handler.secondaryElements
                        val searcher = createUsageSearcher(
                            primaryElements,
                            secondaryElements,
                            handler,
                            options,
                            project
                        )
                        val saver = ContainerUtil.newConcurrentSet<Location>()
                        searcher.generate { usage: Usage ->
                            if (cancelToken != null) {
                                try {
                                    cancelToken.checkCanceled()
                                } catch (e: CancellationException) {
                                    return@generate false
                                }
                            }
                            if (usage is UsageInfo2UsageAdapter && !usage.isNonCodeUsage) {
                                val elem = usage.element
                                val loc = psiElementToLocation(elem)
                                if (loc != null) {
                                    saver.add(loc)
                                }
                            }
                            true
                        }
                        result = ArrayList(saver)
                    } else {
                        result = ReferencesSearch.search(target).findAll().stream()
                            .map<PsiElement> { obj: PsiReference -> obj.element }
                            .map(MiscUtil::psiElementToLocation)
                            .filter { o: Location? -> Objects.nonNull(o) }
                            .collect(Collectors.toList<Location>())
                    }
                    result
                }, LspProgressIndicator(cancelToken!!)
            )
        }

        // Took this function from com.intellij.find.findUsages.FindUsagesManager.
        // Reference solution (Ruin0x11/intellij-lsp-server) used outdated constructor of FindUsagesManager.
        // Now this constructor is not exists.
        @Throws(PsiInvalidElementAccessException::class)
        private fun createUsageSearcher(
            primaryElements: Array<PsiElement>,
            secondaryElements: Array<PsiElement>,
            handler: FindUsagesHandlerBase,
            options: FindUsagesOptions,
            project: Project
        ): UsageSearcher {
            val optionsClone = options.clone()
            return UsageSearcher { processor: Processor<in Usage?> ->
                val usageInfoProcessor: Processor<UsageInfo?> =
                    CommonProcessors.UniqueProcessor { usageInfo: UsageInfo? ->
                        val usage = if (usageInfo != null) UsageInfoToUsageConverter.convert(
                            primaryElements,
                            usageInfo
                        ) else null
                        processor.process(usage)
                    }
                val elements = ArrayUtil.mergeArrays(
                    primaryElements,
                    secondaryElements,
                    PsiElement.ARRAY_FACTORY
                )

                optionsClone.fastTrack = SearchRequestCollector(SearchSession(*elements))
                if (optionsClone.searchScope is GlobalSearchScope) {
                    // we will search in project scope always but warn if some usage is out of scope
                    optionsClone.searchScope =
                        optionsClone.searchScope.union(GlobalSearchScope.projectScope(project))
                }
                try {
                    for (element in elements) {
                        if (!handler.processElementUsages(
                                element,
                                usageInfoProcessor,
                                optionsClone
                            )
                        ) {
                            return@UsageSearcher
                        }

                        for (searcher in CustomUsageSearcher.EP_NAME.extensionList) {
                            try {
                                searcher.processElementUsages(element, processor, optionsClone)
                            } catch (e: ProcessCanceledException) {
                                throw e
                            } catch (e: Exception) {
                                LOG.error(e)
                            }
                        }
                    }

                    PsiSearchHelper.getInstance(project).processRequests(
                        optionsClone.fastTrack
                    ) { ref: PsiReference ->
                        val info = if (ref.element.isValid) UsageInfo(ref) else null
                        usageInfoProcessor.process(info)
                    }
                } finally {
                    optionsClone.fastTrack = null
                }
            }
        }
    }
}