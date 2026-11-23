package dev.smalicalls;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.refactoring.changeSignature.ChangeInfo;
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor;
import com.intellij.refactoring.changeSignature.JavaChangeInfo;
import com.intellij.refactoring.rename.ResolveSnapshotProvider;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.kotlin.analysis.api.AnalyzeKt;
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility;
import org.jetbrains.kotlin.analysis.api.types.KaErrorType;
import org.jetbrains.kotlin.analysis.api.types.KaTypeMappingMode;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtPsiUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

public final class SmaliChangeSignature implements ChangeSignatureUsageProcessor {
    private static final Pattern JVM_TYPE = Pattern.compile("\\[*(?:[ZBSCIJFD]|L[^;]+;)|V");

    @Override
    public UsageInfo[] findUsages(ChangeInfo change) {
        if (change.isGenerateDelegate() || !affectsCallers(change)) {
            return UsageInfo.EMPTY_ARRAY;
        }
        var target = change.getMethod();
        var plans = new LinkedHashMap<SmaliUsages.Site, Plan>();
        var directSites = SmaliUsages.find(target);
        if (!directSites.isEmpty()) {
            var plan = plan(change);
            directSites.forEach(site -> plans.put(site, plan));
        }
        for (var method : jvmMethods(target)) {
            if (PsiUtil.canBeOverridden(method)) {
                var manual = Plan.manual("Check how the change affects the overriding method's JVM signature.");
                OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(target.getProject()), true)
                        .forEach(override -> {
                            SmaliUsages.find(override).forEach(site -> plans.putIfAbsent(site, manual));
                            return true;
                        });
            }
        }
        var usages = new ArrayList<UsageInfo>();
        var manager = PsiManager.getInstance(target.getProject());
        plans.forEach((site, plan) -> {
            var file = manager.findFile(site.file());
            if (file != null && site.signature() != null) {
                usages.add(new SignatureUsage(file, site.signature(), plan));
            }
        });
        return usages.toArray(UsageInfo[]::new);
    }

    private static List<PsiMethod> jvmMethods(PsiElement target) {
        return target instanceof PsiMethod method ? List.of(method) : LightClassUtilsKt.toLightMethods(target);
    }

    private static boolean affectsCallers(ChangeInfo change) {
        return change.isNameChanged() || change.isParameterSetOrOrderChanged() || change.isParameterTypesChanged()
                || change.isReturnTypeChanged()
                || (change instanceof JavaChangeInfo java && java.isVisibilityChanged())
                || (change instanceof KotlinChangeInfo kotlin && (kotlin.isReceiverTypeChanged()
                || kotlin.isVisibilityChanged()));
    }

    private static Plan plan(ChangeInfo change) {
        if (change.isParameterSetOrOrderChanged()) {
            return Plan.manual("Changing the number or order of parameters requires updating Smali registers.");
        }
        var target = change.getMethod();
        var methods = jvmMethods(target);
        if (methods.size() != 1) {
            return Plan.manual("The declaration does not have one unambiguous JVM signature.");
        }
        var method = methods.getFirst();
        var old = method.getParameterList().getParameters();
        var proposed = change.getNewParameters();
        if (proposed.length != old.length) {
            return Plan.manual("The JVM parameter list does not map directly to the source parameters.");
        }
        var oldReturn = method.isConstructor() ? PsiTypes.voidType() : method.getReturnType();
        PsiType newReturn = oldReturn;
        var newTypes = new PsiType[old.length];
        String newName = method.isConstructor() ? "<init>" : change.isNameChanged() ? change.getNewName() : method.getName();
        if (change instanceof JavaChangeInfo java) {
            if (java.isVisibilityChanged() && !PsiModifier.PUBLIC.equals(java.getNewVisibility())) {
                return Plan.manual("The new visibility may make the method inaccessible to Smali callers.");
            }
            if (java.wasVararg() != java.isRetainsVarargs() || java.isObtainsVarags()) {
                return Plan.manual("Changing varargs may require updating Smali arguments.");
            }
            for (int i = 0; i < old.length; i++) {
                newTypes[i] = java.getNewParameters()[i].createType(method);
            }
            if (java.isReturnTypeChanged() && java.getNewReturnType() != null) {
                newReturn = java.getNewReturnType().getType(method);
            }
        } else if (change instanceof KotlinChangeInfo kotlin && target instanceof KtNamedFunction function) {
            if (kotlin.isReceiverTypeChanged() || kotlin.getReceiverParameterInfo() != null
                    || function.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                return Plan.manual("Receiver or suspend signatures may require updating Smali arguments.");
            }
            if (kotlin.isVisibilityChanged() && kotlin.getANewVisibility() != KaSymbolVisibility.PUBLIC) {
                return Plan.manual("The new visibility may make the method inaccessible to Smali callers.");
            }
            if (!method.getName().equals(function.getName()) || !function.getAnnotationEntries().isEmpty()) {
                return Plan.manual("This Kotlin function may have a different or generated JVM signature.");
            }
            newName = KtPsiUtil.unquoteIdentifier(newName);
            for (int i = 0; i < old.length; i++) {
                var parameter = kotlin.getNewParameters()[i];
                newTypes[i] = parameter.getCurrentType().getText().equals(parameter.getOriginalType().getText())
                        ? old[i].getType() : kotlinType(function, parameter.getTypeText(), false);
            }
            if (kotlin.isReturnTypeChanged()) {
                newReturn = kotlinType(function, kotlin.getNewReturnTypeInfo().getText(), true);
            }
        } else {
            return Plan.manual("The new JVM signature cannot be determined safely.");
        }
        var signature = new StringBuilder(newName).append('(');
        for (int i = 0; i < old.length; i++) {
            if (proposed[i].getOldIndex() != i || !accepts(newTypes[i], old[i].getType())) {
                return Plan.manual("The new parameter types may require different Smali register values.");
            }
            signature.append(ClassUtil.getBinaryPresentation(newTypes[i]));
        }
        if (!accepts(oldReturn, newReturn)) {
            return Plan.manual("The new return type may require different Smali result handling.");
        }
        return new Plan(signature.append(')').append(ClassUtil.getBinaryPresentation(newReturn)).toString(), null);
    }

    private static PsiType kotlinType(KtNamedFunction function, String text, boolean returnType) {
        var typeRef = new KtPsiFactory(function.getProject()).createTypeCodeFragment(text, function).getContentElement();
        if (typeRef == null) {
            return null;
        }
        return AnalyzeKt.analyze(typeRef, session -> {
            var type = session.getType(typeRef);
            if (type instanceof KaErrorType || (session.getExpandedSymbol(type) instanceof KaNamedClassSymbol symbol
                    && symbol.isInline())) {
                return null;
            }
            if (returnType && session.isUnitType(type) && !session.isNullable(type)) {
                return PsiTypes.voidType();
            }
            return session.asPsiType(type, function, false,
                    returnType ? KaTypeMappingMode.DEFAULT : KaTypeMappingMode.VALUE_PARAMETER, false, null, false, false);
        });
    }

    private static boolean accepts(PsiType expected, PsiType actual) {
        if (expected == null || actual == null) {
            return false;
        }
        var expectedDescriptor = ClassUtil.getBinaryPresentation(expected);
        var actualDescriptor = ClassUtil.getBinaryPresentation(actual);
        if (!JVM_TYPE.matcher(expectedDescriptor).matches() || !JVM_TYPE.matcher(actualDescriptor).matches()) {
            return false;
        }
        return expectedDescriptor.equals(actualDescriptor)
                || (!(expected instanceof PsiPrimitiveType) && !(actual instanceof PsiPrimitiveType)
                && TypeConversionUtil.isAssignable(TypeConversionUtil.erasure(expected), TypeConversionUtil.erasure(actual)));
    }

    @Override
    public MultiMap<PsiElement, String> findConflicts(ChangeInfo change, Ref<UsageInfo[]> usages) {
        var conflicts = new MultiMap<PsiElement, String>();
        for (var info : usages.get()) {
            if (info instanceof SignatureUsage usage && usage.plan.conflict() != null && usage.getElement() != null) {
                conflicts.putValue(usage.getElement(), usage.plan.conflict() + " Update Smali references manually.");
            }
        }
        return conflicts;
    }

    @Override
    public boolean processUsage(ChangeInfo change, UsageInfo info, boolean beforeMethodChange, UsageInfo[] usages) {
        if (!(info instanceof SignatureUsage usage)) {
            return false;
        }
        if (!beforeMethodChange && usage.plan.conflict() == null) {
            usage.edit.replace(usage.signature.replaceToken(usage.plan.signature()));
        }
        return true;
    }

    @Override
    public boolean processPrimaryMethod(ChangeInfo change) {
        return false;
    }

    @Override
    public boolean shouldPreviewUsages(ChangeInfo change, UsageInfo[] usages) {
        return false;
    }

    @Override
    public boolean setupDefaultValues(ChangeInfo change, Ref<UsageInfo[]> usages, Project project) {
        return true;
    }

    @Override
    public void registerConflictResolvers(List<? super ResolveSnapshotProvider.ResolveSnapshot> snapshots,
                                          ResolveSnapshotProvider provider, UsageInfo[] usages, ChangeInfo change) {}

    private record Plan(String signature, String conflict) {
        static Plan manual(String reason) {
            return new Plan(null, reason);
        }
    }

    private static final class SignatureUsage extends UsageInfo {
        private final SmaliReferenceText signature;
        private final SmaliReferenceEdit edit;
        private final Plan plan;

        private SignatureUsage(PsiFile file, SmaliReferenceText signature, Plan plan) {
            super(file, signature.range(), false);
            this.signature = signature;
            this.plan = plan;
            edit = new SmaliReferenceEdit(file, signature.range());
        }
    }
}
