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

import com.intellij.util.SmartList
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.CallExpressionElement
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.PackageQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.createClassifierQualifier
import org.jetbrains.kotlin.resolve.scopes.utils.containsFunctionOrVariable
import org.jetbrains.kotlin.resolve.scopes.utils.findClassifier
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.resolve.validation.SymbolUsageValidator
import org.jetbrains.kotlin.types.expressions.ExpressionTypingContext
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.check
import java.util.*

public class QualifiedExpressionResolver(val symbolUsageValidator: SymbolUsageValidator) {


    public fun resolvePackageHeader(
            packageDirective: KtPackageDirective,
            module: ModuleDescriptor,
            trace: BindingTrace
    ) {
        val packageNames = packageDirective.packageNames
        for ((index, nameExpression) in packageNames.withIndex()) {
            storeResult(trace, nameExpression, module.getPackage(packageDirective.getFqName(nameExpression)),
                        shouldBeVisibleFrom = null, inImport = false, isQualifier = index != packageNames.lastIndex)
        }
    }

    data class TypeQualifierResolutionResult(
            val qualifierParts: List<QualifierPart>,
            val classifierDescriptor: ClassifierDescriptor? = null
    ) {
        val allProjections: List<KtTypeProjection>
            get() = qualifierParts.flatMap { it.typeArguments?.arguments.orEmpty() }
    }

    public fun resolveDescriptorForType(
            userType: KtUserType,
            scope: LexicalScope,
            trace: BindingTrace
    ): TypeQualifierResolutionResult {
        if (userType.qualifier == null && !userType.startWithPackage) { // optimization for non-qualified types
            val descriptor = userType.referenceExpression?.let {
                val classifier = scope.findClassifier(it.getReferencedNameAsName(), KotlinLookupLocation(it))
                storeResult(trace, it, classifier, scope.ownerDescriptor, inImport = false, isQualifier = false)
                classifier
            }

            return TypeQualifierResolutionResult(userType.asQualifierPartList().first, descriptor)
        }

        val module = scope.ownerDescriptor.module
        val (qualifierPartList, hasError) = userType.asQualifierPartList()
        if (hasError) {
            val descriptor = resolveToPackageOrClass(
                    qualifierPartList, module, trace, scope.ownerDescriptor, scope, position = QualifierPosition.TYPE
            ) as? ClassifierDescriptor
            return TypeQualifierResolutionResult(qualifierPartList, descriptor)
        }
        assert(qualifierPartList.size() >= 1) {
            "Too short qualifier list for user type $userType : ${qualifierPartList.joinToString()}"
        }

        val qualifier = resolveToPackageOrClass(
                qualifierPartList.subList(0, qualifierPartList.size() - 1), module,
                trace, scope.ownerDescriptor, scope.check { !userType.startWithPackage }, position = QualifierPosition.TYPE
        ) ?: return TypeQualifierResolutionResult(qualifierPartList, null)

        val lastPart = qualifierPartList.last()
        val classifier = when (qualifier) {
            is PackageViewDescriptor -> qualifier.memberScope.getContributedClassifier(lastPart.name, lastPart.location)
            is ClassDescriptor -> qualifier.unsubstitutedInnerClassesScope.getContributedClassifier(lastPart.name, lastPart.location)
            else -> null
        }
        storeResult(trace, lastPart.expression, classifier, scope.ownerDescriptor, inImport = false, isQualifier = false)

        return TypeQualifierResolutionResult(qualifierPartList, classifier)
    }

    private val KtUserType.startWithPackage: Boolean
        get() {
            var firstPart = this
            while (firstPart.qualifier != null) {
                firstPart = firstPart.qualifier!!
            }
            return firstPart.isAbsoluteInRootPackage
        }


    private fun KtUserType.asQualifierPartList(): Pair<List<QualifierPart>, Boolean> {
        var hasError = false
        val result = SmartList<QualifierPart>()
        var userType: KtUserType? = this
        while (userType != null) {
            val referenceExpression = userType.referenceExpression
            if (referenceExpression != null) {
                result add QualifierPart(referenceExpression.getReferencedNameAsName(), referenceExpression, userType.typeArgumentList)
            }
            else {
                hasError = true
            }
            userType = userType.qualifier
        }
        return result.asReversed() to hasError
    }

    public fun processImportReference(
            importDirective: KtImportDirective,
            moduleDescriptor: ModuleDescriptor,
            trace: BindingTrace,
            packageFragmentForVisibilityCheck: PackageFragmentDescriptor?
    ): ImportingScope? { // null if some error happened
        val importedReference = importDirective.importedReference ?: return null
        val path = importedReference.asQualifierPartList(trace)
        val lastPart = path.lastOrNull() ?: return null
        val packageFragmentForCheck =
                if (packageFragmentForVisibilityCheck is DeclarationDescriptorWithSource && packageFragmentForVisibilityCheck.source == SourceElement.NO_SOURCE) {
                    PackageFragmentWithCustomSource(packageFragmentForVisibilityCheck, KotlinSourceElement(importDirective.getContainingKtFile()))
                }

                else {
                    packageFragmentForVisibilityCheck
                }

        if (!importDirective.isAllUnder) {
            return processSingleImport(moduleDescriptor, trace, importDirective, path, lastPart, packageFragmentForCheck)
        }
        val packageOrClassDescriptor = resolveToPackageOrClass(path, moduleDescriptor, trace, packageFragmentForCheck,
                                                               scopeForFirstPart = null, position = QualifierPosition.IMPORT) ?: return null
        if (packageOrClassDescriptor is ClassDescriptor && packageOrClassDescriptor.kind.isSingleton) {
            trace.report(Errors.CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON.on(lastPart.expression, packageOrClassDescriptor)) // todo report on star
        }
        return AllUnderImportsScope(packageOrClassDescriptor)
}

    private fun processSingleImport(
            moduleDescriptor: ModuleDescriptor, trace: BindingTrace, importDirective: KtImportDirective,
            path: List<QualifierPart>, lastPart: QualifierPart, packageFragmentForVisibilityCheck: PackageFragmentDescriptor?
    ): SingleImportScope? {
        val aliasName = KtPsiUtil.getAliasName(importDirective)
        if (aliasName == null) {
            // import kotlin.
            resolveToPackageOrClass(path, moduleDescriptor, trace, packageFragmentForVisibilityCheck, scopeForFirstPart = null, position = QualifierPosition.IMPORT)
            return null
        }

        val packageOrClassDescriptor = resolveToPackageOrClass(
                path.subList(0, path.size() - 1), moduleDescriptor, trace,
                packageFragmentForVisibilityCheck, scopeForFirstPart = null, position = QualifierPosition.IMPORT
        ) ?: return null

        val candidates = collectCandidateDescriptors(lastPart, packageOrClassDescriptor)
        if (candidates.isNotEmpty()) {
            storeResult(trace, lastPart.expression, candidates, packageFragmentForVisibilityCheck, inImport = true, isQualifier = false)
        }
        else {
            tryResolveDescriptorsWhichCannotBeImported(trace, moduleDescriptor, packageOrClassDescriptor, lastPart)
            return null
        }

        val importedDescriptors = candidates.filter { isVisible(it, packageFragmentForVisibilityCheck, inImport = true) }.
                check { it.isNotEmpty() } ?: candidates

        return SingleImportScope(aliasName, importedDescriptors)
    }

    private fun collectCandidateDescriptors(lastPart: QualifierPart, packageOrClassDescriptor: DeclarationDescriptor): SmartList<DeclarationDescriptor> {
        val descriptors = SmartList<DeclarationDescriptor>()

        val lastName = lastPart.name
        val location = lastPart.location
        when (packageOrClassDescriptor) {
            is PackageViewDescriptor -> {
                val packageScope = packageOrClassDescriptor.memberScope
                descriptors.addIfNotNull(packageScope.getContributedClassifier(lastName, location))
                descriptors.addAll(packageScope.getContributedVariables(lastName, location))
                descriptors.addAll(packageScope.getContributedFunctions(lastName, location))
            }

            is ClassDescriptor -> {
                descriptors.addIfNotNull(
                        packageOrClassDescriptor.unsubstitutedInnerClassesScope.getContributedClassifier(lastName, location)
                )
                val staticClassScope = packageOrClassDescriptor.staticScope
                descriptors.addAll(staticClassScope.getContributedFunctions(lastName, location))
                descriptors.addAll(staticClassScope.getContributedVariables(lastName, location))

                if (packageOrClassDescriptor.kind == ClassKind.OBJECT) {
                    descriptors.addAll(packageOrClassDescriptor.unsubstitutedMemberScope.getContributedFunctions(lastName, location).map {
                        FunctionImportedFromObject(it)
                    })
                    val properties = packageOrClassDescriptor.unsubstitutedMemberScope.getContributedVariables(lastName, location)
                            .filterIsInstance<PropertyDescriptor>().map { PropertyImportedFromObject(it) }
                    descriptors.addAll(properties)
                }
            }

            else -> throw IllegalStateException("Should be class or package: $packageOrClassDescriptor")
        }
        return descriptors
    }

    private fun tryResolveDescriptorsWhichCannotBeImported(
            trace: BindingTrace,
            moduleDescriptor: ModuleDescriptor,
            packageOrClassDescriptor: DeclarationDescriptor,
            lastPart: QualifierPart
    ) {
        val descriptors = SmartList<DeclarationDescriptor>()
        val lastName = lastPart.name
        when (packageOrClassDescriptor) {
            is PackageViewDescriptor -> {
                val packageDescriptor = moduleDescriptor.getPackage(packageOrClassDescriptor.fqName.child(lastName))
                if (!packageDescriptor.isEmpty()) {
                    trace.report(Errors.PACKAGE_CANNOT_BE_IMPORTED.on(lastPart.expression))
                    descriptors.add(packageOrClassDescriptor)
                }
            }

            is ClassDescriptor -> {
                val memberScope = packageOrClassDescriptor.unsubstitutedMemberScope
                descriptors.addAll(memberScope.getContributedFunctions(lastName, lastPart.location))
                descriptors.addAll(memberScope.getContributedVariables(lastName, lastPart.location))
                if (descriptors.isNotEmpty()) {
                    trace.report(Errors.CANNOT_BE_IMPORTED.on(lastPart.expression, lastName))
                }
            }

            else -> throw IllegalStateException("Should be class or package: $packageOrClassDescriptor")
        }
        storeResult(trace, lastPart.expression, descriptors, shouldBeVisibleFrom = null, inImport = true, isQualifier = false)
    }

    private fun KtExpression.asQualifierPartList(trace: BindingTrace): List<QualifierPart> {
        val result = SmartList<QualifierPart>()
        var expression: KtExpression? = this
        loop@ while (expression != null) {
            when (expression) {
                is KtSimpleNameExpression -> {
                    result add QualifierPart(expression.getReferencedNameAsName(), expression)
                    break@loop
                }
                is KtQualifiedExpression -> {
                    (expression.selectorExpression as? KtSimpleNameExpression)?.let {
                        result add QualifierPart(it.getReferencedNameAsName(), it)
                    }
                    expression = expression.receiverExpression
                    if (expression is KtSafeQualifiedExpression) {
                        trace.report(Errors.SAFE_CALL_IN_QUALIFIER.on(expression.operationTokenNode.psi))
                    }
                }
                else -> expression = null
            }
        }
        return result.asReversed()
    }

    data class QualifierPart(
            val name: Name,
            val expression: KtSimpleNameExpression,
            val typeArguments: KtTypeArgumentList? = null
    ) {
        constructor (expression: KtSimpleNameExpression) : this(expression.getReferencedNameAsName(), expression)

        val location = KotlinLookupLocation(expression)
    }

    private enum class QualifierPosition {
        IMPORT, TYPE, EXPRESSION
    }

    private fun resolveToPackageOrClass(
            path: List<QualifierPart>,
            moduleDescriptor: ModuleDescriptor,
            trace: BindingTrace,
            shouldBeVisibleFrom: DeclarationDescriptor?,
            scopeForFirstPart: LexicalScope?,
            position: QualifierPosition
    ): DeclarationDescriptor? {
        val (packageOrClassDescriptor, endIndex) =
                resolveToPackageOrClassPrefix(path, moduleDescriptor, trace, shouldBeVisibleFrom, scopeForFirstPart, position)

        if (endIndex != path.size) {
            return null
        }

        return packageOrClassDescriptor
    }

    private fun resolveToPackageOrClassPrefix(
            path: List<QualifierPart>,
            moduleDescriptor: ModuleDescriptor,
            trace: BindingTrace,
            shouldBeVisibleFrom: DeclarationDescriptor?,
            scopeForFirstPart: LexicalScope?,
            position: QualifierPosition
    ): Pair<DeclarationDescriptor?, Int> {
        if (path.isEmpty()) {
            return Pair(moduleDescriptor.getPackage(FqName.ROOT), 0)
        }

        val inImport = position == QualifierPosition.IMPORT

        val firstPart = path.first()

        if (position == QualifierPosition.EXPRESSION) {
            // In expression position, value wins against classifier (and package).
            // If we see a function or variable (possibly ambiguous),
            // tell resolver we have no qualifier and let it perform the context-dependent resolution.
            if (scopeForFirstPart != null && scopeForFirstPart.containsFunctionOrVariable(firstPart.name, firstPart.location)) {
                return Pair(null, 0)
            }
        }

        val classifierDescriptor = scopeForFirstPart?.let {
            it.findClassifier(firstPart.name, firstPart.location)
        }

        if (classifierDescriptor != null) {
            storeResult(trace, firstPart.expression, classifierDescriptor, shouldBeVisibleFrom, inImport)
        }

        val (prefixDescriptor, nextIndexAfterPrefix) =
                if (classifierDescriptor != null)
                    Pair(classifierDescriptor, 1)
                else
                    moduleDescriptor.quickResolveToPackage(path, trace, inImport)

        var currentDescriptor: DeclarationDescriptor? = prefixDescriptor
        for (qualifierPartIndex in nextIndexAfterPrefix .. path.size - 1) {
            val qualifierPart = path[qualifierPartIndex]

            val nextPackageOrClassDescriptor =
                    when (currentDescriptor) {
                        is ClassDescriptor ->
                            currentDescriptor.unsubstitutedInnerClassesScope.getContributedClassifier(qualifierPart.name, qualifierPart.location)
                        is PackageViewDescriptor -> {
                            val packageView =
                                    if (qualifierPart.typeArguments == null) {
                                        moduleDescriptor.getPackage(currentDescriptor.fqName.child(qualifierPart.name))
                                    }
                                    else null
                            if (packageView != null && !packageView.isEmpty()) {
                                packageView
                            }
                            else {
                                currentDescriptor.memberScope.getContributedClassifier(qualifierPart.name, qualifierPart.location)
                            }
                        }
                        else ->
                            null
                    }

            // If we are in expression, this name can denote a value (not a package or class).
            if (!(position == QualifierPosition.EXPRESSION && nextPackageOrClassDescriptor == null)) {
                storeResult(trace, qualifierPart.expression, nextPackageOrClassDescriptor, shouldBeVisibleFrom, inImport)
            }

            if (nextPackageOrClassDescriptor == null) {
                return Pair(currentDescriptor, qualifierPartIndex)
            }

            currentDescriptor = nextPackageOrClassDescriptor
        }

        return Pair(currentDescriptor, path.size)
    }


    public fun resolveQualifierInExpressionAndUnroll(
            expression: KtQualifiedExpression,
            context: ExpressionTypingContext
    ): List<CallExpressionElement> {
        val qualifiedExpressions = unrollToLeftMostQualifiedExpression(expression)
        val maxPossibleQualifierPrefix = getMaxPossibleQualifierPrefix(qualifiedExpressions)

        val (packageOrClassDescriptor, nextIndexAfterPrefix) = resolveToPackageOrClassPrefix(
                path = maxPossibleQualifierPrefix,
                moduleDescriptor = context.scope.ownerDescriptor.module,
                trace = context.trace,
                shouldBeVisibleFrom = context.scope.ownerDescriptor,
                scopeForFirstPart = context.scope,
                position = QualifierPosition.EXPRESSION
        )

        if (nextIndexAfterPrefix > 0) {
            val qualifierReferenceExpression = maxPossibleQualifierPrefix[nextIndexAfterPrefix - 1].expression
            val qualifier =
                    when (packageOrClassDescriptor) {
                        is PackageViewDescriptor ->
                            PackageQualifier(qualifierReferenceExpression, packageOrClassDescriptor)
                        is ClassDescriptor ->
                            ClassQualifier(qualifierReferenceExpression, packageOrClassDescriptor)
                        else ->
                            null
                    }
            if (qualifier != null) {
                context.trace.record(BindingContext.QUALIFIER, qualifier.expression, qualifier)
            }
        }

        return qualifiedExpressions.map { CallExpressionElement(it) }
    }

    private fun unrollToLeftMostQualifiedExpression(expression: KtQualifiedExpression): List<KtQualifiedExpression> {
        val unrolled = arrayListOf<KtQualifiedExpression>()

        var finger = expression
        while (true) {
            unrolled.add(finger)
            val receiver = finger.receiverExpression
            if (receiver !is KtQualifiedExpression) {
                break
            }
            finger = receiver
        }

        Collections.reverse(unrolled)
        return unrolled
    }

    private fun getMaxPossibleQualifierPrefix(qualifiedExpressions: List<KtQualifiedExpression>) : List<QualifierPart> {
        if (qualifiedExpressions.isEmpty()) return emptyList()

        val first = qualifiedExpressions.first()
        if (first.operationSign != KtTokens.DOT) return emptyList()
        val firstReceiver = first.receiverExpression
        if (firstReceiver !is KtSimpleNameExpression) return emptyList()

        val qualifierParts = arrayListOf<QualifierPart>()
        qualifierParts.add(QualifierPart(firstReceiver))

        for (qualifiedExpression in qualifiedExpressions.dropLast(1)) {
            if (qualifiedExpression.operationSign != KtTokens.DOT) break
            val selector = qualifiedExpression.selectorExpression
            if (selector !is KtSimpleNameExpression) break
            qualifierParts.add(QualifierPart(selector))
        }

        return qualifierParts
    }

    private fun ModuleDescriptor.quickResolveToPackage(
            path: List<QualifierPart>,
            trace: BindingTrace,
            inImport: Boolean
    ): Pair<PackageViewDescriptor, Int> {
        val possiblePackagePrefixSize = path.indexOfFirst { it.typeArguments != null }.let { if (it == -1) path.size() else it + 1 }
        var fqName = path.subList(0, possiblePackagePrefixSize).fold(FqName.ROOT) { fqName, qualifierPart ->
            fqName.child(qualifierPart.name)
        }
        var prefixSize = possiblePackagePrefixSize
        while (!fqName.isRoot) {
            val packageDescriptor = getPackage(fqName)
            if (!packageDescriptor.isEmpty()) {
                recordPackageViews(path.subList(0, prefixSize), packageDescriptor, trace, inImport)
                return Pair(packageDescriptor, prefixSize)
            }
            fqName = fqName.parent()
            prefixSize--
        }
        return Pair(getPackage(FqName.ROOT), 0)
    }

    private fun recordPackageViews(
            path: List<QualifierPart>,
            packageView: PackageViewDescriptor,
            trace: BindingTrace,
            inImport: Boolean
    ) {
        path.foldRight(packageView) { qualifierPart, currentView ->
            storeResult(trace, qualifierPart.expression, currentView, shouldBeVisibleFrom = null, inImport = inImport)
            val parentView = currentView.containingDeclaration
            assert(parentView != null) {
                "Containing Declaration must be not null for package with fqName: ${currentView.fqName}, " +
                "path: ${path.joinToString()}, packageView fqName: ${packageView.fqName}"
            }
            parentView!!
        }
    }

    private fun storeResult(
            trace: BindingTrace,
            referenceExpression: KtSimpleNameExpression,
            descriptors: Collection<DeclarationDescriptor>,
            shouldBeVisibleFrom: DeclarationDescriptor?,
            inImport: Boolean,
            isQualifier: Boolean = true
    ) {
        if (descriptors.size() > 1) {
            val visibleDescriptors = descriptors.filter { isVisible(it, shouldBeVisibleFrom, inImport) }
            if (visibleDescriptors.isEmpty()) {
                val descriptor = descriptors.first() as DeclarationDescriptorWithVisibility
                trace.report(Errors.INVISIBLE_REFERENCE.on(referenceExpression, descriptor, descriptor.visibility, descriptor))
            }
            else if (visibleDescriptors.size() > 1) {
                trace.record(BindingContext.AMBIGUOUS_REFERENCE_TARGET, referenceExpression, visibleDescriptors)
            }
            else {
                storeResult(trace, referenceExpression, visibleDescriptors.single(), null, inImport, isQualifier)
            }
        }
        else {
            storeResult(trace, referenceExpression, descriptors.singleOrNull(), shouldBeVisibleFrom, inImport, isQualifier)
        }
    }

    private fun storeResult(
            trace: BindingTrace,
            referenceExpression: KtSimpleNameExpression,
            descriptor: DeclarationDescriptor?,
            shouldBeVisibleFrom: DeclarationDescriptor?,
            inImport: Boolean,
            isQualifier: Boolean = true
    ) {
        if (descriptor == null) {
            trace.report(Errors.UNRESOLVED_REFERENCE.on(referenceExpression, referenceExpression))
            return
        }

        trace.record(BindingContext.REFERENCE_TARGET, referenceExpression, descriptor)

        if (descriptor is ClassifierDescriptor) {
            symbolUsageValidator.validateTypeUsage(descriptor, trace, referenceExpression)
        }

        if (descriptor is DeclarationDescriptorWithVisibility) {
            val fromToCheck =
                if (shouldBeVisibleFrom is PackageFragmentDescriptor && shouldBeVisibleFrom.source == SourceElement.NO_SOURCE) {
                    PackageFragmentWithCustomSource(shouldBeVisibleFrom, KotlinSourceElement(referenceExpression.getContainingKtFile()))
                }
                else {
                    shouldBeVisibleFrom
                }
            if (!isVisible(descriptor, fromToCheck, inImport)) {
                trace.report(Errors.INVISIBLE_REFERENCE.on(referenceExpression, descriptor, descriptor.visibility, descriptor))
            }
        }

        if (isQualifier) {
            storageQualifier(trace, referenceExpression, descriptor)
        }
    }

    private fun storageQualifier(trace: BindingTrace, referenceExpression: KtSimpleNameExpression, descriptor: DeclarationDescriptor) {
        val qualifier =
                when (descriptor) {
                    is PackageViewDescriptor -> PackageQualifier(referenceExpression, descriptor)
                    is ClassifierDescriptor -> createClassifierQualifier(referenceExpression, descriptor, trace.bindingContext)
                    else -> return
                }

        trace.record(BindingContext.QUALIFIER, qualifier.expression, qualifier)
    }

    private fun isVisible(
            descriptor: DeclarationDescriptor,
            shouldBeVisibleFrom: DeclarationDescriptor?,
            inImport: Boolean
    ): Boolean {
        if (descriptor !is DeclarationDescriptorWithVisibility || shouldBeVisibleFrom == null) return true

        val visibility = descriptor.visibility
        if (inImport) {
            if (Visibilities.isPrivate(visibility)) return false
            if (!visibility.mustCheckInImports()) return true
        }
        return Visibilities.isVisible(ReceiverValue.IRRELEVANT_RECEIVER, descriptor, shouldBeVisibleFrom)
    }
}

/*
    This purpose of this class is to pass information about source file for current package fragment in order for check visibilities between modules
    (see ModuleVisibilityHelperImpl.isInFriendModule).
 */
private class PackageFragmentWithCustomSource(private val original: PackageFragmentDescriptor, private val source: SourceElement) : PackageFragmentDescriptor by original {
    override fun getSource(): SourceElement = source
}
