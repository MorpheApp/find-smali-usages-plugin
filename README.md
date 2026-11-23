# Find Smali Usages

Android Studio plugin that counts DEX class descriptors in Kotlin strings, such as `"Lexample/Hooks;"`, as class usages regardless of where the string is passed. Smali instructions and standalone method descriptor strings such as `"Lexample/Hooks;->bind(I)V"` also count as usages of the exact JVM method. A class descriptor alone does not mark any methods as used, and a reference to one overload does not hide warnings on other overloads.

Use **Find Usages** on a class or method to jump to the matching descriptors or call sites. With Code Vision's **Usages** enabled, Java declarations referenced from Smali show a clickable total for that declaration, including ordinary references and Smali matches. Leave the IDE's unused-declaration inspections enabled; no annotations or ProGuard changes are needed.

Use **Go to Declaration or Usages** (`Ctrl+B` on the default Windows/Linux keymap) on a complete method descriptor in a Kotlin string, such as `Lexample/Hooks;->bind(I)V`, to jump to the exact overload.

Use **Rename** (`Shift+F6`) on a Java or Kotlin declaration to update recognized Smali references along with normal code references. Only the matching overload is updated. When an interpolated string needs to change, the plugin can substitute the updated text at that site without renaming a shared constant used by other call sites.

Use **Move** or **Change Package** to update recognized class descriptors when moving a Java class, moving a Java package, or changing the package of a Kotlin class. This includes class descriptors in method parameters and return types.

**Change Signature** updates recognized references when the arguments can stay in the same registers and the caller can still handle the result. For example, widening a reference parameter or narrowing a reference return type can be safe. Adding, removing, or reordering parameters, changing register widths, or changing how the result is consumed produces a conflict instead of rewriting the call.

## Build and install

The plugin targets Android Studio builds on the `262` IntelliJ Platform branch. Point Gradle to the installed IDE and run it with JDK 25 (Android Studio's bundled runtime works on Linux):

```sh
export ANDROID_STUDIO_HOME=/path/to/android-studio
export JAVA_HOME="$ANDROID_STUDIO_HOME/jbr"
./gradlew buildPlugin
```

In Android Studio, open **Settings > Plugins > gear icon > Install Plugin from Disk**, select `build/distributions/find-smali-usages-0.9.0.zip`, and restart the IDE.

Kotlin string templates may use compile-time constants in descriptors and computed register names. Targets assembled only at patch time are not inferred. Moving a Kotlin top-level function to another package or file still requires updating its generated JVM owner in Smali manually. Moving or renaming an enclosing class individually does not update references to its nested classes. The plugin does not change shrinking rules.

## License

Find Smali Usages is licensed under the [GNU General Public License, version 3](LICENSE) (`GPL-3.0-only`).
