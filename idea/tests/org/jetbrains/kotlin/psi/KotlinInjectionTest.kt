/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.psi

import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.injection.Injectable
import com.intellij.testFramework.LightProjectDescriptor
import junit.framework.TestCase
import org.intellij.lang.regexp.RegExpLanguage
import org.intellij.plugins.intelliLang.Configuration
import org.intellij.plugins.intelliLang.inject.InjectLanguageAction
import org.intellij.plugins.intelliLang.inject.UnInjectLanguageAction
import org.intellij.plugins.intelliLang.inject.config.BaseInjection
import org.intellij.plugins.intelliLang.inject.config.InjectionPlace
import org.intellij.plugins.intelliLang.references.FileReferenceInjector
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinLightProjectDescriptor

class KotlinInjectionTest : KotlinLightCodeInsightFixtureTestCase() {
    fun testInjectUnInjectOnSimpleString() {
        myFixture.configureByText("test.kt",
                                  """val test = "<caret>simple" """)
        TestCase.assertTrue(InjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))
        TestCase.assertFalse(UnInjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))

        InjectLanguageAction.invokeImpl(project, myFixture.editor, myFixture.file, FileReferenceInjector())
        TestCase.assertTrue(myFixture.getReferenceAtCaretPosition() is FileReference)

        TestCase.assertFalse(InjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))
        TestCase.assertTrue(UnInjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))

        UnInjectLanguageAction.invokeImpl(project, myFixture.editor, myFixture.file)
        TestCase.assertNull(myFixture.getReferenceAtCaretPosition())
    }

    fun testInjectionOnJavaPredefinedMethodWithAnnotation() = testInjectionPresent(
            """
            |val test1 = java.util.regex.Pattern.compile("<caret>pattern")
            """,
            RegExpLanguage.INSTANCE.id,
            unInjectShouldBePresent = false
    )

    fun testInjectionOnJavaCustomInjectionWithAnnotation() {
        val customInjection = BaseInjection("java")
        customInjection.injectedLanguageId = HTMLLanguage.INSTANCE.id
        val elementPattern = customInjection.compiler.createElementPattern(
                """psiParameter().ofMethod(2, psiMethod().withName("replace").withParameters("int", "int", "java.lang.String").definedInClass("java.lang.StringBuilder"))""",
                "HTML temp rule")
        customInjection.setInjectionPlaces(InjectionPlace(elementPattern, true))

        try {
            Configuration.getInstance().replaceInjections(listOf(customInjection), listOf(), true)

            testInjectionPresent(
                    """
                    |val stringBuilder = StringBuilder().replace(0, 0, "<caret><html></html>")
                    """,
                    HTMLLanguage.INSTANCE.id,
                    unInjectShouldBePresent = false
            )
        }
        finally {
            Configuration.getInstance().replaceInjections(listOf(), listOf(customInjection), true)
        }
    }

    fun testInjectionWithCommentOnProperty() = testInjectionPresent(
            """
            |//language=file-reference
            |val test = "<caret>simple"
            """)

    fun testInjectionWithMultipleCommentsOnFun() = testInjectionPresent(
            """
            |// Some comment
            |// Other comment
            |//language=file-reference
            |fun test() = "<caret>simple"
            """)

    fun testInjectionWithAnnotationOnPropertyWithAnnotation() = testInjectionPresent(
            """
            |@org.intellij.lang.annotations.Language("file-reference")
            |val test = "<caret>simple"
            """)

    fun testInjectWithCommentOnProperty() = doFileReferenceInjectTest(
            """
            |val test = "<caret>simple"
            """,
            """
            |//language=file-reference
            |val test = "simple"
            """
    )

    fun testInjectWithCommentOnCommentedProperty() = doFileReferenceInjectTest(
            """
            |// Hello
            |val test = "<caret>simple"
            """,
            """
            |// Hello
            |//language=file-reference
            |val test = "simple"
            """
    )

    fun testInjectWithCommentOnPropertyWithKDoc() = doFileReferenceInjectTest(
            """
            |/**
            | * Hi
            | */
            |val test = "<caret>simple"
            """,
            """
            |/**
            | * Hi
            | */
            |//language=file-reference
            |val test = "<caret>simple"
            """
    )

    fun testInjectWithCommentOnExpression() = doFileReferenceInjectTest(
            """
            |fun test() {
            |    "<caret>"
            |}
            """,
            """
            |fun test() {
            |    //language=file-reference
            |    "<caret>"
            |}
            """
    )

    fun testInjectWithCommentOnDeepExpression() = doFileReferenceInjectTest(
            """
            |fun test() {
            |    "" + "<caret>"
            |}
            """,
            """
            |fun test() {
            |    "" + "<caret>"
            |}
            """
    )

    fun testInjectOnPropertyWithAnnotation() = doFileReferenceInjectTest(
            """
            |val test = "<caret>simple"
            """,
            """
            |import org.intellij.lang.annotations.Language
            |
            |@Language("file-reference")
            |val test = "simple"
            """
    )

    fun testInjectWithOnExpressionWithAnnotation() = doFileReferenceInjectTest(
            """
            |fun test() {
            |    "<caret>"
            |}
            """,
            """
            |fun test() {
            |    //language=file-reference
            |    "<caret>"
            |}
            """
    )

    // TODO: add test for non-default language annotation

    fun testRemoveInjectionWithAnnotation() = doRemoveInjectionTest(
            """
            |import org.intellij.lang.annotations.Language
            |
            |@Language("file-reference")
            |val test = "<caret>simple"
            """,
            """
            |import org.intellij.lang.annotations.Language
            |
            |val test = "simple"
            """
    )

    // TODO: Doesn't work. UnInjectionLanguageAction is not enabled because of absent LanguageInjectionSupport.INJECTOR_SUPPORT user data.
//    fun testRemoveInjectionFromOneLineFunWithAnnotation() = doRemoveInjectionTest(
//            """
//            |import org.intellij.lang.annotations.Language
//            |
//            |@Language("HTML") fun template(): String = "<caret><html></html>"
//            """,
//            """
//            |import org.intellij.lang.annotations.Language
//            |
//            |fun template(): String = "<caret><html></html>"
//            """
//    )

//    fun testRemoveInjectionOnQualifiedNameWithAnnotation() = doRemoveInjectionTest(
//            """
//            |import org.intellij.lang.annotations.Language
//            |
//            |@Language("RegExp")
//            |val s = java.util.regex.Pattern.compile("Hi")
//            """,
//            """
//            |val test1 = java.util.regex.Pattern.compile("Hi")
//            """
//    )

    fun testRemoveInjectionWithComment() = doRemoveInjectionTest(
            """
            |//language=file-reference
            |val test = "<caret>simple"
            """,
            """
            |val test = "simple"
            """
    )

    fun testRemoveInjectionWithCommentNotFirst() = doRemoveInjectionTest(
            """
            |// Some comment. To do a language injection, add a line comment language=some instruction.
            |//   language=file-reference
            |val test = "<caret>simple"
            """,
            """
            |// Some comment. To do a language injection, add a line comment language=some instruction.
            |val test = "simple"
            """
    )

    fun testRemoveInjectionWithCommentAfterKDoc() = doRemoveInjectionTest(
            """
            |/**Property*/
            |// language=file-reference
            |val test = "<caret>simple"
            """,
            """
            |/**Property*/
            |val test = "simple"
            """
    )

    fun testRemoveInjectionWithCommentInExpression() = doRemoveInjectionTest(
            """
            |fun test() {
            |    // This is my favorite part
            |    // language=RegExp
            |    "<caret>something"
            |}
            """,
            """
            |fun test() {
            |    // This is my favorite part
            |    "something"
            |}
            """
    )

    override fun getProjectDescriptor(): LightProjectDescriptor {
        if (getTestName(true).endsWith("WithAnnotation")) {
            return KotlinLightProjectDescriptor.INSTANCE
        }

        return JAVA_LATEST
    }

    private fun testInjectionPresent(text: String, languageId: String? = null, unInjectShouldBePresent: Boolean = true) {
        myFixture.configureByText("${getTestName(true)}.kt", text.trimMargin())

        TestCase.assertFalse("Injection action is available. There's probably no injection at caret place",
                             InjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))

        if (languageId != null) {
            val injectedFile = (editor as? EditorWindow)?.injectedFile
            assertEquals("Wrong injection language", languageId, injectedFile?.language?.id)
        }

        if (unInjectShouldBePresent) {
            TestCase.assertTrue("UnInjection action is not available. There's no injection at caret place or some other troubles.",
                                UnInjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))
        }
    }

    private fun doRemoveInjectionTest(before: String, after: String) {
        myFixture.setCaresAboutInjection(false)

        myFixture.configureByText("${getTestName(true)}.kt", before.trimMargin())

        TestCase.assertTrue(UnInjectLanguageAction().isAvailable(project, myFixture.editor, myFixture.file))
        UnInjectLanguageAction.invokeImpl(project, myFixture.editor, myFixture.file)

        myFixture.checkResult(after.trimMargin())
    }

    private fun doFileReferenceInjectTest(before: String, after: String) {
        doTest(FileReferenceInjector(), before, after)
    }

    private fun doTest(injectable: Injectable, before: String, after: String) {
        val configuration = Configuration.getProjectInstance(project).advancedConfiguration
        val allowed = configuration.isSourceModificationAllowed

        configuration.isSourceModificationAllowed = true
        try {
            myFixture.configureByText("${getTestName(true)}.kt", before.trimMargin())
            InjectLanguageAction.invokeImpl(project, myFixture.editor, myFixture.file, injectable)
            myFixture.checkResult(after.trimMargin())
        }
        finally {
            configuration.isSourceModificationAllowed = allowed
        }
    }
}