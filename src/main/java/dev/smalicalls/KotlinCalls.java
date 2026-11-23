package dev.smalicalls;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UastFacade;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class KotlinCalls {
    private KotlinCalls() {}

    static boolean mayContainReferences(String text) {
        return text.contains("invoke-") || text.indexOf(';') >= 0 || text.contains("\\u");
    }

    static void scan(KtFile file, BiConsumer<String, SmaliReferenceText> classes,
                     SmaliUsages.MethodSiteConsumer methods) {
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                ProgressManager.checkCanceled();
                if (element instanceof KtStringTemplateExpression template && mayContainReferences(template.getText())) {
                    scanTemplate(template, classes, methods);
                }
                super.visitElement(element);
            }
        });
    }

    private static void scanTemplate(KtStringTemplateExpression template, BiConsumer<String, SmaliReferenceText> classes,
                                     SmaliUsages.MethodSiteConsumer methods) {
        var rendered = render(template);
        var encoding = template.getText().startsWith("\"\"\"")
                ? SmaliReferenceText.Encoding.KOTLIN_RAW : SmaliReferenceText.Encoding.KOTLIN;
        Consumer<SmaliCalls.Call> recordCall = call -> {
            classes.accept(call.owner(), referenceText(rendered, call.ownerRange(), encoding));
            methods.accept(call.target(), referenceText(rendered, call.nameRange(), encoding),
                    referenceText(rendered, call.signatureRange(), encoding));
        };
        SmaliCalls.scan(rendered.text(), recordCall);
        var standalone = SmaliCalls.findStandaloneMethodDescriptor(rendered.text());
        if (standalone != null) {
            recordCall.accept(standalone);
        }
        SmaliCalls.scanClassDescriptors(rendered.text(), (descriptor, range) ->
                classes.accept(descriptor, referenceText(rendered, range, encoding)));
    }

    private static SmaliReferenceText referenceText(RenderedTemplate template, TextRange token,
                                                     SmaliReferenceText.Encoding encoding) {
        int start = token.getStartOffset();
        int end = token.getEndOffset();
        // An interpolation is a single source span, even if only one part of its value is renamed.
        for (var segment : template.segments()) {
            if (!segment.literal() && segment.rendered().intersectsStrict(token)) {
                start = Math.min(start, segment.rendered().getStartOffset());
                end = Math.max(end, segment.rendered().getEndOffset());
            }
        }
        return new SmaliReferenceText(sourceRange(template.segments(), new TextRange(start, end)),
                template.text().substring(start, end), token.shiftRight(-start), encoding);
    }

    static SmaliCalls.Call methodAt(KtStringTemplateExpression template, int offset) {
        var rendered = render(template);
        return SmaliCalls.findMethodDescriptor(rendered.text(), range ->
                sourceRange(rendered.segments(), range).containsOffset(offset));
    }

    private static RenderedTemplate render(KtStringTemplateExpression template) {
        var text = new StringBuilder();
        var segments = new ArrayList<Segment>();
        for (var entry : template.getEntries()) {
            String value = switch (entry) {
                case KtLiteralStringTemplateEntry literal -> literal.getText();
                case KtEscapeStringTemplateEntry escape -> escape.getUnescapedValue();
                default -> {
                    var constant = evaluate(entry.getExpression());
                    // An unknown register is fine; an unknown part of the method descriptor isn't.
                    yield constant == null ? "\u0000" : constant.toString();
                }
            };
            if (!value.isEmpty()) {
                int start = text.length();
                text.append(value);
                segments.add(new Segment(new TextRange(start, text.length()), entry.getTextRange(),
                        entry instanceof KtLiteralStringTemplateEntry));
            }
        }
        return new RenderedTemplate(text.toString(), segments);
    }

    private static TextRange sourceRange(List<Segment> segments, TextRange range) {
        int start = -1;
        for (var segment : segments) {
            var overlap = segment.rendered().intersection(range);
            if (overlap == null || overlap.isEmpty()) {
                continue;
            }
            // An interpolated token belongs to the whole interpolation at the call site.
            var source = segment.literal()
                    ? overlap.shiftRight(segment.source().getStartOffset() - segment.rendered().getStartOffset())
                    : segment.source();
            if (start < 0) {
                start = source.getStartOffset();
            }
            if (overlap.getEndOffset() == range.getEndOffset()) {
                return new TextRange(start, source.getEndOffset());
            }
        }
        throw new IllegalStateException("Smali call range is outside its string template: " + range);
    }

    private record RenderedTemplate(String text, List<Segment> segments) {}

    private record Segment(TextRange rendered, TextRange source, boolean literal) {}

    private static Object evaluate(KtExpression expression) {
        if (expression != null
                && UastFacade.INSTANCE.convertElementWithParent(expression, UExpression.class) instanceof UExpression value) {
            return value.evaluate();
        }
        return null;
    }
}
