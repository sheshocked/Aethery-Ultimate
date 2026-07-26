# Aethery-Ultimate 📱

[![Release](https://img.shields.io/github/v/release/sheshocked/Aethery-Ultimate?sort=semver)](https://github.com/sheshocked/Aethery-Ultimate/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-0095D5?logo=kotlin&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-stable-000000?logo=rust&logoColor=white)

Aethery-Ultimate is a native, open-source Android VPN application built around the **Aether** censorship-circumvention core. It wraps the terminal-based Aether tunnel in a clean, material-design mobile interface, letting you establish high-performance bypass tunnels on your phone with a single click.

<p align="center">
  <img src="aether_logo.svg" alt="Aethery Logo" width="180">
</p>

---

## 🌟 Key Features

- **Native VpnService (TUN Mode)**: Full system-wide routing of all TCP/UDP traffic through the Aether tunnel.
- **Custom MTU Tuning**: Adjust the Maximum Transmission Unit (MTU) (e.g. 1280 to 1360 bytes) directly in Settings to mitigate packet drop and bypass UDP throttling on networks like MCI and Irancell.
- **Custom Tunnel DNS**: Configure secure or sanction-bypassing DNS servers (e.g., Presets for Shecan, 403.online, Cloudflare).
- **Split Tunneling (App Bypass)**: Exclude specific installed applications (such as local banking apps, messengers, or Snapp) from the VPN tunnel to preserve local routing and secure access.
- **Micro-telemetry**: Live connection log viewer and diagnostic statuses built into the main screen.

---

## ⚙️ How It Works

Aethery is designed as a native Android layer communicating with the prebuilt Aether core through JNI:

```text
MainActivity (UI Controls)
    │ Configuration & UI states
    ▼
AetherVpnService (Android VpnService / TUN)
    │
    ▼
NativeCore (JNI Bridge / C++) ── libaether.so (Rust Core)
```

1. **MainActivity** initiates the connection and passes configuration parameters as a JSON string to `AetherVpnService`.
2. **AetherVpnService** requests VPN permissions, allocates the virtual TUN interface, sets the MTU/DNS, and protects the core sockets from looping.
3. The **Native JNI Bridge** receives the file descriptor and handles route discovery, cryptographic handshakes (MASQUE, WireGuard, nested gool), and traffic obfuscation via Aether.

---

## 🛠️ How to Build Local APKs

### Prerequisites
Ensure you have the following installed on your machine:
- Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`
- JDK 17
- Rust stable with Android targets (`aarch64-linux-android` and `armv7-linux-androideabi`)
- `cargo-ndk`

### Build Steps

1. **Clone the repository**:
   ```bash
   git clone https://github.com/sheshocked/Aethery-Ultimate.git
   cd Aethery-Ultimate
   ```

2. **Build the Rust core** for Android architectures:
   ```bash
   cd core/aether
   cargo ndk -t arm64-v8a --platform 24 build --release --lib
   cargo ndk -t armeabi-v7a --platform 24 build --release --lib
   cd ../..
   ```

3. **Copy the compiled library binaries**:
   ```bash
   mkdir -p app/src/main/jniLibs/arm64-v8a jniLibs/armeabi-v7a
   cp core/aether/target/aarch64-linux-android/release/libaether.so app/src/main/jniLibs/arm64-v8a/libaether.so
   cp core/aether/target/armv7-linux-androideabi/release/libaether.so app/src/main/jniLibs/armeabi-v7a/libaether.so
   ```

4. **Compile the release APK**:
   ```bash
   ./gradlew assembleRelease
   ```

The compiled APKs will be located under `app/build/outputs/apk/release/`.

---

## 📜 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
