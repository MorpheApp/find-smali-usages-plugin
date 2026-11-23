package dev.smalicalls;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class SmaliCallsTest {
    @Test
    public void findsCompleteClassDescriptorsInsideStrings() {
        var text = "(ILexample/Outer$Inner;[Lother/Type;)LResult; notLexample/Lookalike; Lmissing/Terminator";
        var descriptors = new HashSet<String>();
        SmaliCalls.scanClassDescriptors(text, (descriptor, range) -> {
            assertEquals(descriptor, range.substring(text));
            descriptors.add(descriptor);
        });
        assertEquals(Set.of("Lexample/Outer$Inner;", "Lother/Type;", "LResult;"), descriptors);
    }

    @Test
    public void recognizesCallsByFullDescriptor() {
        var targets = new HashSet<String>();
        SmaliCalls.scan("""
                # invoke-static {}, Lexample/Hooks;->commented()V
                const-string v0, "invoke-static {}, Lexample/Hooks;->quoted()V"
                invoke-static {v0}, Lexample/Hooks;->bind(I)V  # inline comment
                invoke-virtual/range {v0 .. v2}, Lexample/Hooks$Inner;->read([ILjava/lang/String;)[Ljava/lang/String;
                invoke-direct {v0}, Lexample/Hooks;-><init>()V
                invoke-static {}, Lexample/Hooks;->unresolved()V\u0000
                """, call -> targets.add(call.target()));
        assertEquals(Set.of(
                "Lexample/Hooks;->bind(I)V",
                "Lexample/Hooks$Inner;->read([ILjava/lang/String;)[Ljava/lang/String;",
                "Lexample/Hooks;-><init>()V"
        ), targets);
    }
}
