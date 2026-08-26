# ProGuard configuration for TurnOffTimer
# Add project specific ProGuard rules here.

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
