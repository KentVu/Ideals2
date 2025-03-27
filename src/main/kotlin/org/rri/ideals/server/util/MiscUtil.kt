package org.rri.ideals.server.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.Segment
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.rri.ideals.server.LspPath
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.stream.Stream

object MiscUtil {
    private val LOG = Logger.getInstance(
        MiscUtil::class.java
    )

    fun <T> with(`object`: T, block: Consumer<T>): T {
        block.accept(`object`)
        return `object`
    }

    fun offsetToPosition(doc: Document, offset: Int): Position {
        if (offset == -1) {
            return Position(0, 0)
        }
        val line = doc.getLineNumber(offset)
        val lineStartOffset = doc.getLineStartOffset(line)
        val column = offset - lineStartOffset
        return Position(line, column)
    }

    fun resolvePsiFile(project: Project, path: LspPath): PsiFile? {
        val result = Ref<PsiFile>()
        invokeWithPsiFileInReadAction(
            project, path
        ) { value: PsiFile -> result.set(value) }
        return result.get()
    }

    fun <T> produceWithPsiFileInReadAction(
        project: Project,
        path: LspPath,
        block: Function<PsiFile, T>
    ): T? {
        val virtualFile = path.findVirtualFile()

        if (virtualFile == null) {
            LOG.info("File not found: $path")
            return null
        }

        return ApplicationManager.getApplication().runReadAction(Computable {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) {
                LOG.info("Unable to get PSI for virtual file: $virtualFile")
                return@Computable null
            }
            block.apply(psiFile)
        })
    }

    fun invokeWithPsiFileInReadAction(project: Project, path: LspPath, block: Consumer<PsiFile>) {
        produceWithPsiFileInReadAction<Any?>(
            project, path
        ) { psiFile: PsiFile ->
            block.accept(psiFile)
            null
        }
    }

    fun <T> computeInEDTAndWait(action: Supplier<T>): T {
        val ref = AtomicReference<T>()

        ApplicationManager.getApplication().invokeAndWait {
            ref.set(action.get())
        }

        return ref.get()
    }

    fun getDocument(file: PsiFile): Document? {
        val virtualFile = file.virtualFile ?: return file.viewProvider.document

        var doc = FileDocumentManager.getInstance().getDocument(virtualFile)

        if (doc == null) {
            FileDocumentManagerImpl.registerDocument(
                DocumentImpl(file.viewProvider.contents),
                virtualFile
            )
            doc = FileDocumentManager.getInstance()
                .getDocument(virtualFile)
        }

        return doc
    }

    fun asWriteAction(action: Runnable): Runnable {
        return Runnable { ApplicationManager.getApplication().runWriteAction(action) }
    }

    fun wrap(e: Throwable): RuntimeException {
        return if (e is RuntimeException) e else RuntimeException(e)
    }

    fun asRunnable(action: RunnableWithException): Runnable {
        return Runnable {
            try {
                action.run()
            } catch (e: Exception) {
                throw wrap(e)
            }
        }
    }

    fun <T> makeThrowsUnchecked(block: Callable<T>): T {
        try {
            return block.call()
        } catch (e: Exception) {
            throw wrap(e)
        }
    }

    fun psiElementToLocationLink(
        targetElem: PsiElement,
        doc: Document?,
        originalRange: Range?
    ): LocationLink? {
        if (doc == null) {
            return null
        }
        val range = getPsiElementRange(doc, targetElem)
        val uri = LspPath.fromVirtualFile(targetElem.containingFile.virtualFile).toLspUri()
        return if (range != null) LocationLink(uri, range, range, originalRange) else null
    }

    fun psiElementToLocation(elem: PsiElement?): Location? {
        if (elem == null) {
            return null
        }
        val file = elem.containingFile
        return psiElementToLocation(elem, file)
    }

    fun psiElementToLocation(elem: PsiElement?, file: PsiFile): Location? {
        val doc = getDocument(file) ?: return null
        val uri = LspPath.fromVirtualFile(file.virtualFile).toLspUri()
        val range = getPsiElementRange(doc, elem)
        return if (range != null) Location(uri, range) else null
    }

    fun getPsiElementRange(doc: Document, elem: PsiElement?): Range? {
        var range: TextRange? = null
        if (elem == null) {
            return null
        }
        if (elem is PsiNameIdentifierOwner) {
            val identifier = elem.nameIdentifier
            if (identifier != null) {
                range = identifier.textRange
            }
        }
        if (range == null) {
            range = elem.textRange
        }
        return if (range != null) getRange(doc, range) else null
    }

    fun getRange(doc: Document, segment: Segment): Range {
        return Range(
            offsetToPosition(doc, segment.startOffset),
            offsetToPosition(doc, segment.endOffset)
        )
    }

    fun positionToOffset(doc: Document, pos: Position): Int {
        return doc.getLineStartOffset(pos.line) + pos.character
    }

    fun <T> streamOf(array: Array<T>?): Stream<T> {
        return if (array != null) Arrays.stream(array) else Stream.empty()
    }

    fun <T> toConsumer(block: ThrowingConsumer<T>): Consumer<T> {
        return Consumer { t: T ->
            try {
                block.accept(t)
            } catch (e: Exception) {
                throw wrap(e)
            }
        }
    }

    fun <T> toSupplier(block: ThrowingSupplier<T>): Supplier<T> {
        return Supplier {
            try {
                return@Supplier block.get()
            } catch (e: Exception) {
                throw wrap(e)
            }
        }
    }

    fun <T> uncheckExceptions(block: ThrowingSupplier<T>): T {
        return toSupplier(block).get()
    }

    fun <T> distinctByKey(
        keyExtractor: Function<in T, *>
    ): Predicate<T> {
        val seen: MutableMap<Any, Boolean?> = ConcurrentHashMap()
        return Predicate { t: T ->
            seen.putIfAbsent(
                keyExtractor.apply(t),
                java.lang.Boolean.TRUE
            ) == null
        }
    }

    interface RunnableWithException {
        @Throws(Exception::class)
        fun run()
    }

    interface ThrowingConsumer<T> {
        @Throws(Exception::class)
        fun accept(t: T)
    }

    interface ThrowingSupplier<T> {
        @Throws(Exception::class)
        fun get(): T
    }
}
