package dev.smalicalls;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class SmaliUsageProvider implements ImplicitUsageProvider {
    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        return !SmaliUsages.find(element).isEmpty();
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }
}
