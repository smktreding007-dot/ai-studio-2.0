# All app classes are kept as they are: Firebase reads/writes them by reflection (User, Match, Slot ...), Coil fetchers, JSON models.
-keep class com.bestwinesport.admin.** { *; }
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses,RuntimeVisibleAnnotations,Exceptions
-keepclassmembers class * { @com.google.firebase.database.PropertyName *; }
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
