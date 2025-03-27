package org.rri.ideals.server.signature

import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ParameterInfoControllerBase.SignatureItem
import com.intellij.codeInsight.hint.ParameterInfoControllerBase.SignatureItemModel
import com.intellij.codeInsight.hint.ParameterInfoListener
import com.intellij.codeInsight.hint.ShowParameterInfoContext
import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.lang.Language
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Tuple
import org.jetbrains.annotations.TestOnly
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.util.LspProgressIndicator
import org.rri.ideals.server.util.MiscUtil.with
import org.rri.ideals.server.util.MiscUtil.wrap

@Service(Service.Level.PROJECT)
class SignatureHelpService(private val project: Project) : Disposable {
    private var flushRunnable: Runnable? = null

    override fun dispose() {
    }

    fun computeSignatureHelp(executorContext: ExecutorContext): SignatureHelp {
        LOG.info("start signature help")
        val editor = executorContext.editor
        val psiFile = executorContext.psiFile
        val offset = ReadAction.compute<Int, RuntimeException> { editor.caretModel.offset }
        val cancelChecker = checkNotNull(executorContext.cancelToken)
        val language = ReadAction.compute<Language, RuntimeException> {
            PsiUtilCore.getLanguageAtOffset(
                psiFile,
                offset
            )
        }
        // This assignment came from ShowParameterInfoHandler, IDEA 203.5981.155
        @Suppress("UNCHECKED_CAST") val handlers: Array<ParameterInfoHandler<PsiElement, Any>> =
            ShowParameterInfoHandler.getHandlers(
                project,
                language,
                psiFile.viewProvider.baseLanguage
            ) as Array<ParameterInfoHandler<PsiElement, Any>>

        val context = ShowParameterInfoContext(
            editor, project, psiFile, offset, -1, false, false
        )

        val isHandled = findAndUseValidHandler(handlers, context)
        if (!isHandled) {
            return with(
                SignatureHelp()
            ) { signatureHelp: SignatureHelp ->
                signatureHelp.signatures =
                    ArrayList()
            }
        }
        WriteAction.runAndWait<RuntimeException> {
            PsiDocumentManager.getInstance(
                project
            ).commitAllDocuments()
        }
        if (ApplicationManager.getApplication().isUnitTestMode && flushRunnable != null) {
            flushRunnable!!.run()
        }
        return ProgressManager.getInstance().runProcess<SignatureHelp>(
            { createSignatureHelpFromListener() }, LspProgressIndicator(
                cancelChecker
            )
        )
    }

    @TestOnly
    fun setEdtFlushRunnable(runnable: Runnable) {
        this.flushRunnable = runnable
    }

    companion object {
        private val LOG = Logger.getInstance(
            SignatureHelpService::class.java
        )

        private fun findAndUseValidHandler(
            handlers: Array<ParameterInfoHandler<PsiElement, Any>>,
            context: ShowParameterInfoContext
        ): Boolean {
            return ReadAction.compute<Boolean, RuntimeException> {
                for (handler in handlers) {
                    val element = handler.findElementForParameterInfo(context)
                    if (element != null && element.isValid) {
                        handler.showParameterInfo(element, context)
                        return@compute true
                    }
                }
                false
            }
        }

        private fun createSignatureHelpFromListener(): SignatureHelp {
            val ans = SignatureHelp()
            for (listener in ParameterInfoListener.EP_NAME.extensionList) {
                if (listener is MyParameterInfoListener) {
                    val model: ParameterInfoControllerBase.Model
                    try {
                        model = listener.queue.take()
                    } catch (e: InterruptedException) {
                        throw wrap(e)
                    }
                    ans.signatures =
                        model.signatures.stream()
                            .map { signatureIdeaItemModel: SignatureItemModel ->
                                val signatureItem = signatureIdeaItemModel as SignatureItem
                                val signatureInformation = SignatureInformation()
                                val parametersInformation = ArrayList<ParameterInformation>()
                                for (i in signatureItem.startOffsets.indices) {
                                    val startOffset = signatureItem.startOffsets[i]
                                    val endOffset = signatureItem.endOffsets[i]
                                    parametersInformation.add(
                                        with(
                                            ParameterInformation()
                                        ) { parameterInformation: ParameterInformation ->
                                            parameterInformation.setLabel(
                                                Tuple.two(
                                                    startOffset,
                                                    endOffset
                                                )
                                            )
                                        })
                                }
                                signatureInformation.parameters = parametersInformation
                                signatureInformation.activeParameter =
                                    if (model.current == -1) null else model.current
                                signatureInformation.label = signatureItem.text
                                signatureInformation
                            }.toList()
                    ans.activeSignature =
                        if (model.highlightedSignature == -1) null else model.highlightedSignature
                    break
                }
            }
            return ans
        }
    }
}
