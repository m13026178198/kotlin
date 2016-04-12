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

package org.jetbrains.kotlin.console

import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.console.ConsoleExecuteAction
import com.intellij.execution.console.LanguageConsoleBuilder
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.console.ProcessBackedConsoleExecuteActionHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.AbstractConsoleRunnerWithHistory
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.console.actions.BuildAndRestartConsoleAction
import org.jetbrains.kotlin.console.actions.KtExecuteCommandAction
import org.jetbrains.kotlin.console.gutter.ConsoleGutterContentProvider
import org.jetbrains.kotlin.console.gutter.ConsoleIndicatorRenderer
import org.jetbrains.kotlin.console.gutter.IconWithTooltip
import org.jetbrains.kotlin.console.gutter.ReplIcons
import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.ModuleTestSourceInfo
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.moduleInfo
import org.jetbrains.kotlin.resolve.lazy.*
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyScriptDescriptor
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.resolve.scopes.utils.replaceImportingScopes
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptDefinitionProvider
import org.jetbrains.kotlin.script.ScriptParameter
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import java.awt.Color
import java.awt.Font
import kotlin.properties.Delegates

private val KOTLIN_SHELL_EXECUTE_ACTION_ID = "KotlinShellExecute"

class KotlinConsoleRunner(
        val module: Module,
        private val cmdLine: GeneralCommandLine,
        internal val previousCompilationFailed: Boolean,
        myProject: Project,
        title: String,
        path: String?
) : AbstractConsoleRunnerWithHistory<LanguageConsoleView>(myProject, title, path) {

    private val replState = ReplState()

    override fun finishConsole() {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            // Ignore super with myConsoleView.setEditable(false)
            return
        }

        super.finishConsole()
    }

    val commandHistory = CommandHistory()

    var isReadLineMode: Boolean = false
        set(value) {
            if (value)
                changeConsoleEditorIndicator(ReplIcons.EDITOR_READLINE_INDICATOR)
            else
                changeConsoleEditorIndicator(ReplIcons.EDITOR_INDICATOR)

            field = value
        }

    fun changeConsoleEditorIndicator(newIconWithTooltip: IconWithTooltip) = WriteCommandAction.runWriteCommandAction(project) {
        consoleEditorHighlighter.gutterIconRenderer = ConsoleIndicatorRenderer(newIconWithTooltip)
    }

    private var consoleEditorHighlighter by Delegates.notNull<RangeHighlighter>()
    private var disposableDescriptor by Delegates.notNull<RunContentDescriptor>()

    val executor = CommandExecutor(this)
    var compilerHelper: ConsoleCompilerHelper by Delegates.notNull()

    override fun createProcess() = cmdLine.createProcess()

    override fun createConsoleView(): LanguageConsoleView? {
        val builder = LanguageConsoleBuilder()

        val consoleView = builder.gutterContentProvider(ConsoleGutterContentProvider())
                .psiFileFactory { virtualFile, project -> createScriptFile(virtualFile, project) }
                .build(project, KotlinLanguage.INSTANCE)


        KotlinScriptDefinitionProvider.getInstance(project).addScriptDefinition(object : KotlinScriptDefinition {
            override fun isScript(file: PsiFile) = file.originalFile.virtualFile == consoleView.virtualFile
            override fun getScriptParameters(scriptDescriptor: ScriptDescriptor) = emptyList<ScriptParameter>()
            override fun getScriptName(script: KtScript) = Name.identifier("REPL")
        })

        consoleView.prompt = null

        disableCompletion(consoleView)

        val consoleEditor = consoleView.consoleEditor

        setupPlaceholder(consoleEditor)
        val historyKeyListener = HistoryKeyListener(module.project, consoleEditor, commandHistory)
        consoleEditor.contentComponent.addKeyListener(historyKeyListener)
        commandHistory.listeners.add(historyKeyListener)

        val executeAction = KtExecuteCommandAction(consoleView.virtualFile)
        executeAction.registerCustomShortcutSet(CommonShortcuts.CTRL_ENTER, consoleView.consoleEditor.component)

        return consoleView
    }

    override fun createProcessHandler(process: Process): OSProcessHandler {
        val processHandler = ReplOutputHandler(
                this,
                process,
                cmdLine.commandLineString
        )
        val consoleFile = consoleView.virtualFile
        val keeper = KotlinConsoleKeeper.getInstance(project)

        keeper.putVirtualFileToConsole(consoleFile, this)
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                keeper.removeConsole(consoleFile)
            }
        })

        return processHandler
    }

    override fun createExecuteActionHandler() = object : ProcessBackedConsoleExecuteActionHandler(processHandler, false) {
        override fun runExecuteAction(consoleView: LanguageConsoleView) = executor.executeCommand()
    }

    override fun fillToolBarActions(toolbarActions: DefaultActionGroup,
                                    defaultExecutor: Executor,
                                    contentDescriptor: RunContentDescriptor
    ): List<AnAction> {
        disposableDescriptor = contentDescriptor
        compilerHelper = ConsoleCompilerHelper(project, module, defaultExecutor, contentDescriptor)

        val actionList = arrayListOf<AnAction>(
                BuildAndRestartConsoleAction(this),
                createConsoleExecAction(consoleExecuteActionHandler),
                createCloseAction(defaultExecutor, contentDescriptor)
        )
        toolbarActions.addAll(actionList)
        return actionList
    }

    override fun createConsoleExecAction(consoleExecuteActionHandler: ProcessBackedConsoleExecuteActionHandler)
            = ConsoleExecuteAction(consoleView, consoleExecuteActionHandler, KOTLIN_SHELL_EXECUTE_ACTION_ID, consoleExecuteActionHandler)

    override fun constructConsoleTitle(title: String) = "$title (in module ${module.name})"

    private fun setupPlaceholder(editor: EditorEx) {
        val executeCommandAction = ActionManager.getInstance().getAction(KOTLIN_SHELL_EXECUTE_ACTION_ID)
        val executeCommandActionShortcutText = KeymapUtil.getFirstKeyboardShortcutText(executeCommandAction)

        editor.setPlaceholder("<$executeCommandActionShortcutText> to execute")
        editor.setShowPlaceholderWhenFocused(true)

        val placeholderAttrs = TextAttributes()
        placeholderAttrs.foregroundColor = ReplColors.PLACEHOLDER_COLOR
        placeholderAttrs.fontType = Font.ITALIC
        editor.setPlaceholderAttributes(placeholderAttrs)
    }

    private fun disableCompletion(consoleView: LanguageConsoleView) {
        val consoleFile = consoleView.virtualFile
        val jetFile = PsiManager.getInstance(project).findFile(consoleFile) as? KtFile ?: return
        jetFile.moduleInfo = ModuleTestSourceInfo(module)
    }

    fun setupGutters() {
        fun configureEditorGutter(editor: EditorEx, color: Color, iconWithTooltip: IconWithTooltip): RangeHighlighter {
            editor.settings.isLineMarkerAreaShown = true // hack to show gutter
            editor.settings.isFoldingOutlineShown = true
            editor.gutterComponentEx.setPaintBackground(true)
            val editorColorScheme = editor.colorsScheme
            editorColorScheme.setColor(EditorColors.GUTTER_BACKGROUND, color)
            editor.colorsScheme = editorColorScheme

            return addGutterIndicator(editor, iconWithTooltip)
        }

        val historyEditor = consoleView.historyViewer
        val consoleEditor = consoleView.consoleEditor

        configureEditorGutter(historyEditor, ReplColors.HISTORY_GUTTER_COLOR, ReplIcons.HISTORY_INDICATOR)
        consoleEditorHighlighter = configureEditorGutter(consoleEditor, ReplColors.EDITOR_GUTTER_COLOR, ReplIcons.EDITOR_INDICATOR)

        historyEditor.settings.isUseSoftWraps = true
        historyEditor.settings.additionalLinesCount = 0

        consoleEditor.settings.isCaretRowShown = true
        consoleEditor.settings.additionalLinesCount = 2
    }

    fun addGutterIndicator(editor: EditorEx, iconWithTooltip: IconWithTooltip): RangeHighlighter {
        val indicator = ConsoleIndicatorRenderer(iconWithTooltip)
        val editorMarkup = editor.markupModel
        val indicatorHighlighter = editorMarkup.addRangeHighlighter(
                0, editor.document.textLength, HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE
        )

        return indicatorHighlighter.apply { gutterIconRenderer = indicator }
    }

    @TestOnly fun dispose() {
        processHandler.destroyProcess()
        Disposer.dispose(disposableDescriptor)
    }

    fun successfulLine(text: String) {
        runReadAction {
            val lineNumber = replState.successfulLinesCount + 1
            val virtualFile =
                    LightVirtualFile("line$lineNumber${KotlinParserDefinition.STD_SCRIPT_EXT}", KotlinLanguage.INSTANCE, text).apply {
                        charset = CharsetToolkit.UTF8_CHARSET
                    }
            val psiFile = (PsiFileFactory.getInstance(project) as PsiFileFactoryImpl).trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile?
                          ?: error("Script file not analyzed at line $lineNumber: $text")
            //TODO_R:
            replState.submitLine(psiFile)
            psiFile.moduleInfo = ModuleTestSourceInfo(module)
            val scriptDescriptor = psiFile.script!!.resolveToDescriptor() as LazyScriptDescriptor
            ForceResolveUtil.forceResolveAllContents(scriptDescriptor)
            replState.lineSuccess(psiFile, scriptDescriptor)
            val consoleFile = consoleView.virtualFile
            val jetFile = PsiManager.getInstance(project).findFile(consoleFile) as? KtFile ?: return
            replState.submitLine(jetFile)

        }
    }
}

fun createScriptFile(virtualFile: VirtualFile, project: Project): PsiFile {
    val singleRootFileViewProvider = SingleRootFileViewProvider(PsiManager.getInstance(project), virtualFile)
    val scriptFile = KtFile(singleRootFileViewProvider, false)
    KotlinScriptDefinitionProvider.getInstance(project).addScriptDefinition(object : KotlinScriptDefinition {
        override fun isScript(file: PsiFile) = scriptFile == file
        override fun getScriptParameters(scriptDescriptor: ScriptDescriptor) = emptyList<ScriptParameter>()
        override fun getScriptName(script: KtScript) = Name.identifier("REPL")
    })
    return scriptFile
}

class ReplState {
    private val lines = hashMapOf<KtFile, LineInfo>()
    private val successfulLines = arrayListOf<SuccessfulLine>()

    val successfulLinesCount: Int
        get() = successfulLines.size

    fun submitLine(ktFile: KtFile) {
        val line = SubmittedLine(ktFile, successfulLines.lastOrNull())
        lines[ktFile] = line
        ktFile.fileScopesMutator = object : FileScopesMutator {
            override fun createFileScopes(fileScopeFactory: FileScopeFactory): FileScopes {
                return lineInfo(ktFile)?.computeFileScopes(fileScopeFactory) ?: fileScopeFactory.createScopesForFile(ktFile)
            }
        }
    }

    fun lineSuccess(ktFile: KtFile, scriptDescriptor: LazyScriptDescriptor) {
        val successfulLine = SuccessfulLine(ktFile, successfulLines.lastOrNull(), scriptDescriptor)
        lines[ktFile] = successfulLine
        successfulLines.add(successfulLine)
    }

    fun lineFailure(ktFile: KtFile) {
        lines[ktFile] = FailedLine(ktFile, successfulLines.lastOrNull())
    }

    fun lineInfo(ktFile: KtFile) = lines[ktFile]

    inner abstract class LineInfo {
        abstract val linePsi: KtFile
        abstract val parentLine: SuccessfulLine?

        fun computeFileScopes(fileScopeFactory: FileScopeFactory): FileScopes {
            // create scope that wraps previous line lexical scope and adds imports from this line
            val lexicalScopeAfterLastLine = parentLine?.lineDescriptor?.scopeForInitializerResolution
                                            ?: return fileScopeFactory.createScopesForFile(linePsi)
            val lastLineImports = lexicalScopeAfterLastLine.parentsWithSelf.firstIsInstance<ImportingScope>()
            val scopesForThisLine = fileScopeFactory.createScopesForFile(linePsi, lastLineImports)
            val combinedLexicalScopes = lexicalScopeAfterLastLine.replaceImportingScopes(scopesForThisLine.importingScope)
            return FileScopes(combinedLexicalScopes, scopesForThisLine.importingScope, scopesForThisLine.importResolver)
        }
    }

    inner class SubmittedLine(override val linePsi: KtFile, override val parentLine: SuccessfulLine?) : LineInfo()
    inner class SuccessfulLine(override val linePsi: KtFile, override val parentLine: SuccessfulLine?, val lineDescriptor: LazyScriptDescriptor) : LineInfo()
    inner class FailedLine(override val linePsi: KtFile, override val parentLine: SuccessfulLine?) : LineInfo()
}
