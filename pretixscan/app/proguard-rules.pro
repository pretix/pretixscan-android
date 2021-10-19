# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Don't obfuscate, to make reading crash logs easier
-dontobfuscate

# Don't remove stuff that breaks DGC validation
-keep class j$.time.*
-keep class j$.time.zone.** { *; }

# Don't remove stuff that breaks our database usage
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }

# Don't remove stuff that breaks other stuff
-keep class org.bouncycastle.** { *; }
-keep class org.conscript.** { *; }

# Don't remove our own stuff
# This prevents us from listing everything on its own but obviously limits the optimization
# Previous stuff that failed:
# - Missing constructors used by Jackson
# - Missing fragment constructors
# - Missing JavaScript interfaces
-keep class eu.pretix.pretixscan.** { *; }
-keepclassmembers class eu.pretix.pretixscan.** { *; }
-keep class eu.pretix.libpretixsync.** { *; }
-keepclassmembers class eu.pretix.libpretixsync.** { *; }

# If we manually implement Serializable by setting readObject/writeObject, these won't be explicitly
# called
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Just to be safe, never remove fragments (causes breakage in some cases)
-keep public class * extends androidx.fragment.app.Fragment
