package dev.smalicalls;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SmaliGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
        var template = PsiTreeUtil.getParentOfType(source, KtStringTemplateExpression.class, false);
        if (template == null || DumbService.isDumb(template.getProject())) {
            return null;
        }
        var call = KotlinCalls.methodAt(template, offset);
        if (call == null) {
            return null;
        }

        var project = template.getProject();
        var facade = JavaPsiFacade.getInstance(project);
        var owner = call.owner();
        var binaryName = owner.substring(1, owner.length() - 1).replace('/', '.');
        var matches = findMethods(facade, binaryName, call.target(), GlobalSearchScope.projectScope(project));
        if (matches.isEmpty()) {
            matches = findMethods(facade, binaryName, call.target(), GlobalSearchScope.allScope(project));
        }
        return matches.isEmpty() ? null : matches.toArray(PsiElement[]::new);
    }

    private static Set<PsiElement> findMethods(JavaPsiFacade facade, String binaryName,
                                               String target, GlobalSearchScope scope) {
        var matches = new LinkedHashSet<PsiElement>();
        collectMethods(facade, binaryName, target, scope, matches);
        if (binaryName.indexOf('$') >= 0) {
            collectMethods(facade, binaryName.replace('$', '.'), target, scope, matches);
        }
        return matches;
    }

    private static void collectMethods(JavaPsiFacade facade, String className, String target,
                                       GlobalSearchScope scope, Set<PsiElement> matches) {
        for (var owner : facade.findClasses(className, scope)) {
            for (var method : owner.getMethods()) {
                if (target.equals(SmaliUsages.methodKey(method))) {
                    matches.add(method.getNavigationElement());
                }
            }
        }
    }
}
