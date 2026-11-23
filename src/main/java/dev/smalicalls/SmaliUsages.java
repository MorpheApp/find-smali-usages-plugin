package dev.smalicalls;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtFunction;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

final class SmaliUsages {
    private static final Key<CachedValue<Map<String, Set<Site>>>> SITES = Key.create("dev.smalicalls.sites");

    private SmaliUsages() {}

    static Collection<Site> find(PsiElement element) {
        if (!(element instanceof PsiClass || element instanceof PsiMethod
                || element instanceof KtClassOrObject || element instanceof KtFunction || element instanceof PsiPackage)) {
            return List.of();
        }
        var project = element.getProject();
        if (DumbService.isDumb(project)) {
            return List.of();
        }
        var sites = CachedValuesManager.getManager(project).getCachedValue(project, SITES, () ->
                CachedValueProvider.Result.create(
                        collectSites(project),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        ProjectRootModificationTracker.getInstance(project),
                        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
                ), false);
        if (sites.isEmpty()) {
            return List.of();
        }
        return switch (element) {
            case PsiClass owner -> forKey(sites, classKey(owner));
            case PsiMethod method -> forMethod(sites, method);
            case KtClassOrObject owner -> forKey(sites, classKey(LightClassUtilsKt.toLightClass(owner)));
            case PsiPackage pkg -> {
                record Location(VirtualFile file, TextRange range) {}
                var matches = new LinkedHashMap<Location, Site>();
                if (!pkg.getQualifiedName().isEmpty()) {
                    var prefix = packagePrefix(pkg);
                    var facade = JavaPsiFacade.getInstance(project);
                    var scope = GlobalSearchScope.projectScope(project);
                    sites.forEach((key, entries) -> {
                        ProgressManager.checkCanceled();
                        if (key.startsWith(prefix) && !key.contains("->") && isProjectClass(key, facade, scope)) {
                            for (var entry : entries) {
                                matches.putIfAbsent(new Location(entry.file(), entry.range()), entry);
                            }
                        }
                    });
                }
                yield matches.values();
            }
            case KtFunction function -> {
                var matches = new LinkedHashSet<Site>();
                for (var method : LightClassUtilsKt.toLightMethods(function)) {
                    matches.addAll(forMethod(sites, method));
                }
                yield matches;
            }
            default -> List.of();
        };
    }

    private static Set<Site> forKey(Map<String, Set<Site>> sites, String key) {
        return key == null ? Set.of() : sites.getOrDefault(key, Set.of());
    }

    static String classKey(PsiClass owner) {
        var name = owner == null ? null : ClassUtil.getJVMClassName(owner);
        return name == null ? null : "L" + name.replace('.', '/') + ";";
    }

    static String packagePrefix(PsiPackage pkg) {
        var name = pkg.getQualifiedName();
        return name.isEmpty() ? "L" : "L" + name.replace('.', '/') + "/";
    }

    private static boolean isProjectClass(String key, JavaPsiFacade facade, GlobalSearchScope scope) {
        var binaryName = key.substring(1, key.length() - 1).replace('/', '.');
        return hasClassKey(key, facade.findClasses(binaryName, scope))
                || (binaryName.indexOf('$') >= 0 && hasClassKey(key, facade.findClasses(binaryName.replace('$', '.'), scope)));
    }

    private static boolean hasClassKey(String key, PsiClass[] classes) {
        for (var owner : classes) {
            if (key.equals(classKey(owner))) {
                return true;
            }
        }
        return false;
    }

    private static Set<Site> forMethod(Map<String, Set<Site>> sites, PsiMethod method) {
        return forKey(sites, methodKey(method));
    }

    static String methodKey(PsiMethod method) {
        var owner = classKey(method.getContainingClass());
        var name = method.isConstructor() ? "<init>" : method.getName();
        return owner == null ? null : owner + "->" + name + ClassUtil.getAsmMethodSignature(method);
    }

    private static Map<String, Set<Site>> collectSites(Project project) {
        var sites = new HashMap<String, Set<Site>>();
        var psiManager = PsiManager.getInstance(project);
        var candidates = FileBasedIndex.getInstance().getContainingFiles(
                SmaliCallFiles.INDEX_ID, SmaliCallFiles.HAS_REFERENCES, GlobalSearchScope.projectScope(project));
        for (var candidate : candidates) {
            ProgressManager.checkCanceled();
            var file = psiManager.findFile(candidate);
            BiConsumer<String, SmaliReferenceText> recordSite = (target, text) ->
                    sites.computeIfAbsent(target, key -> new LinkedHashSet<>()).add(new Site(candidate, text, null));
            MethodSiteConsumer recordMethod = (target, text, signature) ->
                    sites.computeIfAbsent(target, key -> new LinkedHashSet<>()).add(new Site(candidate, text, signature));
            if (file instanceof KtFile ktFile) {
                KotlinCalls.scan(ktFile, recordSite, recordMethod);
            } else if (file != null && "smali".equals(candidate.getExtension())) {
                var source = file.getText();
                SmaliCalls.scan(source, call -> {
                    recordMethod.accept(call.target(), smaliReference(source, call.nameRange()),
                            smaliReference(source, call.signatureRange()));
                    SmaliCalls.scanClassDescriptors(call.target(), (descriptor, range) -> recordSite.accept(
                            descriptor, smaliReference(source, range.shiftRight(call.ownerRange().getStartOffset()))));
                });
            }
        }
        return sites;
    }

    private static SmaliReferenceText smaliReference(String source, TextRange range) {
        return new SmaliReferenceText(range, range.substring(source), TextRange.create(0, range.getLength()),
                SmaliReferenceText.Encoding.SMALI);
    }

    @FunctionalInterface
    interface MethodSiteConsumer {
        void accept(String target, SmaliReferenceText name, SmaliReferenceText signature);
    }

    record Site(VirtualFile file, SmaliReferenceText text, SmaliReferenceText signature) {
        TextRange range() {
            return text.range();
        }
    }
}
