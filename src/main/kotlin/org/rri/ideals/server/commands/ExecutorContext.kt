package org.rri.ideals.server.commands

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.jsonrpc.CancelChecker

class ExecutorContext(val psiFile: PsiFile, val editor: Editor, val cancelToken: CancelChecker?)
