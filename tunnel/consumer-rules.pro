-keepclassmembers class com.zaneschepke.tunnel.service.VpnService {
    int bypass(int);
}

# JNI callback bridges for DNS and status updates
-keepclassmembers,includedescriptorclasses class com.zaneschepke.tunnel.backend.dns.NativeDnsResolver {
    public static void onResolutionComplete(long, java.lang.String);
}

-keepclassmembers,includedescriptorclasses class com.zaneschepke.tunnel.backend.TunnelStatusBridge {
    public static void onStatusChanged(int, int);
}