package dev.smalicalls;

import com.intellij.codeInsight.codeVision.CodeVisionHost;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.JavaInheritorsCodeVisionProvider;
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingSettingsPerFile;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspection;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiTypes;
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor;
import com.intellij.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor;
import com.intellij.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestModeFlags;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.kotlin.idea.codeInsight.inspections.UnusedSymbolInspection;
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo;
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureProcessor;
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinMethodDescriptor;
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinTypeInfo;
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor;
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SmaliInspectionTest extends LightJavaCodeInsightFixtureTestCase {
    public void testChangeJavaSignatureWidensParameterAndNarrowsReturnWithoutChangingRegisters() {
        var parent = myFixture.addClass("package example; public class Parent {}");
        var child = myFixture.addClass("package example; public class Child extends Parent {}");
        var owner = myFixture.addClass("""
                package example;
                public class Hooks {
                    public static Parent bind(Child value) { return null; }
                    public static Parent bind(int value) { return null; }
                }
                """);
        var factory = JavaPsiFacade.getElementFactory(getProject());
        var kotlin = myFixture.addFileToProject("Patch.kt", """
                const val OWNER = "Lexample/Hooks;"
                const val SIGNATURE = "bind(Lexample/Child;)Lexample/Parent;"
                fun patch(reg: Int) = "invoke-static {v$reg}, $OWNER->$SIGNATURE"
                """);
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static/range {v0 .. v0}, Lexample/Hooks;->bind(Lexample/Child;)Lexample/Parent;
                move-result-object v1
                invoke-static {v0}, Lexample/Hooks;->bind(I)Lexample/Parent;
                """);
        var parameters = new ParameterInfoImpl[]{new ParameterInfoImpl(0, "value", factory.createType(parent))};

        new ChangeSignatureProcessor(getProject(), owner.getMethods()[0], false, "public", "bindNew",
                factory.createType(child), parameters).run();

        assertEquals("""
                const val OWNER = "Lexample/Hooks;"
                const val SIGNATURE = "bind(Lexample/Child;)Lexample/Parent;"
                fun patch(reg: Int) = "invoke-static {v$reg}, $OWNER->bindNew(Lexample/Parent;)Lexample/Child;"
                """, kotlin.getText());
        assertEquals("""
                invoke-static/range {v0 .. v0}, Lexample/Hooks;->bindNew(Lexample/Parent;)Lexample/Child;
                move-result-object v1
                invoke-static {v0}, Lexample/Hooks;->bind(I)Lexample/Parent;
                """, smali.getText());
    }

    public void testChangeJavaSignatureFlagsRegisterAndResultChanges() {
        var owner = myFixture.addClass("""
                package example;
                public class Hooks {
                    public static int bind(int value) { return 0; }
                }
                """);
        String call = "invoke-static {v0}, Lexample/Hooks;->bind(I)I\nmove-result v1\n";
        var smali = myFixture.addFileToProject("Patch.smali", call);
        var method = owner.getMethods()[0];

        var extraParameter = new ParameterInfoImpl[]{
                new ParameterInfoImpl(0, "value", PsiTypes.intType()),
                new ParameterInfoImpl(-1, "extra", PsiTypes.intType(), "0")
        };
        var registers = org.junit.Assert.assertThrows(ConflictsInTestsException.class, () -> new ChangeSignatureProcessor(
                getProject(), method, false, "public", "bind", PsiTypes.intType(), extraParameter).run());
        assertTrue(registers.getMessage(), registers.getMessage().contains("updating Smali registers"));
        var results = org.junit.Assert.assertThrows(ConflictsInTestsException.class, () -> new ChangeSignatureProcessor(
                getProject(), method, false, "public", "bind", PsiTypes.longType(), ParameterInfoImpl.fromMethod(method)).run());
        assertTrue(results.getMessage(), results.getMessage().contains("different Smali result handling"));
        assertEquals(call, smali.getText());
    }

    public void testChangeKotlinSignatureRetargetsStandaloneDescriptor() {
        var parent = myFixture.addClass("package example; public class Parent {}");
        myFixture.addClass("package example; public class Child extends Parent {}");
        var kotlin = (KtFile) myFixture.addFileToProject("example/Hooks.kt", """
                package example
                fun bind(item: Child): Parent = item
                """);
        var patch = myFixture.addFileToProject("Patch.kt", """
                val target = "Lexample/HooksKt;->bind(Lexample/Child;)Lexample/Parent;"
                """);
        var function = (KtNamedFunction) kotlin.getDeclarations().getFirst();
        var descriptor = new KotlinMethodDescriptor(function);
        var change = new KotlinChangeInfo(descriptor, descriptor.getParameters(), descriptor.getVisibility(),
                null, "bindNew", new KotlinTypeInfo(parent.getQualifiedName(), function), false);
        change.getNewParameters()[0].setType(parent.getQualifiedName());

        new KotlinChangeSignatureProcessor(getProject(), change).run();

        assertEquals("""
                val target = "Lexample/HooksKt;->bindNew(Lexample/Parent;)Lexample/Parent;"
                """, patch.getText());
    }

    public void testChangeKotlinPackageRetargetsClassDescriptors() {
        var kotlin = (KtFile) myFixture.addFileToProject("original/Hooks.kt", """
                package original
                class Hooks
                """);
        var patch = myFixture.addFileToProject("Patch.kt", """
                val target = "Loriginal/Hooks;-><init>()V"
                """);

        new K2ChangePackageRefactoringProcessor(new K2ChangePackageDescriptor(getProject(), Set.of(kotlin),
                new FqName("moved"), false, false)).run();

        assertEquals("""
                val target = "Lmoved/Hooks;-><init>()V"
                """, patch.getText());
    }

    public void testMoveJavaPackageUpdatesDescriptorsForContainedClasses() {
        var owner = myFixture.addClass("""
                package original;
                public class Hooks {
                    public static class Nested {}
                }
                """);
        var target = myFixture.addClass("package moved; class Existing {}").getContainingFile().getContainingDirectory();
        var patch = myFixture.addFileToProject("Patch.smali", """
                invoke-direct {v0}, Loriginal/Hooks;-><init>()V
                invoke-direct {v0}, Loriginal/Hooks$Nested;-><init>()V
                """);
        var originalPackage = JavaPsiFacade.getInstance(getProject()).findPackage("original");
        var destination = new SingleSourceRootMoveDestination(new PackageWrapper(owner.getManager(), "moved"), target);

        assertNotNull(originalPackage);
        new MoveClassesOrPackagesProcessor(getProject(), new PsiElement[]{originalPackage}, destination, false, false, null).run();

        assertEquals("""
                invoke-direct {v0}, Lmoved/original/Hooks;-><init>()V
                invoke-direct {v0}, Lmoved/original/Hooks$Nested;-><init>()V
                """, patch.getText());
    }

    public void testMoveJavaClassToAnotherPackageUpdatesExactDescriptors() {
        var owner = myFixture.addClass("package original; public class Hooks {}");
        var target = myFixture.addClass("package moved; class Existing {}").getContainingFile().getContainingDirectory();
        var kotlin = myFixture.addFileToProject("Patch.kt", """
                const val OWNER = "Loriginal/Hooks;"
                val call = "invoke-direct {v0}, $OWNER-><init>()V"
                val other = "Loriginal/Other;"
                """);
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static {v0}, Lother/Hooks;->bind(Loriginal/Hooks;)Loriginal/Hooks;
                """);
        var destination = new SingleSourceRootMoveDestination(new PackageWrapper(owner.getManager(), "moved"), target);

        new MoveClassesOrPackagesProcessor(getProject(), new PsiElement[]{owner}, destination, false, false, null).run();

        assertEquals("""
                const val OWNER = "Lmoved/Hooks;"
                val call = "invoke-direct {v0}, Lmoved/Hooks;-><init>()V"
                val other = "Loriginal/Other;"
                """, kotlin.getText());
        assertEquals("""
                invoke-static {v0}, Lother/Hooks;->bind(Lmoved/Hooks;)Lmoved/Hooks;
                """, smali.getText());
    }

    public void testRenameJavaMethodUpdatesOnlyItsExactReferences() {
        var java = myFixture.addClass("""
                package example;
                public class Hooks {
                    public static void bind(int value) {}
                    public static void bind(long value) {}
                }
                """);
        var kotlin = myFixture.addFileToProject("Patch.kt", """
                private const val METHOD = "bind(I)V"
                val call = "invoke-static {v0}, Lexample/Hooks;->$METHOD"
                val descriptor = "Lexample/Hooks;->bind(I)V"
                val overload = "invoke-static {v0}, Lexample/Hooks;->bind(J)V"
                val anotherOwner = "invoke-static {v0}, Lother/Hooks;->$METHOD"
                """);
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static {v0}, Lexample/Hooks;->bind(I)V
                # invoke-static {v0}, Lexample/Hooks;->bind(I)V
                invoke-static/range {v0 .. v0}, Lexample/Hooks;->bind(I)V
                invoke-static {v0}, Lexample/Hooks;->bind(J)V
                """);

        myFixture.renameElement(java.getMethods()[0], "bindValue", false, false);
        assertEquals("""
                private const val METHOD = "bind(I)V"
                val call = "invoke-static {v0}, Lexample/Hooks;->bindValue(I)V"
                val descriptor = "Lexample/Hooks;->bindValue(I)V"
                val overload = "invoke-static {v0}, Lexample/Hooks;->bind(J)V"
                val anotherOwner = "invoke-static {v0}, Lother/Hooks;->$METHOD"
                """, kotlin.getText());
        assertEquals("""
                invoke-static {v0}, Lexample/Hooks;->bindValue(I)V
                # invoke-static {v0}, Lexample/Hooks;->bind(I)V
                invoke-static/range {v0 .. v0}, Lexample/Hooks;->bindValue(I)V
                invoke-static {v0}, Lexample/Hooks;->bind(J)V
                """, smali.getText());
    }

    public void testRenameKotlinClassUpdatesDescriptorsAndSignatureTypes() {
        var kotlin = (KtFile) myFixture.addFileToProject("example/Hooks.kt", """
                package example
                class Hooks
                """);
        var patch = myFixture.addFileToProject("Patch.kt", """
                const val OWNER = "Lexample/Hooks;"
                val call = "invoke-static {v0}, $OWNER->bind(Lexample/Hooks;)Lexample/Hooks;"
                """);
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static {v0}, Lother/Hooks;->bind([Lexample/Hooks;)Lexample/Hooks;
                invoke-direct {v0}, Lexample/Hooks;-><init>()V
                """);

        myFixture.renameElement(kotlin.getDeclarations().getFirst(), "RenamedHooks", false, false);
        assertEquals("""
                const val OWNER = "Lexample/RenamedHooks;"
                val call = "invoke-static {v0}, Lexample/RenamedHooks;->bind(Lexample/RenamedHooks;)Lexample/RenamedHooks;"
                """, patch.getText());
        assertEquals("""
                invoke-static {v0}, Lother/Hooks;->bind([Lexample/RenamedHooks;)Lexample/RenamedHooks;
                invoke-direct {v0}, Lexample/RenamedHooks;-><init>()V
                """, smali.getText());
    }

    public void testRenameKotlinFunctionUpdatesAFullDescriptor() {
        var kotlin = (KtFile) myFixture.addFileToProject("example/Functions.kt", """
                package example
                fun bind(value: Int) {}
                """);
        var patch = myFixture.addFileToProject("Patch.kt", """
                val descriptor = "Lexample/FunctionsKt;->bind(I)V"
                """);

        myFixture.renameElement(kotlin.getDeclarations().getFirst(), "bindValue", false, false);
        assertEquals("""
                val descriptor = "Lexample/FunctionsKt;->bindValue(I)V"
                """, patch.getText());
    }

    public void testRenameJavaNestedClassPreservesKotlinStringEscaping() {
        var owner = myFixture.addClass("""
                package example;
                public class Outer {
                    public static class Inner {}
                }
                """);
        var patch = myFixture.addFileToProject("Patch.kt", """
                val escaped = "Lexample/Outer\\$Inner;"
                val raw = \"""Lexample/Outer${'$'}Inner;\"""
                """);
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-direct {v0}, Lexample/Outer$Inner;-><init>()V
                """);

        myFixture.renameElement(owner.getInnerClasses()[0], "Renamed", false, false);
        assertEquals("""
                val escaped = "Lexample/Outer\\$Renamed;"
                val raw = \"""Lexample/Outer${'$'}Renamed;\"""
                """, patch.getText());
        assertEquals("""
                invoke-direct {v0}, Lexample/Outer$Renamed;-><init>()V
                """, smali.getText());
    }

    public void testGotoDeclarationFindsTheExactOverloadOnANestedClass() {
        myFixture.addFileToProject("example/Hooks.java", """
                package example;
                public class Hooks {
                    public static class Nested {
                        public static void bind(int value) {}
                        public static void bind(long value) {}
                    }
                }
                """);
        var patch = myFixture.configureByText("Patch.kt", """
                private val target = "method: Lexample/Hooks\\$Nested;->b<caret>ind(J)V"
                private val wrongReturnType = "Lexample/Hooks\\$Nested;->bind(J)I"
                """);

        var target = gotoDeclarationTarget();
        assertInstanceOf(target, PsiMethod.class);
        assertEquals("Lexample/Hooks$Nested;->bind(J)V", SmaliUsages.methodKey((PsiMethod) target));

        myFixture.getEditor().getCaretModel().moveToOffset(patch.getText().indexOf("bind(J)I"));
        assertNull(gotoDeclarationTarget());
    }

    public void testGotoDeclarationResolvesInterpolatedCallsToKotlinSource() {
        myFixture.addFileToProject("example/Hooks.kt", """
                package example
                fun bind(value: Int) {}
                """);
        myFixture.configureByText("Patch.kt", """
                private const val HOOK = "Lexample/HooksKt;"
                fun patch(register: Int) = "invoke-static {v$register}, $HOOK->bi<caret>nd(I)V"
                """);

        var target = gotoDeclarationTarget();
        assertInstanceOf(target, KtNamedFunction.class);
        assertEquals("bind", ((KtNamedFunction) target).getName());
        assertEquals("Hooks.kt", target.getContainingFile().getName());
    }

    public void testFullMethodDescriptorConstantAndItsInterpolatedCallsAreUsages() {
        myFixture.addClass("package example.net; public abstract class HttpURLConnection {}");
        myFixture.addClass("package example.io; public abstract class InputStream {}");
        var patch = myFixture.addFileToProject("Patch.kt", """
                private const val HELPER =
                    "Lexample/GlideRedditMediaWayback;->open(Lexample/net/HttpURLConnection;I)Lexample/io/InputStream;"

                fun patch(statusRegister: Int, connection: String) = \"""
                    iget-object v3, p0, $connection
                    invoke-static {v3, v4}, $HELPER
                    invoke-static {v3, v$statusRegister}, $HELPER
                    \"""
                """);
        myFixture.enableInspections(new UnusedDeclarationInspection(true));
        var java = (PsiJavaFile) myFixture.configureByText("GlideRedditMediaWayback.java", """
                package example;
                public class GlideRedditMediaWayback {
                    public static example.io.InputStream open(example.net.HttpURLConnection connection, int code) {
                        return null;
                    }
                    public static example.io.InputStream open(example.net.HttpURLConnection connection) {
                        return null;
                    }
                }
                """);
        var owner = java.getClasses()[0];
        var overloads = owner.getMethods();
        var firstCall = location(patch, "$HELPER");
        var lastCall = patch.getName() + ":" + patch.getText().lastIndexOf("$HELPER");
        assertUsageLocations(owner, Set.of(
                location(patch, "Lexample/GlideRedditMediaWayback;"), firstCall, lastCall));
        assertUsageLocations(overloads[0], Set.of(
                location(patch, "open(Lexample/net/HttpURLConnection;I)"), firstCall, lastCall));
        assertUsageLocations(overloads[1], Set.of());
        var highlights = myFixture.doHighlighting();
        assertFalse(highlights.toString(), isHighlightedAsUnused(owner.getTextOffset(), highlights));
        assertFalse(highlights.toString(), isHighlightedAsUnused(overloads[0], highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(overloads[1], highlights));
    }

    private PsiElement gotoDeclarationTarget() {
        var editor = myFixture.getEditor();
        return GotoDeclarationAction.findTargetElement(getProject(), editor, editor.getCaretModel().getOffset());
    }

    public void testJavaInspectionExemptsOnlyTheReferencedOverload() {
        myFixture.addFileToProject("patches/Hook.kt", """
                package patches
                private const val HOOK = "Lexample/Hooks;"
                fun patch(register: Int) =
                    "invoke-static {v$register}, $HOOK->calledBySmali(I)V"
                """);
        myFixture.enableInspections(new UnusedDeclarationInspection(true));
        var java = (PsiJavaFile) myFixture.configureByText("Hooks.java", """
                package example;
                public class Hooks {
                    public static void calledBySmali(int value) {}
                    public static void calledBySmali(long value) {}
                    public static void dead() {}
                }
                class UnusedClass {}
                """);
        var owner = java.getClasses()[0];
        var hooks = owner.getMethods();
        var provider = new SmaliUsageProvider();
        assertTrue("Smali also uses the owning class", provider.isImplicitUsage(owner));
        assertTrue("Kotlin template resolves the int overload", provider.isImplicitUsage(hooks[0]));
        assertFalse("Kotlin template does not resolve the long overload", provider.isImplicitUsage(hooks[1]));
        var highlights = myFixture.doHighlighting();
        assertFalse(highlights.toString(), isHighlightedAsUnused(owner.getTextOffset(), highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(java.getClasses()[1].getTextOffset(), highlights));
        assertFalse(highlights.toString(), isHighlightedAsUnused(hooks[0], highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(hooks[1], highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(hooks[2], highlights));
    }

    public void testFindUsagesNavigatesToSmaliAndInterpolatedKotlinCallSites() {
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static {v0}, Lexample/Hooks;->bind(I)V
                """);
        var kotlin = myFixture.addFileToProject("Patch.kt", """
                private const val HOOK = "Lexample/Hooks;"
                private const val METHOD = "bind"
                fun patch(register: Int) =
                    "invoke-static {v$register}, $HOOK->$METHOD(I)V\\ninvoke-static {v0}, $HOOK->bind(I)V"
                """);
        var java = (PsiJavaFile) myFixture.configureByText("Hooks.java", """
                package example;
                public class Hooks {
                    public static void bind(int value) {}
                    public static void bind(long value) {}
                }
                """);
        var owner = java.getClasses()[0];
        var methods = owner.findMethodsByName("bind", false);
        assertUsageLocations(owner, Set.of(
                location(smali, "Lexample/Hooks;"),
                location(kotlin, "Lexample/Hooks;"),
                location(kotlin, "$HOOK->$METHOD"),
                location(kotlin, "$HOOK->bind")));
        assertUsageLocations(methods[0], Set.of(
                location(smali, "bind(I)V"),
                location(kotlin, "$METHOD(I)V"),
                location(kotlin, "bind(I)V")));
        assertUsageLocations(methods[1], Set.of());
    }

    public void testUsageHintCombinesJavaAndDescriptorReferencesWithoutMarkingMethodsUsed() {
        var patch = myFixture.addFileToProject("Hook.kt", """
                fun register(type: String, name: String) {}
                fun patch() {
                    register("Lexample/Hooks;", "bind")
                    register("type=[Lexample/Hooks;", "other")
                    // register("Lexample/Hooks;", "bind")
                }
                """);
        var caller = myFixture.addFileToProject("example/Caller.java", """
                package example;
                class Caller {
                    Hooks hook;
                }
                """);
        myFixture.enableInspections(new UnusedDeclarationInspection(true));
        var java = (PsiJavaFile) myFixture.configureByText("Hooks.java", """
                package example;
                public class Hooks {
                    public static void bind(int value) {}
                    public static void bind(long value) {}
                }
                """);
        var owner = java.getClasses()[0];
        var hooks = owner.getMethods();
        assertUsageLocations(owner, Set.of(
                location(caller, "Hooks"),
                location(patch, "Lexample/Hooks;"),
                patch.getName() + ":" + (patch.getText().indexOf("[Lexample/Hooks;") + 1)));
        assertUsageLocations(hooks[0], Set.of());
        assertUsageLocations(hooks[1], Set.of());

        var highlights = myFixture.doHighlighting();
        assertFalse(highlights.toString(), isHighlightedAsUnused(owner.getTextOffset(), highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(hooks[0], highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(hooks[1], highlights));

        HighlightingSettingsPerFile.getInstance(getProject())
                .setHighlightingSettingForRoot(java, FileHighlightingSetting.FORCE_HIGHLIGHTING);
        var editor = myFixture.getEditor();
        var lenses = computeCodeVision(() -> new SmaliUsageCodeVision().computeForEditor(editor, java));
        assertTrue(lenses.toString(), lenses.stream().anyMatch(lens ->
                lens.getFirst().getStartOffset() == owner.getTextRange().getStartOffset()
                        && "3 usages".equals(lens.getSecond().getLongPresentation())));
    }

    public void testCodeVisionShowsStaticHookUsagesAndBaseClassInheritor() {
        var base = myFixture.addClass("""
                package example;
                public abstract class BaseFixSLinksPatch {}
                """);
        myFixture.addClass("package example; public class Link {}");
        myFixture.addFileToProject("Patch.kt", """
                private const val OWNER = "Lexample/sync/FixSLinksPatch;"
                private const val HOOK = "patchResolveSLink(Lexample/Link;)Z"
                fun patch(register: Int) = "invoke-static {v$register}, $OWNER->$HOOK"
                """);
        var java = (PsiJavaFile) myFixture.configureByText("FixSLinksPatch.java", """
                package example.sync;
                import example.BaseFixSLinksPatch;
                import example.Link;
                public class FixSLinksPatch extends BaseFixSLinksPatch {
                    public static boolean patchResolveSLink(Link link) {
                        return link != null;
                    }
                }
                """);
        var owner = java.getClasses()[0];
        var hook = owner.getMethods()[0];
        HighlightingSettingsPerFile.getInstance(getProject())
                .setHighlightingSettingForRoot(java, FileHighlightingSetting.FORCE_HIGHLIGHTING);
        var editor = myFixture.getEditor();
        var lenses = computeCodeVision(() -> new SmaliUsageCodeVision().computeForEditor(editor, java));
        assertTrue(lenses.toString(), lenses.stream().anyMatch(lens ->
                lens.getFirst().getStartOffset() == owner.getTextRange().getStartOffset()
                        && "2 usages".equals(lens.getSecond().getLongPresentation())));
        assertTrue(lenses.toString(), lenses.stream().anyMatch(lens ->
                lens.getFirst().getStartOffset() == hook.getTextRange().getStartOffset()
                        && "1 usage".equals(lens.getSecond().getLongPresentation())));
        assertEquals(JavaBundle.message("code.vision.inheritors.hint", 1), computeCodeVision(
                () -> new JavaInheritorsCodeVisionProvider().getHint(base, base.getContainingFile())));
    }

    private <T> T computeCodeVision(Supplier<T> computation) {
        return TestModeFlags.runWithFlag(CodeVisionHost.Companion.isCodeVisionTestKey(), true,
                () -> PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(
                        () -> ReadAction.compute(computation::get))));
    }

    public void testKotlinInspectionExemptsJvmMethod() {
        var smali = myFixture.addFileToProject("Patch.smali", """
                invoke-static {}, Lexample/HooksKt;->calledBySmali()I
                """);
        myFixture.enableInspections(new UnusedSymbolInspection());
        var kotlin = (KtFile) myFixture.configureByText("Hooks.kt", """
                package example
                fun calledBySmali(): Int = 1
                fun dead(): Int = 2
                """);
        var highlights = myFixture.doHighlighting();
        assertFalse(highlights.toString(), isHighlightedAsUnused(
                kotlin.getText().indexOf("calledBySmali"), highlights));
        assertTrue(highlights.toString(), isHighlightedAsUnused(
                kotlin.getText().indexOf("dead"), highlights));
        assertUsageLocations(kotlin.getDeclarations().getFirst(), Set.of(location(smali, "calledBySmali")));
    }

    public void testRawTemplateResolvesCrossFileConstantAndNestedArrayType() {
        myFixture.addFileToProject("patches/Targets.kt", """
                package patches
                const val HOOK = "Lexample/Hooks;"
                """);
        myFixture.addFileToProject("patches/Patch.kt", """
                package patches
                fun patch(first: Int, last: Int) = \"""
                    invoke-static/range {v$first .. v$last}, $HOOK->bind([Lexample/Hooks${'$'}Nested;)V
                \"""
                """);
        var java = (PsiJavaFile) myFixture.configureByText("Hooks.java", """
                package example;
                public class Hooks {
                    public static class Nested {}
                    public static void bind(Nested[] value) {}
                    public static void bind(Nested value) {}
                }
                """);
        var hooks = java.getClasses()[0].getMethods();
        var provider = new SmaliUsageProvider();
        assertTrue(provider.isImplicitUsage(hooks[0]));
        assertFalse(provider.isImplicitUsage(hooks[1]));
    }

    public void testSmaliFileEditsUpdateUsage() {
        var call = myFixture.addFileToProject("Patch.smali", """
                .class public Lexample/Patch;
                .super Ljava/lang/Object;
                .method public static patch()V
                    .registers 0
                    invoke-static {}, Lexample/Hooks;->used()V
                    return-void
                .end method
                """);
        var java = (PsiJavaFile) myFixture.configureByText("Hooks.java", """
                package example;
                class Hooks {
                    static void used() {}
                }
                """);
        var method = java.getClasses()[0].getMethods()[0];
        var provider = new SmaliUsageProvider();
        assertTrue(provider.isImplicitUsage(method));

        var documents = PsiDocumentManager.getInstance(getProject());
        var document = documents.getDocument(call);
        assertNotNull(document);
        WriteAction.run(() -> document.setText(call.getText().replace("invoke-static", "# invoke-static")));
        documents.commitAllDocuments();

        assertFalse(provider.isImplicitUsage(method));
        assertFalse(provider.isImplicitUsage(method.getContainingClass()));
        assertUsageLocations(method, Set.of());
    }

    private void assertUsageLocations(PsiElement target, Set<String> expected) {
        var usages = myFixture.findUsages(target);
        assertEquals(expected.size(), usages.size());
        assertEquals(expected, usages.stream().collect(Collectors.mapping(
                usage -> usage.getFile().getName() + ":" + usage.getNavigationOffset(), Collectors.toSet())));
    }

    private String location(PsiFile file, String occurrence) {
        int offset = file.getText().indexOf(occurrence);
        assertTrue("Missing fixture text: " + occurrence, offset >= 0);
        return file.getName() + ":" + offset;
    }

    private boolean isHighlightedAsUnused(PsiMethod method, List<HighlightInfo> highlights) {
        var name = method.getNameIdentifier();
        assertNotNull(name);
        return isHighlightedAsUnused(name.getTextOffset(), highlights);
    }

    private boolean isHighlightedAsUnused(int offset, List<HighlightInfo> highlights) {
        return highlights.stream().anyMatch(info ->
                info.getStartOffset() == offset
                        && info.getDescription() != null
                        && info.getDescription().contains("never used"));
    }
}
