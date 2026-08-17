# 🔋 S Charge

S Charge is a lightweight Android battery monitoring and charging-control application developed as part of the **S-Suite** ecosystem.

It provides live battery telemetry, battery capacity and health analysis, calibration, charging-session monitoring, battery analytics, notifications, and optional **Smart Charge** control on supported rooted devices.

S Charge is designed to work locally on the device and does not require an internet connection.

---

## ✨ Features

### 🔋 Real-Time Battery Monitoring

S Charge displays live battery information including:

- Battery percentage
- Precise battery percentage from charge-counter telemetry
- Battery voltage
- Battery current
- Battery-side power
- Temperature
- Charging/discharging status
- Charger/input power
- Present battery charge
- Charging-time / full-charge estimates when available

The main battery indicator follows Android's whole-number battery level, while the precise percentage remains available for detailed telemetry and capacity calculations.

Availability of individual measurements depends on what the device's Android battery driver exposes.

---

### ❤️ Battery Health

S Charge calculates battery health using the device's design capacity and the calibrated current full capacity.

Battery health becomes available after the app has enough capacity information from calibration.

---

### 📏 Actual Battery Capacity

S Charge can estimate the battery's current usable capacity using the device's charge-counter telemetry during calibration.

The resulting capacity can be used for:

- Actual battery capacity
- Battery health calculation
- Battery analytics

---

### 🧪 Battery Calibration

Calibration is designed to begin when:

- Battery level is **15% or below**
- The device is connected to power

S Charge measures the change in charge-counter value over the charging range to estimate the battery's usable capacity.

A calibration session may produce a provisional capacity estimate after a sufficient charging span, and a later full-charge completion can refine the result.

**Important:** a calibration interruption/disruption is not a clean continuous measurement. The calibration state is handled so that an interrupted session does not leave an invalid stale baseline to be reused incorrectly.

---

### ⚡ Smart Charge

Smart Charge is an **optional root-only feature** for supported devices.

It can control charging around user-defined thresholds using compatible Linux/sysfs charging-control interfaces.

Smart Charge requires:

- Root/Superuser access
- A compatible kernel
- A supported writable charging-control interface

Smart Charge is **not required** for normal monitoring, analytics, battery health, capacity information, calibration, or notifications.

---

### 📊 Battery Analytics

S Charge provides live graphical information for measurements such as:

- Voltage
- Current
- Battery power

These graphs help visualize battery behavior during charging and discharging.

---

### 🔔 Charging Notifications

S Charge can maintain charging monitoring and notifications in the background.

Charging events can be received through the system power-connection receiver, allowing charging monitoring to start without first opening the main S Charge interface.

Notification behavior can depend on Android background-execution restrictions and device-specific battery-management policies.

---

### 📱 Rooted & Unrooted Support

S Charge is **not a root-only application**.

| Feature | Rooted | Unrooted |
|---|:---:|:---:|
| Battery monitoring | ✅ | ✅ |
| Battery percentage / precise telemetry | ✅ | ✅ |
| Voltage/current/power | ✅ | ✅ |
| Temperature | ✅ | ✅ |
| Battery health | ✅ | ✅ |
| Actual capacity | ✅ | ✅ |
| Calibration | ✅ | ✅ |
| Battery analytics | ✅ | ✅ |
| Charging monitoring | ✅ | ✅ |
| Notifications | ✅ | ✅ |
| Smart Charge | ✅ | ❌ |
| Kernel charging control | ✅ | ❌ |

Actual availability of individual battery statistics depends on the device and its battery driver.

---

## 🔒 Permissions & Access

### 1. Root / Superuser

**Only required for Smart Charge.**

S Charge uses root access to interact with supported Linux/sysfs charging-control interfaces on compatible devices.

Possible root environments include solutions such as:

- Magisk
- KernelSU
- APatch

Root is **not required** for normal monitoring, calibration, capacity analysis, or battery health features.

---

### 2. Notifications

S Charge requests notification access so charging-monitoring information can be shown while the app is running in the background.

On supported Android versions, notification permission may need to be granted by the user.

---

### 3. Background / Battery Optimization

S Charge requests exemption from battery optimization on supported Android versions so charging monitoring, calibration, and notifications can continue reliably while the app is not open.

Device manufacturers may apply additional background-management rules.

---

### 4. Boot Completed

S Charge can receive the device boot-completed event to restore appropriate charging-monitoring behavior after startup.

Normal monitoring remains dependent on the device's current charging state.

---

## ⚠️ Compatibility

S Charge supports rooted and unrooted Android devices for its battery-monitoring and analysis features.

Smart Charge requires root and compatible kernel charging-control interfaces.

Compatibility depends on:

- Device
- Android version
- Kernel
- Battery driver
- Charger driver
- Available sysfs interfaces

Some devices may not expose certain hardware statistics. S Charge does not invent unavailable hardware data.

---

## 🛠️ Technical Specs

- **Minimum SDK:** Android 8.0 (API 26)
- **Target SDK:** Android 14 (API 34)
- **Compile SDK:** Android 14 (API 34)
- **Architecture:** Universal APK
- **Package:** `com.hesi.scharge`
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Root Framework:** libsu
- **Data Storage:** Android DataStore / local preferences
- **Java/Kotlin target:** Java 17
- **Internet Required:** ❌ No

---

## 🔐 Privacy

S Charge operates locally on the device.

- ❌ No account required
- ❌ No cloud service required
- ❌ No internet connection required
- ❌ No personal data collection
- ❌ No battery data uploaded to external servers

Battery telemetry and application settings remain on the device.

---

## 🚀 Build

Open the `SNotes` folder in **Android Studio**.

Or build from the command line with JDK 17 and the Android SDK:

```bash
./gradlew assembleDebug
```

For a release build:

```bash
./gradlew assembleRelease
```
---

## 🧩 Project Structure

```text
SCharge/
├── app/
│   ├── src/main/java/com/hesi/scharge/
│   │   ├── core/
│   │   ├── data/
│   │   ├── receiver/
│   │   ├── service/
│   │   ├── telemetry/
│   │   └── ui/
│   └── src/main/res/
├── gradle/
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

---

## 🌐 S-Suite

S Charge is one application in the **S-Suite** ecosystem.
