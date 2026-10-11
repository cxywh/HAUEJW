# 保留 WebView 相关（若后续加 JS 接口）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# org.json 由系统提供，无需保留规则
