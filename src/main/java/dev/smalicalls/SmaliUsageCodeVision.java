package dev.smalicalls;

import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering;
import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds;
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase;
import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesHandlerFactory.OperationMode;
import com.intellij.find.findUsages.FindUsagesManager;
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.RefactoringUiService;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.UsageViewManager;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class SmaliUsageCodeVision extends CodeVisionProviderBase {
    @Override
    public @NotNull String getId() {
        return "dev.smalicalls.usages";
    }

    @Override
    public @NotNull String getGroupId() {
        return PlatformCodeVisionIds.USAGES.getKey();
    }

    @Override
    public @NotNull String getName() {
        return "Usages including Smali";
    }

    @Override
    public @NotNull List<CodeVisionRelativeOrdering> getRelativeOrderings() {
        return List.of();
    }

    @Override
    public boolean acceptsFile(@NotNull PsiFile file) {
        return file instanceof PsiJavaFile;
    }

    @Override
    public boolean acceptsElement(@NotNull PsiElement element) {
        return element instanceof PsiClass || element instanceof PsiMethod;
    }

    @Override
    public String getHint(@NotNull PsiElement element, @NotNull PsiFile file) {
        // Java's built-in usage hint skips implicit entry points, even when Find Usages has results.
        if (SmaliUsages.find(element).isEmpty()) {
            return null;
        }
        var handler = findUsagesHandler(element);
        if (handler == null) {
            return null;
        }
        // Resolving interactive Find Usages targets can prompt for a base method on the EDT.
        var primary = new PsiElement[]{element};
        var options = handler.getFindUsagesOptions().clone();
        UsageTarget[] targets = {new PsiElement2UsageTargetAdapter(element, options, false)};
        var searcher = FindUsagesManager.createUsageSearcher(
                handler, primary, PsiElement.EMPTY_ARRAY, options);
        var total = new AtomicInteger();
        Runnable countUsages = () -> searcher.generate(usage -> {
            if (!UsageViewManager.isSelfUsage(usage, targets)) {
                total.incrementAndGet();
            }
            return true;
        });
        if (ProgressManager.getGlobalProgressIndicator() == null) {
            ProgressManager.getInstance().runProcess(countUsages, new EmptyProgressIndicator());
        } else {
            countUsages.run();
        }
        int count = total.get();
        return switch (count) {
            case 0 -> null;
            case 1 -> "1 usage";
            default -> count + " usages";
        };
    }

    @Override
    public void handleClick(@NotNull Editor editor, @NotNull PsiElement element, @NotNull MouseEvent event) {
        if (element.isValid()) {
            var handler = findUsagesHandler(element);
            if (handler != null) {
                RefactoringUiService.getInstance().startFindUsages(element, handler.getFindUsagesOptions().clone());
            }
        }
    }

    private static FindUsagesHandler findUsagesHandler(PsiElement element) {
        for (var factory : FindUsagesHandlerFactory.EP_NAME.getExtensionList(element.getProject())) {
            if (factory.canFindUsages(element)) {
                var handler = factory.createFindUsagesHandler(element, OperationMode.DEFAULT);
                if (handler != null) {
                    return handler == FindUsagesHandler.NULL_HANDLER ? null : handler;
                }
            }
        }
        return null;
    }
}
