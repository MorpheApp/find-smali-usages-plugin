package dev.smalicalls;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiFileRange;
import com.intellij.util.IncorrectOperationException;

final class SmaliReferenceEdit {
    private final SmartPsiFileRange location;
    private final String originalText;

    SmaliReferenceEdit(PsiFile file, TextRange range) {
        location = SmartPointerManager.getInstance(file.getProject()).createSmartPsiFileRangePointer(file, range);
        originalText = file.getViewProvider().getContents().subSequence(range.getStartOffset(), range.getEndOffset()).toString();
    }

    TextRange range() {
        var current = location.getRange();
        return current == null ? null : TextRange.create(current);
    }

    void replace(String replacement) {
        var file = location.getElement();
        if (file == null) {
            throw new IncorrectOperationException("The Smali reference file is no longer available.");
        }
        var documents = PsiDocumentManager.getInstance(file.getProject());
        var document = documents.getDocument(file);
        if (document == null) {
            throw new IncorrectOperationException("The Smali reference location is no longer editable.");
        }
        documents.doPostponedOperationsAndUnblockDocument(document);
        var range = range();
        if (range == null) {
            throw new IncorrectOperationException("The Smali reference location changed during refactoring.");
        }
        var current = document.getText(range);
        if (!current.equals(replacement)) {
            if (!current.equals(originalText)) {
                throw new IncorrectOperationException("The Smali reference changed during refactoring.");
            }
            document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        }
    }
}
