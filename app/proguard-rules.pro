# Add project specific ProGuard rules here.

# ViewModel 通过反射实例化，R8 需要保留构造函数
-keep class com.twitterdownloader.app.MainViewModel { <init>(android.app.Application); }
