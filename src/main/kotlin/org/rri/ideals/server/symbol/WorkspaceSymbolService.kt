package org.rri.ideals.server.symbol

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereToggleAction
import com.intellij.ide.actions.searcheverywhere.SymbolSearchEverywhereContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.StandardProgressIndicator
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.symbol.util.SymbolUtil.getSymbolKind
import org.rri.ideals.server.util.MiscUtil.psiElementToLocation
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@Service(Service.Level.PROJECT)
class WorkspaceSymbolService(private val project: Project) {
    private val LIMIT = 100

    @Suppress("deprecation")
    fun runSearch(pattern: String): CompletableFuture<Either<List<SymbolInformation?>, List<WorkspaceSymbol>>> {
        return CompletableFutures.computeAsync(
            AppExecutorUtil.getAppExecutorService()
        ) { cancelToken: CancelChecker? ->
            if (DumbService.isDumb(project)) {
                return@computeAsync Either.forRight<List<SymbolInformation?>, List<WorkspaceSymbol>?>(
                    null
                )
            }
            val result = execute(pattern, cancelToken).stream()
                .map(WorkspaceSearchResult::symbol)
                .toList()
            Either.forRight(
                result
            )
        }
    }

    private fun execute(pattern: String, cancelToken: CancelChecker?): List<WorkspaceSearchResult> {
        val contributorRef = Ref<SymbolSearchEverywhereContributor>()
        ApplicationManager.getApplication().invokeAndWait {
            val context = SimpleDataContext.getProjectContext(project)
            val event = AnActionEvent.createEvent(
                context,
                null,
                "keyboard shortcut",
                ActionUiKind.NONE,
                null
            )
            val contributor = SymbolSearchEverywhereContributor(event)
            if (!pattern.isEmpty()) {
                val actions = contributor.getActions {}
                val everywhereAction = ContainerUtil.find(
                    actions
                ) { o: AnAction? -> o is SearchEverywhereToggleAction } as SearchEverywhereToggleAction?
                everywhereAction!!.isEverywhere = true
            }
            contributorRef.set(contributor)
        }
        val ref = Ref<List<WorkspaceSearchResult>>()
        ApplicationManager.getApplication().runReadAction {
            ref.set(
                search(
                    contributorRef.get(),
                    if (pattern.isEmpty()) "*" else pattern,
                    cancelToken
                )
            )
        }
        return ref.get()
    }

    @JvmRecord
    data class WorkspaceSearchResult(
        val symbol: WorkspaceSymbol,
        val element: PsiElement,
        val weight: Int,
        val isProjectFile: Boolean
    )

    // Returns: the list of founded symbols
    // Note: Project symbols first then symbols from libraries, jdks, environments...
    fun search(
        contributor: SymbolSearchEverywhereContributor,
        pattern: String,
        cancelToken: CancelChecker?
    ): List<WorkspaceSearchResult> {
        val allSymbols = ArrayList<WorkspaceSearchResult>(LIMIT)
        val scope = ProjectScope.getProjectScope(project)
        val elements = HashSet<PsiElement>()
        val processedFiles = HashSet<PsiFile>()
        try {
            val indicator = WorkspaceSymbolIndicator(cancelToken)
            ApplicationManager.getApplication().executeOnPooledThread {
                contributor.fetchWeightedElements(
                    pattern, indicator
                ) { descriptor: FoundItemDescriptor<Any> ->
                    val elem = descriptor.item
                    if (elem !is PsiElement || elements.contains(elem)) {
                        return@fetchWeightedElements true
                    }
                    val searchResult =
                        toSearchResult(descriptor, scope)
                            ?: return@fetchWeightedElements true
                    elements.add(elem)
                    allSymbols.add(searchResult)

                    // Add Kotlin file symbol if we haven't processed this file yet
                    val psiFile = elem.getContainingFile()
                    if (psiFile != null && !processedFiles.contains(psiFile)) {
                        processedFiles.add(psiFile)
                        val virtualFile = psiFile.virtualFile
                        if (virtualFile != null && virtualFile.name.endsWith(".kt") && virtualFile.name == "DocumentSymbol.kt") {
                            val ktFileName = virtualFile.nameWithoutExtension + "Kt"
                            if (pattern == "*" || ktFileName.lowercase(Locale.getDefault())
                                    .contains(pattern.lowercase(Locale.getDefault()))
                            ) {
                                val ktFileSymbol = WorkspaceSymbol(
                                    ktFileName,
                                    SymbolKind.Object,
                                    Either.forLeft(
                                        Location(
                                            LspPath.fromVirtualFile(virtualFile).toLspUri(),
                                            Range(
                                                Position(0, 0),
                                                Position(
                                                    psiFile.text.split("\n".toRegex())
                                                        .dropLastWhile { it.isEmpty() }
                                                        .toTypedArray().size, 0))
                                        )),
                                    null)
                                allSymbols.add(
                                    WorkspaceSearchResult(
                                        ktFileSymbol,
                                        psiFile,
                                        0,
                                        scope.contains(virtualFile)
                                    )
                                )
                            }
                        }
                    }
                    allSymbols.size < LIMIT
                }
            }.get()
        } catch (ignored: InterruptedException) {
        } catch (ignored: ExecutionException) {
        }
        allSymbols.sortWith(COMP)
        return allSymbols
    }

    private class WorkspaceSymbolIndicator(private val cancelToken: CancelChecker?) :
        AbstractProgressIndicatorBase(), StandardProgressIndicator {
        override fun checkCanceled() {
            if (cancelToken != null) {
                try {
                    cancelToken.checkCanceled()
                } catch (e: CancellationException) {
                    throw ProcessCanceledException(e)
                }
            }
            super.checkCanceled()
        }
    }

    companion object {
        private val COMP: Comparator<WorkspaceSearchResult> = Comparator
            .comparingInt(WorkspaceSearchResult::weight).reversed()
            .thenComparing { a: WorkspaceSearchResult, b: WorkspaceSearchResult ->
                java.lang.Boolean.compare(
                    b.isProjectFile,
                    a.isProjectFile
                )
            }

        private fun toSearchResult(
            descriptor: FoundItemDescriptor<Any>,
            scope: SearchScope
        ): WorkspaceSearchResult? {
            val elem = descriptor.item
            if (elem !is PsiElement) {
                return null
            }
            if (elem !is NavigationItem) {
                return null
            }
            val itemPresentation: ItemPresentation = elem.getPresentation()
                ?: return null
            val psiFile: PsiFile = elem.getContainingFile() ?: return null
            val virtualFile = psiFile.virtualFile
            var containerName: String? = null
            val parent = elem.getParent()
            val grandParent = parent?.parent
            if (parent is PsiNameIdentifierOwner) {
                containerName = parent.getName()
            } else if (parent != null && grandParent is PsiNameIdentifierOwner
            ) {
                containerName = grandParent.getName()
            }
            val location = Location()
            var kind = getSymbolKind(itemPresentation)
            if (elem is PsiFile) {
                kind = SymbolKind.File
            }
            location.uri = LspPath.fromVirtualFile(virtualFile).toLspUri()
            val symbol = WorkspaceSymbol(
                itemPresentation.presentableText,
                kind,
                Either.forLeft<Location?, WorkspaceSymbolLocation>(
                    psiElementToLocation(elem, psiFile)
                ),
                containerName
            )
            return WorkspaceSearchResult(
                symbol,
                elem,
                descriptor.weight,
                scope.contains(virtualFile)
            )
        }
    }
}
