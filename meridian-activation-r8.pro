# Maximum full-app R8 obfuscation & shrinking for Meridian.
# Strips SourceFile, LineNumberTable, and LocalVariableTable while keeping only the
# minimum runtime reflection/XML/JNI entry points required for stability.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-repackageclasses p
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively
-renamesourcefileattribute ""

# Strip Android Log calls so licensing/activation log tags and messages cannot guide reverse engineering.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
}

# Preserve Android XML-instantiated Fragment, Preference, and custom View class names and
# constructors only (their internal methods and fields remain obfuscated).
-keep class * extends androidx.fragment.app.Fragment {
    public <init>();
}
-keep class * extends androidx.preference.Preference {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# Preserve explicit reflection / native annotations and JNI bridges.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
    @com.google.android.apps.common.proguard.UsedByReflection *;
    @com.google.android.apps.common.proguard.UsedByNative *;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.google.android.accessibility.luamemory.LuaMemory {
    native <methods>;
}
-keep class io.github.aaron_gh.sandfly.ai.SandflyAiEntry {
    public <init>();
}

# Preserve AIDL Binder interfaces, LuaJava JFunction callbacks, Parcelable, Enum, and Gson models.
-keep class * implements android.os.IInterface {
    *;
}
-keepclassmembers class * implements party.iroiro.luajava.JFunction {
    public int __call(party.iroiro.luajava.Lua);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.android.accessibility.gemineye.screenoverview.json.** {
    <fields>;
    <init>(...);
}

# Preserve third-party native / JNI libraries.
-keep class ai.onnxruntime.** { *; }
-keep class com.googlecode.tesseract.** { *; }
-keep class com.k2fsa.sherpa.** { *; }
-keep class party.iroiro.luajava.** { *; }
-keep class org.luaj.** { *; }
-keep class zxingcpp.** { *; }
-keep class com.google.ai.edge.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep EndpointCodec.decode obfuscated but callable.
-keepclassmembers,allowobfuscation class io.meridian.licensing.EndpointCodec {
    public static java.lang.String decode(java.lang.String, int);
}

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineManager
-dontwarn org.luaj.**
-dontwarn party.iroiro.luajava.**
-dontwarn ai.onnxruntime.**
-dontwarn com.googlecode.tesseract.**
-dontwarn com.k2fsa.sherpa.**
