package dev.smalicalls;

import com.intellij.openapi.util.TextRange;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SmaliCalls {
    private static final String OBJECT = "L[^\\s;()\\[\\u0000]+;";
    private static final String TYPE = "\\[*(?:[ZBSCIJFD]|" + OBJECT + ")";
    private static final String NAME = "(?:<init>|<clinit>|[^\\s();<>/\\[\\u0000]+)";
    private static final String PROTOTYPE = "\\((?:" + TYPE + ")*\\)(?:V|" + TYPE + ")";
    private static final String CLASS_NAME_CHARS = "\\p{L}\\p{M}\\p{N}\\p{Pc}\\p{Sc}-";
    private static final String CLASS_SEGMENT = "[" + CLASS_NAME_CHARS + "]+";
    private static final String CLASS_DESCRIPTOR = "L" + CLASS_SEGMENT + "(?:/" + CLASS_SEGMENT + ")*;";
    private static final Pattern CLASS_IN_STRING = Pattern.compile(
            "(?<![/" + CLASS_NAME_CHARS + "])[ZBSCIJFD]*(" + CLASS_DESCRIPTOR + ")"
    );
    private static final Pattern METHOD_IN_STRING = Pattern.compile(
            "(?<![/\\u0000" + CLASS_NAME_CHARS + "])" + methodTarget(CLASS_DESCRIPTOR)
                    + "(?![\\u0000" + CLASS_NAME_CHARS + "])"
    );
    private static final Pattern CALL = Pattern.compile(
            "^[\\t ]*invoke-(?:virtual|super|direct|static|interface|polymorphic)(?:/range)?"
                    + "[\\t ]+\\{[^}\\r\\n]*}[\\t ]*,[\\t ]*"
                    + methodTarget(OBJECT)
                    + "(?:[\\t ]*,[\\t ]*" + PROTOTYPE + ")?[\\t ]*(?:#[^\\r\\n]*)?$",
            Pattern.MULTILINE
    );

    private SmaliCalls() {}

    private static String methodTarget(String ownerPattern) {
        return "(?<target>(?<owner>" + ownerPattern + ")->(?<name>" + NAME + ")" + PROTOTYPE + ")";
    }

    static void scanClassDescriptors(String text, BiConsumer<String, TextRange> consumer) {
        var descriptors = CLASS_IN_STRING.matcher(text);
        while (descriptors.find()) {
            consumer.accept(descriptors.group(1), new TextRange(descriptors.start(1), descriptors.end(1)));
        }
    }

    static void scan(String text, Consumer<Call> consumer) {
        var calls = CALL.matcher(text);
        while (calls.find()) {
            consumer.accept(toCall(calls));
        }
    }

    static Call findMethodDescriptor(String text, Predicate<TextRange> acceptsRange) {
        var descriptors = METHOD_IN_STRING.matcher(text);
        while (descriptors.find()) {
            if (acceptsRange.test(new TextRange(descriptors.start(), descriptors.end()))) {
                return toCall(descriptors);
            }
        }
        return null;
    }

    static Call findStandaloneMethodDescriptor(String text) {
        return findMethodDescriptor(text, range -> text.substring(0, range.getStartOffset()).isBlank()
                && text.substring(range.getEndOffset()).isBlank());
    }

    private static Call toCall(Matcher matcher) {
        return new Call(matcher.group("owner"), matcher.group("target"),
                new TextRange(matcher.start("owner"), matcher.end("owner")),
                new TextRange(matcher.start("name"), matcher.end("name")));
    }

    record Call(String owner, String target, TextRange ownerRange, TextRange nameRange) {
        TextRange signatureRange() {
            return new TextRange(nameRange.getStartOffset(), ownerRange.getStartOffset() + target.length());
        }
    }
}
