# Methods called from JavaScript through addJavascriptInterface.
-keepclassmembers class fi.jss.pwa.WebAppActivity$DownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
