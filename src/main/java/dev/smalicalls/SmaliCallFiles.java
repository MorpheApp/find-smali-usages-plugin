package dev.smalicalls;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorIntegerDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

public final class SmaliCallFiles extends ScalarIndexExtension<Integer> {
    static final ID<Integer, Void> INDEX_ID = ID.create("dev.smalicalls.files");
    static final int HAS_REFERENCES = 1;

    @Override
    public @NotNull ID<Integer, Void> getName() {
        return INDEX_ID;
    }

    @Override
    public @NotNull DataIndexer<Integer, Void, FileContent> getIndexer() {
        return content -> {
            var text = content.getContentAsText().toString();
            boolean candidate = "smali".equals(content.getFile().getExtension())
                    ? text.contains("invoke-")
                    : KotlinCalls.mayContainReferences(text);
            return candidate ? Collections.singletonMap(HAS_REFERENCES, null) : Map.of();
        };
    }

    @Override
    public @NotNull KeyDescriptor<Integer> getKeyDescriptor() {
        return EnumeratorIntegerDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return file -> switch (file.getExtension()) {
            case "kt", "kts", "smali" -> !file.getFileType().isBinary();
            case null, default -> false;
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 3;
    }
}
