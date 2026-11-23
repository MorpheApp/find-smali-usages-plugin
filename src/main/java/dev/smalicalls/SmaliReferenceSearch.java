package dev.smalicalls;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchScopeUtil;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtPsiUtil;

public final class SmaliReferenceSearch extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {
    public SmaliReferenceSearch() {
        super(true);
    }

    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters query,
                             @NotNull Processor<? super PsiReference> consumer) {
        report(query.getElementToSearch(), query.getScopeDeterminedByUser(), consumer);
    }

    public static final class Methods extends QueryExecutorBase<PsiReference, MethodReferencesSearch.SearchParameters> {
        public Methods() {
            super(true);
        }

        @Override
        public void processQuery(@NotNull MethodReferencesSearch.SearchParameters query,
                                 @NotNull Processor<? super PsiReference> consumer) {
            report(query.getMethod(), query.getScopeDeterminedByUser(), consumer);
        }
    }

    private static void report(PsiElement target, SearchScope scope, Processor<? super PsiReference> consumer) {
        if (!target.isValid()) {
            return;
        }
        var manager = PsiManager.getInstance(target.getProject());
        // Patch strings can call hooks across modules with no JVM dependency on the target.
        for (var site : SmaliUsages.find(target)) {
            ProgressManager.checkCanceled();
            var file = manager.findFile(site.file());
            if (file != null && inScope(scope, file, site.range())
                    && !consumer.process(new SmaliReference(file, site.text(), target))) {
                return;
            }
        }
    }

    private static boolean inScope(SearchScope scope, PsiFile file, TextRange range) {
        if (scope instanceof LocalSearchScope local) {
            return local.containsRange(file, range);
        }
        return PsiSearchScopeUtil.isInScope(scope, file);
    }

    private static final class SmaliReference extends PsiReferenceBase<PsiFile> {
        private final PsiElement target;
        private final SmaliReferenceText text;
        private final SmaliReferenceEdit edit;
        private final String classDescriptor;
        private final String packagePrefix;
        private final String className;
        private final boolean fixedMethodName;

        SmaliReference(PsiFile file, SmaliReferenceText text, PsiElement target) {
            super(file, text.range(), true);
            this.target = target;
            this.text = text;
            edit = new SmaliReferenceEdit(file, text.range());
            classDescriptor = classDescriptor(target);
            packagePrefix = target instanceof PsiPackage pkg ? SmaliUsages.packagePrefix(pkg) : null;
            className = target instanceof PsiNamedElement named ? named.getName() : null;
            var kotlin = target.getNavigationElement() instanceof KtNamedFunction function ? function : null;
            fixedMethodName = (target instanceof PsiMethod method && method.isConstructor())
                    || (kotlin != null && (hasJvmName(kotlin)
                    || !text.token().substring(text.decoded()).equals(kotlin.getName())));
        }

        private static String classDescriptor(PsiElement element) {
            return switch (element) {
                case PsiClass owner -> SmaliUsages.classKey(owner);
                case KtClassOrObject owner -> SmaliUsages.classKey(LightClassUtilsKt.toLightClass(owner));
                default -> null;
            };
        }

        private static boolean hasJvmName(KtNamedFunction function) {
            return function.getAnnotationEntries().stream().anyMatch(annotation -> {
                var shortName = annotation.getShortName();
                return shortName != null && ("JvmName".equals(shortName.asString())
                        || function.getContainingKtFile().getImportDirectives().stream().anyMatch(directive ->
                        shortName.asString().equals(directive.getAliasName())
                                && directive.getImportedFqName() != null
                                && "kotlin.jvm.JvmName".equals(directive.getImportedFqName().asString())));
            });
        }

        @Override
        public @NotNull TextRange getRangeInElement() {
            var current = edit.range();
            return current != null ? current : text.range();
        }

        @Override
        public PsiElement resolve() {
            return target;
        }

        @Override
        public PsiElement bindToElement(@NotNull PsiElement element) {
            if (packagePrefix != null && element instanceof PsiPackage pkg) {
                var destination = SmaliUsages.packagePrefix(pkg);
                if (!packagePrefix.equals(destination)) {
                    edit.replace(text.movePackage(packagePrefix, destination));
                }
                return getElement();
            }
            var descriptor = classDescriptor(element);
            if (classDescriptor == null || descriptor == null) {
                return super.bindToElement(element);
            }
            if (!classDescriptor.equals(descriptor)) {
                edit.replace(text.renameClass(classDescriptor, descriptor));
            }
            return getElement();
        }

        @Override
        public PsiElement handleElementRename(@NotNull String newName) {
            if (fixedMethodName) {
                return getElement();
            }
            var name = KtPsiUtil.unquoteIdentifier(newName);
            String replacement;
            if (classDescriptor != null) {
                if (className == null || !classDescriptor.endsWith(className + ";")) {
                    throw new IncorrectOperationException("Cannot rename this JVM class descriptor automatically.");
                }
                String renamed = classDescriptor.substring(0, classDescriptor.length() - className.length() - 1)
                        + name + ";";
                replacement = text.renameClass(classDescriptor, renamed);
            } else {
                replacement = text.replaceToken(name);
            }
            edit.replace(replacement);
            return getElement();
        }
    }
}
