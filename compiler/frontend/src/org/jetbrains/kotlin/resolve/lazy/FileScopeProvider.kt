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

package org.jetbrains.kotlin.resolve.lazy

import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.kotlin.resolve.scopes.LexicalScope

abstract class FileScopeProvider {
    fun getFileResolutionScope(file: KtFile): LexicalScope = getFileScopes(file).lexicalScope
    fun getImportResolver(file: KtFile): ImportResolver = getFileScopes(file).importResolver

    abstract fun getFileScopes(file: KtFile): FileScopes

    object ThrowException : FileScopeProvider() {
        override fun getFileScopes(file: KtFile) = throw UnsupportedOperationException("Should not be called")
    }
}

class FileScopeProviderImpl(private val fileScopeFactory: FileScopeFactory) : FileScopeProvider() {
    override fun getFileScopes(file: KtFile) = file.customFileScopes ?: fileScopeFactory.createScopesForFile(file)
}

var KtFile.customFileScopes: FileScopes? by UserDataProperty(Key.create("CUSTOM_FILE_SCOPES"))