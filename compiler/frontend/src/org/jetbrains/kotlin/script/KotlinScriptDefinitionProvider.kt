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

package org.jetbrains.kotlin.script

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly
import java.util.*

class KotlinScriptDefinitionProvider {
    private val definitions = ArrayList<KotlinScriptDefinition>()

    init {
        definitions.add(StandardScriptDefinition)
    }

    fun findScriptDefinition(file: VirtualFile): KotlinScriptDefinition? = definitions.firstOrNull { it.isScript(file) }

    fun findScriptDefinition(psiFile: PsiFile): KotlinScriptDefinition? = definitions.firstOrNull { it.isScript(psiFile) }

    fun isScript(file: VirtualFile): Boolean = findScriptDefinition(file) != null

    fun isScript(psiFile: PsiFile): Boolean = findScriptDefinition(psiFile) != null

    @TestOnly
    fun addScriptDefinition(scriptDefinition: KotlinScriptDefinition) {
        definitions.add(0, scriptDefinition)
    }

    fun setScriptDefinitions(definitions: List<KotlinScriptDefinition>) {
        this.definitions.clear()
        this.definitions.addAll(definitions)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): KotlinScriptDefinitionProvider =
                ServiceManager.getService(project, KotlinScriptDefinitionProvider::class.java)
    }
}
