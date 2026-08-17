# 🔋 S Charge

S Charge is a lightweight Android battery monitoring and smart charging application designed as part of the **S Suite** ecosystem.

It provides real-time battery telemetry, battery health and capacity analysis, calibration, charging-session monitoring, and optional **Smart Charge** control on rooted devices with compatible kernels.

Built around a custom **Nothing OS-inspired** dot-matrix aesthetic, S Charge keeps battery information simple, local, and easy to understand.

---

## ✨ Features

### 🔋 Real-Time Battery Monitoring

Displays live battery information including:

- Battery percentage
- Voltage
- Current
- Battery power consumption
- Charger/input power
- Battery temperature
- Charging/discharging status
- Present battery charge
- Charge time information

Monitoring features work on both **rooted and unrooted devices**, subject to the information exposed by the device's Android battery driver.

---

### ❤️ Battery Health

Displays battery health as a percentage based on the device's design capacity and calibrated actual capacity. Battery health becomes available after completing battery capacity calibration.

---

### 📏 Actual Battery Capacity

S Charge can determine the battery's actual usable capacity through its calibration process.

The calibration process measures the battery during a controlled charging cycle and uses the collected data to determine actual capacity.

Once calibrated, the result is stored and used for:

- Actual Capacity
- Battery Health
- Battery analytics

---

### 🧪 Battery Calibration

Calibration can be started when:

- Battery level is **15% or below**
- The device is connected to power

Calibration measures the battery's usable capacity while charging.

The process can continue until:

- 100%, or
- An appropriate early-completion threshold is reached once sufficient capacity has been measured.

If charging is interrupted by a disconnected or faulty cable, calibration is **paused rather than cancelled**.

When the charger is connected again, S Charge can resume the existing calibration instead of restarting from the beginning.

---

### ⚡ Smart Charge

Smart Charge is an **optional root-only feature**. It allows supported rooted devices to automatically control charging at user-defined thresholds.

Smart Charge uses supported Linux/sysfs charging-control interfaces and therefore requires:

- Root/Superuser access
- A compatible kernel
- A writable charging-control node

Smart Charge is **not required** for the normal battery monitoring, analytics, health, capacity, or calibration features.

---

### 📊 Battery Analytics

Interactive real-time graphs provide visual information for:

- Voltage
- Current
- Battery power

These graphs help visualize battery behavior during charging and discharging.

---

### 📱 Rooted & Unrooted Support

S Charge is **not a root-only application**.

| Feature | Rooted | Unrooted |
|---|:---:|:---:|
| Battery monitoring | ✅ | ✅ |
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

Actual availability of individual battery statistics depends on what the device's Android battery driver exposes.

---

## 🔒 Permissions & Access

### 1. Root / Superuser

**Only required for Smart Charge.**

S Charge uses root privileges to access supported Linux sysfs charging-control nodes and control charging on compatible devices.

When Smart Charge is enabled, your root manager may request Superuser permission.

Compatible root solutions may include:

- Magisk
- KernelSU
- APatch

Root is **not required** for normal battery monitoring, calibration, capacity measurement, or battery health.

---

### 2. Background / Battery Usage

S Charge needs reliable background execution while charging so that charging monitoring, calibration, and notifications are not stopped by aggressive battery optimization.

On supported Android versions, S Charge can request exemption from battery optimization.

The app does **not** require unrestricted background processing continuously; normal monitoring is intended to operate while charging.

---

### 3. Boot Completed

S Charge can use the boot-completed event to restore appropriate charging-monitoring behavior after the device starts.

Normal monitoring remains dependent on the device's actual charging state.

---

## ⚠️ Compatibility

S Charge supports both rooted and unrooted Android devices for its monitoring and battery-analysis features.

However, **Smart Charge requires root and compatible kernel charging-control interfaces**.

Charging-control compatibility depends on:

- Device
- Android version
- Kernel
- Battery driver
- Charger driver
- Available sysfs interfaces

Some devices may not expose certain battery statistics.

For example, battery cycle count may display `N/A` when the device/kernel does not provide a usable lifetime cycle-count value.

S Charge does not invent hardware statistics that are unavailable from the device.

---

## 🛠️ Technical Specs

- **Minimum SDK:** Android 8.0 (API 26)
- **Target SDK:** Android 16 (API 36)
- **Architecture:** Universal APK
- **Package:** `com.hesi.scharge`
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Root Framework:** libsu
- **Data Storage:** Android DataStore
- **Internet Required:** ❌ No

---

## 🔐 Privacy

S Charge operates entirely on the device.

- ❌ No account required
- ❌ No cloud service required
- ❌ No internet connection required
- ❌ No personal data collection
- ❌ No battery data uploaded to external servers

Battery information and application settings remain on the device.

---

## 🚀 Installation

1. Open the **Releases** section of this repository.
2. Download the latest `S_Charge.apk`.
3. Install it on your Android device.
4. Grant notification/background access when requested.
5. If you want to use **Smart Charge**, grant Superuser permission when requested.
6. Configure your Smart Charge thresholds if your device supports kernel charging control.

Root is **not required** to use the normal battery monitoring and calibration features.

---
