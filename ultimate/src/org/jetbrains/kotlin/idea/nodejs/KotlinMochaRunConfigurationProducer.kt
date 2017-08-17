package org.jetbrains.kotlin.idea.nodejs

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.lang.javascript.ecmascript6.TypeScriptUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.SmartList
import com.intellij.util.containers.SmartHashSet
import com.jetbrains.nodejs.mocha.MochaUtil
import com.jetbrains.nodejs.mocha.execution.*
import com.jetbrains.nodejs.util.NodeJsCoffeeUtil
import org.jetbrains.kotlin.idea.js.getJsClasspath
import org.jetbrains.kotlin.idea.js.getJsOutputFilePath
import org.jetbrains.kotlin.idea.project.TargetPlatformDetector
import org.jetbrains.kotlin.js.resolve.JsPlatform
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import java.io.File

class KotlinMochaRunConfigurationProducer : MochaRunConfigurationProducer() {
    private data class TestElementInfo(val runSettings: MochaRunSettings, val enclosingTestElement: PsiElement)
    private data class TestElementPath(val suiteNames: List<String>, val testName: String?)

    // Copied from MochaRunConfigurationProducer.collectMochaTestRoots()
    private fun collectMochaTestRoots(project: Project): List<VirtualFile> {
        val list = RunManager.getInstance(project).getConfigurationsList(MochaConfigurationType.getInstance())
        val roots = SmartList<VirtualFile>()
        for (configuration in list) {
            if (configuration is MochaRunConfiguration) {
                val settings = configuration.runSettings
                var path: String? = null
                if (settings.testKind === MochaTestKind.DIRECTORY) {
                    path = settings.testDirPath
                }
                else if (settings.testKind === MochaTestKind.TEST_FILE ||
                         settings.testKind === MochaTestKind.SUITE ||
                         settings.testKind === MochaTestKind.TEST) {
                    path = settings.testFilePath
                }
                if (!StringUtil.isEmptyOrSpaces(path)) {
                    val dir = LocalFileSystem.getInstance().findFileByPath(path!!)
                    if (dir != null) {
                        roots.add(dir)
                    }
                }
            }
        }
        return roots
    }

    // Copied from MochaRunConfigurationProducer.isActiveFor()
    private fun isActiveFor(element: PsiElement, context: ConfigurationContext): Boolean {
        val file = PsiUtilCore.getVirtualFile(element) ?: return false
        if (isTestRunnerPackageAvailableFor(element.project, file)) {
            return true
        }
        if (context.getOriginalConfiguration(MochaConfigurationType.getInstance()) is MochaRunConfiguration) {
            return true
        }
        val roots = collectMochaTestRoots(element.project)
        if (roots.isEmpty()) {
            return false
        }
        val dirs = SmartHashSet<VirtualFile>()
        for (root in roots) {
            if (root.isDirectory) {
                dirs.add(root)
            }
            else if (root == file) {
                return true
            }
        }
        return VfsUtilCore.isUnder(file, dirs)
    }

    private fun createSuiteOrTestData(element: PsiElement): TestElementPath? {
        val declaration = element.getNonStrictParentOfType<KtNamedDeclaration>() ?: return null
        val klass = when (declaration) {
            is KtClassOrObject -> declaration
            is KtNamedFunction -> declaration.containingClassOrObject ?: return null
            else -> return null
        }
        val suiteNames = klass.parentsWithSelf
                .filterIsInstance<KtClassOrObject>()
                .mapNotNull { it.name }
                .toList()
                .asReversed()
        val testName = (declaration as? KtNamedFunction)?.name
        return TestElementPath(suiteNames, testName)
    }

    private fun createTestElementRunInfo(element: PsiElement, originalSettings: MochaRunSettings): TestElementInfo? {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
        if (TargetPlatformDetector.getPlatform(module) !is JsPlatform) return null
        val project = module.project
        val testFilePath = getJsOutputFilePath(module, true, false) ?: return null
        val settings = if (originalSettings.workingDir.isBlank()) {
            val workingDir = FileUtil.toSystemDependentName(project.baseDir.path)
            originalSettings.builder().setWorkingDir(workingDir).build()
        }
        else originalSettings
        val (suiteNames, testName) = createSuiteOrTestData(element) ?: return null
        val builder = settings.builder()
        builder.setTestFilePath(testFilePath)
        if (settings.ui.isEmpty()) {
          builder.setUi(MochaUtil.UI_BDD)
        }
        if (testName == null) {
          builder.setTestKind(MochaTestKind.SUITE)
          builder.setSuiteNames(suiteNames)
        }
        else {
          builder.setTestKind(MochaTestKind.TEST)
          builder.setTestNames(suiteNames + testName)
        }

        val nodeJsClasspath = getJsClasspath(module).joinToString(File.pathSeparator) {
            val basePath = project.basePath ?: return@joinToString it
            FileUtil.getRelativePath(basePath, it, '/') ?: it
        }
        builder.setEnvData(EnvironmentVariablesData.create(mapOf("NODE_PATH" to nodeJsClasspath), true))

        return TestElementInfo(builder.build(), element)
    }

    private fun getLanguageCompilerMochaOption(psiFile: PsiFile?): String? {
        if (psiFile == null) return null

        val pkg = MochaUtil.getMochaPackage(psiFile.project, null)
        return when {
            pkg.name == "mocha-webpack" -> null
            NodeJsCoffeeUtil.isCoffee(psiFile) -> "--compilers coffee:coffee-script/register"
            TypeScriptUtil.TYPESCRIPT_FILE_TYPES.contains(psiFile.fileType) -> "--require ts-node/register"
            else -> null
        }
    }

    override fun isConfigurationFromCompatibleContext(configuration: MochaRunConfiguration, context: ConfigurationContext): Boolean {
        val element = context.psiLocation ?: return false
        val (thisRunSettings, _) = createTestElementRunInfo(element, configuration.runSettings) ?: return false
        val thatRunSettings = configuration.runSettings
        val thisTestKind = thisRunSettings.testKind
        if (thisTestKind != thatRunSettings.testKind) return false
        return when {
            thisTestKind == MochaTestKind.DIRECTORY -> thisRunSettings.testDirPath == thatRunSettings.testDirPath
            thisTestKind == MochaTestKind.PATTERN -> thisRunSettings.testFilePattern == thatRunSettings.testFilePattern
            thisTestKind == MochaTestKind.TEST_FILE -> thisRunSettings.testFilePath == thatRunSettings.testFilePath
            thisTestKind == MochaTestKind.SUITE -> thisRunSettings.testFilePath == thatRunSettings.testFilePath && thisRunSettings.suiteNames == thatRunSettings.suiteNames
            thisTestKind != MochaTestKind.TEST -> false
            else -> thisRunSettings.testFilePath == thatRunSettings.testFilePath && thisRunSettings.testNames == thatRunSettings.testNames
        }
    }

    override fun setupConfigurationFromCompatibleContext(
            configuration: MochaRunConfiguration,
            context: ConfigurationContext,
            sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = context.psiLocation ?: return false
        if (!isActiveFor(element, context)) return false
        var (runSettings, enclosingTestElement) = createTestElementRunInfo(element, configuration.runSettings) ?: return false
        if (runSettings.testKind == MochaTestKind.DIRECTORY) return false
        if (runSettings.extraMochaOptions.isBlank()) {
            val psiFile = element.containingFile
            val compilerMochaOption = getLanguageCompilerMochaOption(psiFile?.originalFile)
            if (compilerMochaOption != null) {
                runSettings = runSettings.builder().setExtraMochaOptions(compilerMochaOption).build()
            }
        }
        configuration.runSettings = runSettings
        sourceElement.set(enclosingTestElement)
        configuration.setGeneratedName()
        return true
    }
}