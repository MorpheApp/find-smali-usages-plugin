package dev.smalicalls;

import com.intellij.openapi.util.TextRange;
import com.intellij.util.IncorrectOperationException;

import java.util.function.UnaryOperator;

record SmaliReferenceText(TextRange range, String decoded, TextRange token, Encoding encoding) {
    enum Encoding { SMALI, KOTLIN, KOTLIN_RAW }

    String replaceToken(String replacement) {
        return encode(token.replace(decoded, replacement));
    }

    String renameClass(String oldDescriptor, String newDescriptor) {
        return replaceClasses(descriptor -> descriptor.equals(oldDescriptor) ? newDescriptor : descriptor);
    }

    String movePackage(String oldPath, String newPath) {
        return replaceClasses(descriptor -> descriptor.startsWith(oldPath)
                ? newPath + descriptor.substring(oldPath.length()) : descriptor);
    }

    private String replaceClasses(UnaryOperator<String> remap) {
        var result = new StringBuilder();
        int[] position = {0};
        SmaliCalls.scanClassDescriptors(decoded, (descriptor, location) -> {
            var replacement = remap.apply(descriptor);
            if (!replacement.equals(descriptor)) {
                result.append(decoded, position[0], location.getStartOffset()).append(replacement);
                position[0] = location.getEndOffset();
            }
        });
        if (position[0] == 0) {
            throw new IncorrectOperationException("The Smali class descriptor no longer matches the rename target.");
        }
        return encode(result.append(decoded, position[0], decoded.length()).toString());
    }

    private String encode(String value) {
        return switch (encoding) {
            case SMALI -> value;
            case KOTLIN -> value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\b", "\\b");
            case KOTLIN_RAW -> value.replace("$", "${'$'}").replace("\"", "${'\"'}").replace("\r", "${'\\r'}");
        };
    }
}
