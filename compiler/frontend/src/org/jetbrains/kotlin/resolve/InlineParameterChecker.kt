/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter

object InlineParameterChecker : DeclarationChecker {
    override fun check(
            declaration: KtDeclaration, descriptor: DeclarationDescriptor, diagnosticHolder: DiagnosticSink, bindingContext: BindingContext
    ) {
        if (declaration is KtFunction) {
            val inline = declaration.hasModifier(KtTokens.INLINE_KEYWORD)
            for (parameter in declaration.valueParameters) {
                val parameterDescriptor = bindingContext.get(BindingContext.VALUE_PARAMETER, parameter)
                if (!inline || (parameterDescriptor != null && !parameterDescriptor.type.isFunctionType)) {
                    parameter.reportIncorrectInline(KtTokens.NOINLINE_KEYWORD, diagnosticHolder)
                    parameter.reportIncorrectInline(KtTokens.CROSSINLINE_KEYWORD, diagnosticHolder)
                }
            }
        }
    }

    private fun KtParameter.reportIncorrectInline(modifierToken: KtModifierKeywordToken, diagnosticHolder: DiagnosticSink) {
        val modifier = modifierList?.getModifier(modifierToken)
        modifier?.let {
            diagnosticHolder.report(Errors.ILLEGAL_INLINE_PARAMETER_MODIFIER.on(modifier, modifierToken))
        }
    }
}
