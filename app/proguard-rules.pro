# ez-vcard
-keep,includedescriptorclasses class ezvcard.property.** { *; }
-keep enum ezvcard.VCardVersion { *; }
-dontwarn ezvcard.io.json.**
-dontwarn freemarker.**
-keep class ezvcard.parameter.** {
    <init>(...);
}

-dontwarn com.andrognito.patternlockview.PatternLockView
-dontwarn com.andrognito.patternlockview.listener.PatternLockViewListener
