package org.rri.ideals.server.completions

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement

@JvmRecord
internal data class LookupElementWithMatcher(
    val lookupElement: LookupElement,
    val prefixMatcher: PrefixMatcher
)
