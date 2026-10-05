-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# ZipFile reflects over extra-field classes. Without their constructors, R8 makes
# unzipping the Python runtime throw "is not a concrete class".
-keep class org.apache.commons.compress.** { *; }
-dontwarn com.yausername.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
