# ================================================================
# Pulse Messenger — R8 / ProGuard rules
# ----------------------------------------------------------------
# Debug builds do not run R8 (minifyEnabled false), so this file's
# only impact is on `assembleRelease`. However, it MUST exist now
# because app/build.gradle already references it. Missing file =
# Gradle configuration failure before compilation even begins.
#
# Every rule below is here for a concrete reason. Do not delete
# rules "to see if the release build gets smaller" — the failure
# mode is silent runtime breakage (missing Firestore fields, empty
# Glide images, blank chat list, etc.), not a build error.
# ================================================================


# ----------------------------------------------------------------
# 1. Keep line numbers for meaningful crash reports, but hide the
#    original source file name so it isn't leaked into stack traces.
# ----------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ----------------------------------------------------------------
# 2. Annotations & generic signatures.
#    Firestore, Glide, and Media3 all use annotations at runtime
#    (@Keep, @DocumentId, @PropertyName, @ServerTimestamp).
#    Without these attributes R8 strips them and reflection fails.
# ----------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions


# ----------------------------------------------------------------
# 3. Application model classes — Firestore POJO mapping.
#    Firestore deserializes Message, Conversation, User from maps
#    using reflection on FIELD NAMES. R8 will rename those fields
#    (e.g. "senderId" -> "a") unless we explicitly keep them.
#    We scope the rule to the model package so we don't blanket-keep
#    the entire app.
# ----------------------------------------------------------------
-keep class com.pulse.messenger.model.** { *; }


# ----------------------------------------------------------------
# 4. Firebase Firestore.
#    These rules match the ones Google publishes in the firebase-
#    firestore AAR. Repeating them here is intentional — the AAR
#    rules are consumer rules that R8 aggregates, but listing them
#    explicitly makes the file self-documenting and protects the
#    project if a future BoM update drops a rule by accident.
# ----------------------------------------------------------------
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.firestore.model.** { *; }
-dontwarn com.google.firebase.firestore.**


# ----------------------------------------------------------------
# 5. Firebase Auth + Credential Manager + Google ID.
#    -keep keeps the generated AuthCredential classes that
#    GoogleAuthProvider uses by reflection.
# ----------------------------------------------------------------
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

-keep class androidx.credentials.** { *; }
-keep interface androidx.credentials.** { *; }
-dontwarn androidx.credentials.**


# ----------------------------------------------------------------
# 6. Firebase Realtime Database (presence) and Messaging (FCM).
# ----------------------------------------------------------------
-keep class com.google.firebase.database.** { *; }
-keep class com.google.firebase.messaging.** { *; }
-dontwarn com.google.firebase.database.**
-dontwarn com.google.firebase.messaging.**


# ----------------------------------------------------------------
# 7. Firebase Storage.
#    Note: UploadTask / FileDownloadTask use internal listeners
#    that must survive R8.
# ----------------------------------------------------------------
-keep class com.google.firebase.storage.** { *; }
-dontwarn com.google.firebase.storage.**


# ----------------------------------------------------------------
# 8. Glide.
#    Glide uses reflection to instantiate generated request
#    factories and to detect optional integrations. Without these
#    rules, images silently fail to load in release builds.
# ----------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-dontwarn com.bumptech.glide.**


# ----------------------------------------------------------------
# 9. Media3 / ExoPlayer.
#    ExoPlayer discovers extractors and renderers via reflection.
# ----------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**


# ----------------------------------------------------------------
# 10. Material Components.
#     MaterialBottomSheetDialog and MaterialDialogs inflate views
#     via reflection on the constructor (Context, AttributeSet).
# ----------------------------------------------------------------
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-dontwarn androidx.**


# ----------------------------------------------------------------
# 11. AndroidX Lifecycle / ViewModel.
#     LiveData and ViewModel restoration relies on generic
#     signatures and reflection.
# ----------------------------------------------------------------
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**


# ----------------------------------------------------------------
# 12. RecyclerView adapters.
#     ViewBinding-inflated ViewHolders are constructed by
#     RecyclerView via reflection and must be kept.
# ----------------------------------------------------------------
-keep class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public <init>(android.view.View);
}


# ----------------------------------------------------------------
# 13. ViewBinding.
#     Generated ViewBinding classes are referenced by name in
#     generated code, but keeping them costs nothing and prevents
#     obscure "no binding class" failures after R8 changes.
# ----------------------------------------------------------------
-keep class com.pulse.messenger.databinding.** { *; }


# ----------------------------------------------------------------
# 14. Enum safety.
#     Firestore stores enums (MessageType) as strings; R8's enum
#     inlining would break valueOf() during deserialization.
# ----------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ----------------------------------------------------------------
# 15. Java reflection metadata kept for any class that uses
#     annotations at runtime (defensive catch-all).
# ----------------------------------------------------------------
-keep @interface *
-dontwarn sun.misc.**
-dontwarn javax.annotation.**


# ----------------------------------------------------------------
# 16. Kotlin metadata — not used in this project, but the Firebase
#     BoM pulls in Kotlin-stdlib transitively and R8 warns about
#     missing kotlin.Metadata. Suppress so release builds finish
#     with zero warnings (keeps the Termux output readable).
# ----------------------------------------------------------------
-dontwarn kotlin.**
-dontwarn kotlinx.**


# ----------------------------------------------------------------
# 17. OkHttp / Conscrypt / BouncyCastle warnings emitted by the
#     Firebase Storage AAR. These classes are runtime-optional; the
#     Android platform provides its own TLS stack.
# ----------------------------------------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**


# ----------------------------------------------------------------
# 18. Tink — used internally by Google ID / Credential Manager.
# ----------------------------------------------------------------
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
