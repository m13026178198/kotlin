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

package org.jetbrains.kotlin.idea.quickfix;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("ultimate/testData/quickFixes")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class UltimateQuickFixTestGenerated extends AbstractQuickFixTest {
    public void testAllFilesPresentInQuickFixes() throws Exception {
        KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("ultimate/testData/quickFixes"), Pattern.compile("^([\\w\\-_]+)\\.kt$"), true);
    }

    @TestMetadata("ultimate/testData/quickFixes/spring")
    @TestDataPath("$PROJECT_ROOT")
    @RunWith(JUnit3RunnerWithInners.class)
    public static class Spring extends AbstractQuickFixTest {
        public void testAllFilesPresentInSpring() throws Exception {
            KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("ultimate/testData/quickFixes/spring"), Pattern.compile("^([\\w\\-_]+)\\.kt$"), true);
        }

        @TestMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration")
        @TestDataPath("$PROJECT_ROOT")
        @RunWith(JUnit3RunnerWithInners.class)
        public static class FinalSpringAnnotatedDeclaration extends AbstractQuickFixTest {
            public void testAllFilesPresentInFinalSpringAnnotatedDeclaration() throws Exception {
                KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration"), Pattern.compile("^([\\w\\-_]+)\\.kt$"), true);
            }

            @TestMetadata("classWithComponentRuntime.kt")
            public void testClassWithComponentRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/classWithComponentRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("classWithConfigurationRuntime.kt")
            public void testClassWithConfigurationRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/classWithConfigurationRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("classWithCustomConfigurationRuntime.kt")
            public void testClassWithCustomConfigurationRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/classWithCustomConfigurationRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("funWithBeanFinalClassRuntime.kt")
            public void testFunWithBeanFinalClassRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/funWithBeanFinalClassRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("funWithBeanOpenClassRuntime.kt")
            public void testFunWithBeanOpenClassRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/funWithBeanOpenClassRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("funWithCustomBeanFinalClassRuntime.kt")
            public void testFunWithCustomBeanFinalClassRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/funWithCustomBeanFinalClassRuntime.kt");
                doTest(fileName);
            }

            @TestMetadata("funWithCustomBeanOpenClassRuntime.kt")
            public void testFunWithCustomBeanOpenClassRuntime() throws Exception {
                String fileName = KotlinTestUtils.navigationMetadata("ultimate/testData/quickFixes/spring/finalSpringAnnotatedDeclaration/funWithCustomBeanOpenClassRuntime.kt");
                doTest(fileName);
            }
        }
    }
}
