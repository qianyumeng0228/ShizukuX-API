# ShizukuX-API

ShizukuX-API is an enhanced, developer-friendly version of the Shizuku API. It provides a modernized interface for interacting with [ShizukuX](https://github.com/qianyumeng0228/ShizukuX), while maintaining full backward compatibility with standard Shizuku and Sui servers.

## ✨ Key Features (Plus Upgrades)

ShizukuX-API eliminates the boilerplate associated with standard Shizuku development:

*   **Synchronous Shell Execution**: No more managing `InputStream`, `ErrorStream`, and threads. Get a clean `CommandResult` in one line.
*   **High-Level Utilities**: Dedicated classes for managing **System Settings**, **Package Installation**, and **System Overlays (RRO)**.
*   **Dhizuku (Device Owner) Integration**: Directly access the `DevicePolicyManager` binder without requiring the user to perform a factory reset or complex ADB setup.
*   **Universal Compatibility**: Automatically detects if the server is ShizukuX or standard Shizuku. Uses direct AIDL stubs for Plus servers; falls back to `Shizuku.newProcess` shell execution on standard Shizuku.

## 🚀 Plus API Features

ShizukuX-API includes exclusive interfaces for advanced system interaction:

### 1. AVF (Virtual Machine) Manager
Manage isolated Linux environments via the Android Virtualization Framework.
*   **Capabilities**: Create, start, and manage Microdroid or Debian-based VMs.
*   **Use Case**: Run hardware-accelerated Linux GUI apps or secure isolated services.

### 2. Privileged Storage Proxy
Bypass SAF (Storage Access Framework) limitations for verified power-user tools.
*   **Capabilities**: Obtain `FileDescriptors` for restricted paths like `/data/data/`.
*   **Security**: Requires explicit biometric/user confirmation via the ShizukuX manager.

### 3. Intelligence Bridge (AI Core Plus)
Access privileged system intelligence and hardware accelerators.
*   **Capabilities**: High-priority NPU scheduling and privileged screen context sampling (EyeDropper extension).
*   **Use Case**: Advanced automation and context-aware accessibility tools.

### 4. Window Manager Plus (Desktop Mode)
Take control of the desktop windowing experience.
*   **Capabilities**: Force free-form resizing, manage the system "Bubble Bar," and set "Always on Top" windows.

### 5. System Theming Bridge (Overlay Manager Plus)
Expose privileged overlay management.
*   **Capabilities**: Enable/disable system UI overlays without root.
*   **Use Case**: Rootless theming engines (like Hex Installer).

### 6. Network & DNS Governor
Manage network restrictions and routing.
*   **Capabilities**: Set system-wide Private DNS and manage iptables rules via routing/VPN.
*   **Use Case**: Rootless ad-blockers (AdAway) and firewalls (AFWall+).

### 7. Deep Process Control (Activity Manager Plus)
Advanced memory and process management.
*   **Capabilities**: Deeply kill background apps and manage standby buckets.
*   **Use Case**: Performance optimizers (Thanox, 3C Toolbox).

### 8. Continuity Bridge
Seamless multi-device state synchronization.
*   **Capabilities**: Sync app states and privileged task handoffs between devices running ShizukuX.

## 🚀 Getting Started

### Add dependency

Add the following to your `build.gradle`:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.qianyumeng0228:ShizukuX-API:13.2.0-plus'
}
```

## 🛠️ Usage Examples

### 1. Unified Shell
Execute any command and get the output synchronously (call off the main thread):
```java
// String shorthand
ShizukuXAPI.CommandResult result = ShizukuXAPI.executeShell("whoami");
if (result.isSuccess()) {
    Log.d("API", "Output: " + result.output);
}

// Explicit arg array (preferred — avoids shell-quoting issues)
ShizukuXAPI.CommandResult result2 = ShizukuXAPI.executeShell(
    new String[]{"pm", "list", "packages", "-3"});
```

### 2. System Settings
Easily read or modify `system`, `secure`, and `global` settings:
```java
ShizukuXAPI.Settings.putSecure("now_bar_enabled", "1");
String scale = ShizukuXAPI.Settings.getSystem("font_scale");
```

### 3. Advanced Window Control
Force an app into free-form mode even if restricted by its manifest:
```java
ShizukuXAPI.WindowManager.forceResizable("com.example.app", true);
```

### 4. Storage Access
Access a file in an app's private data directory (requires user confirmation):
```java
ParcelFileDescriptor pfd = ShizukuXAPI.StorageProxy.openFile(
    "/data/data/com.example.app/files/config.json",
    ParcelFileDescriptor.MODE_READ_ONLY);
```

## 🔄 Compatibility

ShizukuX-API dispatches through the AIDL stub (`IShizukuService`) when a Plus
server is detected, giving direct typed access to all Plus interfaces with no
magic transaction codes. On a standard Shizuku server the shell helpers fall
back to `Shizuku.newProcess`; Plus-only AIDL features return `null`/`false`.

**Result**: Your app works everywhere, but gets richer capabilities on ShizukuX.

## 📱 Documentation & Original API
For the core logic, `UserService` documentation, and AIDL definitions, please refer to the original [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) repository. ShizukuX-API includes all original `rikka.shizuku.Shizuku` methods.

## 📃 License
[MIT License](LICENSE)
