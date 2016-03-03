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

package org.jetbrains.kotlin.js.translate.context;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.openapi.util.Factory;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.ReflectionTypes;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.js.config.Config;
import org.jetbrains.kotlin.js.config.EcmaVersion;
import org.jetbrains.kotlin.js.config.LibrarySourcesConfig;
import org.jetbrains.kotlin.js.translate.context.generator.Generator;
import org.jetbrains.kotlin.js.translate.context.generator.Rule;
import org.jetbrains.kotlin.js.translate.intrinsic.Intrinsics;
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.DescriptorUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.jetbrains.kotlin.js.translate.utils.AnnotationsUtils.*;
import static org.jetbrains.kotlin.js.translate.utils.JsDescriptorUtils.*;
import static org.jetbrains.kotlin.js.translate.utils.ManglingUtils.getMangledName;
import static org.jetbrains.kotlin.js.translate.utils.ManglingUtils.getSuggestedName;
import static org.jetbrains.kotlin.resolve.DescriptorUtils.isExtension;
import static org.jetbrains.kotlin.resolve.calls.tasks.DynamicCallsKt.isDynamic;

/**
 * Aggregates all the static parts of the context.
 */
public final class StaticContext {

    public static StaticContext generateStaticContext(@NotNull BindingTrace bindingTrace, @NotNull Config config, @NotNull ModuleDescriptor moduleDescriptor) {
        JsProgram program = new JsProgram("main");
        Namer namer = Namer.newInstance(program.getRootScope());
        Intrinsics intrinsics = new Intrinsics();
        StandardClasses standardClasses = StandardClasses.bindImplementations(namer.getKotlinScope());
        return new StaticContext(program, bindingTrace, namer, intrinsics, standardClasses, program.getRootScope(), config, moduleDescriptor);
    }

    @NotNull
    private final JsProgram program;

    @NotNull
    private final BindingTrace bindingTrace;
    @NotNull
    private final Namer namer;

    @NotNull
    private final Intrinsics intrinsics;

    @NotNull
    private final StandardClasses standardClasses;

    @NotNull
    private final ReflectionTypes reflectionTypes;

    @NotNull
    private final JsScope rootScope;

    @NotNull
    private final Generator<JsName> names = new NameGenerator();
    @NotNull
    private final Map<FqName, JsName> packageNames = Maps.newHashMap();
    @NotNull
    private final Generator<JsScope> scopes = new ScopeGenerator();
    @NotNull
    private final Generator<JsExpression> qualifiers = new QualifierGenerator();
    @NotNull
    private final Generator<Boolean> qualifierIsNull = new QualifierIsNullGenerator();

    @NotNull
    private final Map<JsScope, JsFunction> scopeToFunction = Maps.newHashMap();

    @NotNull
    private final Map<ClassDescriptor, List<DeclarationDescriptor>> localClassesClosure = Maps.newHashMap();

    @NotNull
    private final Config config;

    @NotNull
    private final EcmaVersion ecmaVersion;

    //TODO: too many parameters in constructor
    private StaticContext(@NotNull JsProgram program, @NotNull BindingTrace bindingTrace,
            @NotNull Namer namer, @NotNull Intrinsics intrinsics,
            @NotNull StandardClasses standardClasses, @NotNull JsScope rootScope, @NotNull Config config, @NotNull ModuleDescriptor moduleDescriptor) {
        this.program = program;
        this.bindingTrace = bindingTrace;
        this.namer = namer;
        this.intrinsics = intrinsics;
        this.rootScope = rootScope;
        this.standardClasses = standardClasses;
        this.config = config;
        this.ecmaVersion = config.getTarget();
        this.reflectionTypes = new ReflectionTypes(moduleDescriptor);
    }

    public boolean isEcma5() {
        return ecmaVersion == EcmaVersion.v5;
    }

    @NotNull
    public JsProgram getProgram() {
        return program;
    }

    @NotNull
    public BindingTrace getBindingTrace() {
        return bindingTrace;
    }

    @NotNull
    public BindingContext getBindingContext() {
        return bindingTrace.getBindingContext();
    }

    @NotNull
    public Intrinsics getIntrinsics() {
        return intrinsics;
    }

    @NotNull
    public Namer getNamer() {
        return namer;
    }

    @NotNull
    public ReflectionTypes getReflectionTypes() {
        return reflectionTypes;
    }

    @NotNull
    public JsScope getRootScope() {
        return rootScope;
    }

    @NotNull
    public JsScope getScopeForDescriptor(@NotNull DeclarationDescriptor descriptor) {
        JsScope scope = scopes.get(descriptor.getOriginal());
        assert scope != null : "Must have a scope for descriptor";
        return scope;
    }

    @NotNull
    public JsFunction getFunctionWithScope(@NotNull CallableDescriptor descriptor) {
        JsScope scope = getScopeForDescriptor(descriptor);
        JsFunction function = scopeToFunction.get(scope);
        assert scope.equals(function.getScope()) : "Inconsistency.";
        return function;
    }

    @NotNull
    public JsNameRef getQualifiedReference(@NotNull DeclarationDescriptor descriptor) {
        if (descriptor instanceof PackageViewDescriptor) {
            return getQualifiedReference(((PackageViewDescriptor) descriptor).getFqName());
        }
        if (descriptor instanceof PackageFragmentDescriptor) {
            return getQualifiedReference(((PackageFragmentDescriptor) descriptor).getFqName());
        }

        return new JsNameRef(getNameForDescriptor(descriptor), getQualifierForDescriptor(descriptor));
    }

    @NotNull
    public JsNameRef getQualifiedReference(@NotNull FqName packageFqName) {
        return new JsNameRef(getNameForPackage(packageFqName),
                             packageFqName.isRoot() ? null : getQualifierForParentPackage(packageFqName.parent()));
    }

    @NotNull
    public JsName getNameForDescriptor(@NotNull DeclarationDescriptor descriptor) {
        JsName name = names.get(descriptor.getOriginal());
        assert name != null : "Must have name for descriptor";
        return name;
    }

    @NotNull
    public JsName getNameForPackage(@NotNull final FqName packageFqName) {
        return ContainerUtil.getOrCreate(packageNames, packageFqName, new Factory<JsName>() {
            @Override
            public JsName create() {
                String name = Namer.generatePackageName(packageFqName);
                return getRootScope().declareName(name);
            }
        });
    }

    @NotNull
    private JsNameRef getQualifierForParentPackage(@NotNull FqName packageFqName) {
        JsNameRef result = null;
        JsNameRef qualifier = null;

        FqName fqName = packageFqName;

        while (true) {
            JsNameRef ref = getNameForPackage(fqName).makeRef();

            if (qualifier == null) {
                result = ref;
            }
            else {
                qualifier.setQualifier(ref);
            }

            qualifier = ref;

            if (fqName.isRoot()) break;
            fqName = fqName.parent();
        }

        return result;
    }

    @NotNull
    public Config getConfig() {
        return config;
    }

    private final class NameGenerator extends Generator<JsName> {

        public NameGenerator() {
            Rule<JsName> namesForDynamic = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    if (isDynamic(descriptor)) {
                        String name = descriptor.getName().asString();
                        return JsDynamicScope.INSTANCE.declareName(name);
                    }

                    return null;
                }
            };

            Rule<JsName> localDeclarations = new Rule<JsName>() {
                @Nullable
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!DescriptorUtils.isDescriptorWithLocalVisibility(descriptor) ||
                        !DescriptorUtils.isClass(descriptor)) {
                        return null;
                    }

                    List<String> parts = new ArrayList<String>();
                    do {
                        parts.add(descriptor.getName().isSpecial() ? "f" : descriptor.getName().getIdentifier());
                        descriptor = descriptor.getContainingDeclaration();
                    } while (descriptor != null && !(descriptor instanceof ClassOrPackageFragmentDescriptor));
                    assert descriptor != null;

                    Collections.reverse(parts);
                    StringBuilder suggestedName = new StringBuilder(parts.get(0));
                    for (int i = 1; i < parts.size(); ++i) {
                        suggestedName.append('$').append(parts.get(i));
                    }

                    JsScope scope = getScopeForDescriptor(descriptor);
                    return scope.declareFreshName(suggestedName.toString());
                }
            };

            Rule<JsName> namesForStandardClasses = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor data) {
                    if (!standardClasses.isStandardObject(data)) {
                        return null;
                    }
                    return standardClasses.getStandardObjectName(data);
                }
            };
            Rule<JsName> memberDeclarationsInsideParentsScope = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    JsScope scope = getEnclosingScope(descriptor);
                    return scope.declareFreshName(getSuggestedName(descriptor));
                }
            };
            Rule<JsName> constructorOrCompanionObjectHasTheSameNameAsTheClass = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    if (descriptor instanceof ConstructorDescriptor && ((ConstructorDescriptor) descriptor).isPrimary() ||
                        DescriptorUtils.isCompanionObject(descriptor)
                    ) {
                        //noinspection ConstantConditions
                        return getNameForDescriptor(descriptor.getContainingDeclaration());
                    }
                    return null;
                }
            };

            // ecma 5 property name never declares as obfuscatable:
            // 1) property cannot be overloaded, so, name collision is not possible
            // 2) main reason: if property doesn't have any custom accessor, value holder will have the same name as accessor, so, the same name will be declared more than once
            //
            // But extension property may obfuscatable, because transform into function. Example: String.foo = 1, Int.foo = 2
            Rule<JsName> propertyOrPropertyAccessor = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    PropertyDescriptor propertyDescriptor;
                    if (descriptor instanceof PropertyAccessorDescriptor) {
                        propertyDescriptor = ((PropertyAccessorDescriptor) descriptor).getCorrespondingProperty();
                    }
                    else if (descriptor instanceof PropertyDescriptor) {
                        propertyDescriptor = (PropertyDescriptor) descriptor;
                    }
                    else {
                        return null;
                    }

                    String nameFromAnnotation = getNameForAnnotatedObjectWithOverrides(propertyDescriptor);
                    if (nameFromAnnotation != null) {
                        return declarePropertyOrPropertyAccessorName(descriptor, nameFromAnnotation, false);
                    }

                    String propertyName = getSuggestedName(propertyDescriptor);

                    if (!isExtension(propertyDescriptor)) {
                        if (Visibilities.isPrivate(propertyDescriptor.getVisibility())) {
                            propertyName = getMangledName(propertyDescriptor, propertyName);
                        }
                        return declarePropertyOrPropertyAccessorName(descriptor, propertyName, false);
                    } else {
                        assert !(descriptor instanceof PropertyDescriptor) : "descriptor should not be instance of PropertyDescriptor: " + descriptor;

                        boolean isGetter = descriptor instanceof PropertyGetterDescriptor;
                        String accessorName = Namer.getNameForAccessor(propertyName, isGetter, false);
                        return declarePropertyOrPropertyAccessorName(descriptor, accessorName, false);
                    }
                }
            };

            Rule<JsName> predefinedObjectsHasUnobfuscatableNames = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    // The mixing of override and rename by annotation(e.g. native) is forbidden.
                    if (descriptor instanceof CallableMemberDescriptor &&
                        !((CallableMemberDescriptor) descriptor).getOverriddenDescriptors().isEmpty()) {
                        return null;
                    }

                    if (descriptor instanceof ConstructorDescriptor) {
                        DeclarationDescriptor classDescriptor = descriptor.getContainingDeclaration();
                        assert classDescriptor != null;
                        descriptor = classDescriptor;
                    }

                    String name = getNameForAnnotatedObjectWithOverrides(descriptor);
                    if (name != null) return getEnclosingScope(descriptor).declareName(name);
                    return null;
                }
            };

            Rule<JsName> overridingDescriptorsReferToOriginalName = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    //TODO: refactor
                    if (!(descriptor instanceof FunctionDescriptor)) {
                        return null;
                    }
                    FunctionDescriptor overriddenDescriptor = getOverriddenDescriptor((FunctionDescriptor) descriptor);
                    if (overriddenDescriptor == null) {
                        return null;
                    }

                    JsScope scope = getEnclosingScope(descriptor);
                    JsName result = getNameForDescriptor(overriddenDescriptor);
                    scope.declareName(result.getIdent());
                    return result;
                }
            };

            addRule(namesForDynamic);
            addRule(localDeclarations);
            addRule(namesForStandardClasses);
            addRule(constructorOrCompanionObjectHasTheSameNameAsTheClass);
            addRule(propertyOrPropertyAccessor);
            addRule(predefinedObjectsHasUnobfuscatableNames);
            addRule(overridingDescriptorsReferToOriginalName);
            addRule(memberDeclarationsInsideParentsScope);
        }
    }

    @NotNull
    public JsName declarePropertyOrPropertyAccessorName(@NotNull DeclarationDescriptor descriptor, @NotNull String name, boolean fresh) {
        JsScope scope = getEnclosingScope(descriptor);
        return fresh ? scope.declareFreshName(name) : scope.declareName(name);
    }

    @NotNull
    private JsScope getEnclosingScope(@NotNull DeclarationDescriptor descriptor) {
        DeclarationDescriptor containingDeclaration = getContainingDeclaration(descriptor);
        return getScopeForDescriptor(containingDeclaration.getOriginal());
    }

    private final class ScopeGenerator extends Generator<JsScope> {

        public ScopeGenerator() {
            Rule<JsScope> generateNewScopesForClassesWithNoAncestors = new Rule<JsScope>() {
                @Override
                public JsScope apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof ClassDescriptor)) {
                        return null;
                    }
                    if (getSuperclass((ClassDescriptor) descriptor) == null) {
                        return getRootScope().innerObjectScope("Scope for class " + descriptor.getName());
                    }
                    return null;
                }
            };
            Rule<JsScope> generateInnerScopesForDerivedClasses = new Rule<JsScope>() {
                @Override
                public JsScope apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof ClassDescriptor)) {
                        return null;
                    }
                    ClassDescriptor superclass = getSuperclass((ClassDescriptor) descriptor);
                    if (superclass == null) {
                        return null;
                    }
                    return getScopeForDescriptor(superclass).innerObjectScope("Scope for class " + descriptor.getName());
                }
            };
            Rule<JsScope> generateNewScopesForPackageDescriptors = new Rule<JsScope>() {
                @Override
                public JsScope apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof PackageFragmentDescriptor)) {
                        return null;
                    }
                    return getRootScope().innerObjectScope("Package " + descriptor.getName());
                }
            };
            //TODO: never get there
            Rule<JsScope> generateInnerScopesForMembers = new Rule<JsScope>() {
                @Override
                public JsScope apply(@NotNull DeclarationDescriptor descriptor) {
                    JsScope enclosingScope = getEnclosingScope(descriptor);
                    return enclosingScope.innerObjectScope("Scope for member " + descriptor.getName());
                }
            };
            Rule<JsScope> createFunctionObjectsForCallableDescriptors = new Rule<JsScope>() {
                @Override
                public JsScope apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof CallableDescriptor)) {
                        return null;
                    }
                    JsScope enclosingScope = getEnclosingScope(descriptor);

                    JsFunction correspondingFunction = JsAstUtils.createFunctionWithEmptyBody(enclosingScope);
                    assert (!scopeToFunction.containsKey(correspondingFunction.getScope())) : "Scope to function value overridden for " + descriptor;
                    scopeToFunction.put(correspondingFunction.getScope(), correspondingFunction);
                    return correspondingFunction.getScope();
                }
            };
            addRule(createFunctionObjectsForCallableDescriptors);
            addRule(generateNewScopesForClassesWithNoAncestors);
            addRule(generateInnerScopesForDerivedClasses);
            addRule(generateNewScopesForPackageDescriptors);
            addRule(generateInnerScopesForMembers);
        }
    }

    @Nullable
    public JsExpression getQualifierForDescriptor(@NotNull DeclarationDescriptor descriptor) {
        if (qualifierIsNull.get(descriptor.getOriginal()) != null) {
            return null;
        }
        return qualifiers.get(descriptor.getOriginal());
    }

    private final class QualifierGenerator extends Generator<JsExpression> {
        public QualifierGenerator() {
            Rule<JsExpression> standardObjectsHaveKotlinQualifier = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!standardClasses.isStandardObject(descriptor)) {
                        return null;
                    }
                    return namer.kotlinObject();
                }
            };
            //TODO: review and refactor
            Rule<JsExpression> packageLevelDeclarationsHaveEnclosingPackagesNamesAsQualifier = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (isNativeObject(descriptor)) return null;

                    DeclarationDescriptor containingDescriptor = getContainingDeclaration(descriptor);
                    if (!(containingDescriptor instanceof PackageFragmentDescriptor)) {
                        return null;
                    }

                    JsNameRef result = getQualifierForParentPackage(((PackageFragmentDescriptor) containingDescriptor).getFqName());

                    String moduleName = getExternalModuleName(descriptor);
                    if (moduleName == null) {
                        return result;
                    }

                    if (LibrarySourcesConfig.UNKNOWN_EXTERNAL_MODULE_NAME.equals(moduleName)) {
                        return null;
                    }

                    return JsAstUtils.replaceRootReference(
                            result, namer.getModuleReference(program.getStringLiteral(moduleName)));
                }
            };
            Rule<JsExpression> constructorOrCompanionObjectHasTheSameQualifierAsTheClass = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (descriptor instanceof ConstructorDescriptor || DescriptorUtils.isCompanionObject(descriptor)) {
                        //noinspection ConstantConditions
                        return getQualifierForDescriptor(descriptor.getContainingDeclaration());
                    }
                    return null;
                }
            };
            Rule<JsExpression> libraryObjectsHaveKotlinQualifier = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (isLibraryObject(descriptor)) {
                        return namer.kotlinObject();
                    }
                    return null;
                }
            };
            Rule<JsExpression> nativeObjectsHaveNativePartOfFullQualifier = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (descriptor instanceof ConstructorDescriptor || !isNativeObject(descriptor)) return null;

                    DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();
                    if (containingDeclaration != null && isNativeObject(containingDeclaration)) {
                        return getQualifiedReference(containingDeclaration);
                    }

                    return null;
                }
            };
            Rule<JsExpression> staticMembersHaveContainerQualifier = new Rule<JsExpression>() {
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (descriptor instanceof CallableDescriptor && !isNativeObject(descriptor)) {
                        CallableDescriptor callableDescriptor = (CallableDescriptor) descriptor;
                        if (DescriptorUtils.isStaticDeclaration(callableDescriptor)) {
                            return getQualifiedReference(callableDescriptor.getContainingDeclaration());
                        }
                    }

                    return null;
                }
            };
            Rule<JsExpression> nestedClassesHaveContainerQualifier = new Rule<JsExpression>() {
                @Nullable
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof ClassDescriptor)) {
                        return null;
                    }
                    DeclarationDescriptor container = descriptor.getContainingDeclaration();
                    if (container != null && !(container instanceof ClassDescriptor)) {
                        container = DescriptorUtils.getContainingClass(container);
                    }
                    if (container == null) {
                        return null;
                    }

                    if (isNativeObject(descriptor) || isBuiltin(descriptor)) {
                        return null;
                    }
                    ClassDescriptor cls = (ClassDescriptor) descriptor;
                    if (cls.getKind() == ClassKind.ENUM_ENTRY || cls.getKind() == ClassKind.OBJECT) {
                        return null;
                    }

                    JsExpression result = getQualifiedReference(container);
                    if (DescriptorUtils.isCompanionObject(container)) {
                        result = Namer.getCompanionObjectAccessor(result);
                    }
                    return result;
                }
            };

            Rule<JsExpression> localClassesHavePackageQualifier = new Rule<JsExpression>() {
                @Nullable
                @Override
                public JsExpression apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!DescriptorUtils.isDescriptorWithLocalVisibility(descriptor) || !(descriptor instanceof ClassDescriptor)) {
                        return null;
                    }

                    descriptor = descriptor.getContainingDeclaration();
                    while (descriptor != null && !(descriptor instanceof ClassOrPackageFragmentDescriptor)) {
                        descriptor = descriptor.getContainingDeclaration();
                    }
                    if (!(descriptor instanceof PackageFragmentDescriptor)) return null;

                    return getQualifiedReference(descriptor);
                }
            };

            addRule(libraryObjectsHaveKotlinQualifier);
            addRule(constructorOrCompanionObjectHasTheSameQualifierAsTheClass);
            addRule(standardObjectsHaveKotlinQualifier);
            addRule(packageLevelDeclarationsHaveEnclosingPackagesNamesAsQualifier);
            addRule(nativeObjectsHaveNativePartOfFullQualifier);
            addRule(staticMembersHaveContainerQualifier);
            addRule(nestedClassesHaveContainerQualifier);
            addRule(localClassesHavePackageQualifier);
        }
    }

    private static class QualifierIsNullGenerator extends Generator<Boolean> {

        private QualifierIsNullGenerator() {
            Rule<Boolean> propertiesInClassHaveNoQualifiers = new Rule<Boolean>() {
                @Override
                public Boolean apply(@NotNull DeclarationDescriptor descriptor) {
                    if ((descriptor instanceof PropertyDescriptor) && descriptor.getContainingDeclaration() instanceof ClassDescriptor) {
                        return true;
                    }
                    return null;
                }
            };
            addRule(propertiesInClassHaveNoQualifiers);
        }
    }

    public void putLocalClassClosure(@NotNull ClassDescriptor localClass, @NotNull List<DeclarationDescriptor> closure) {
        localClassesClosure.put(localClass, Lists.newArrayList(closure));
    }

    @Nullable
    public List<DeclarationDescriptor> getLocalClassClosure(@NotNull ClassDescriptor localClass) {
        List<DeclarationDescriptor> result = localClassesClosure.get(localClass);
        return result != null ? Lists.newArrayList(result) : null;
    }
}
