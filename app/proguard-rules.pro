# switch-mcp: minification is disabled by default. Keep JNI entry points and
# the MCP/Ktor reflective bits if minify is ever enabled.
-keep class org.xnet.switchmcp.NativePortmuxEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
