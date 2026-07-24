# PhoneStick 📱💾

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" height="128" alt="PhoneStick Icon">
</p>

PhoneStick turns your rooted Android device into a USB Mass Storage drive or CD-ROM emulator using Android Kernel ConfigFS and USB gadget drivers.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_main.png" width="360" alt="PhoneStick Screenshot">
</p>

## Features

- **Material Design 3**: Modern, clean UI with dynamic light/dark theme support and speed-dial FAB actions.
- **Mount Raw Disk Images**: Mount `.img`, `.iso`, `.bin`, `.raw`, `.vhd`, `.qcow2` files directly as USB flash drives or CD-ROMs.
- **Zero-Copy SAF Resolution**: Resolves external storage URIs (SD cards, Downloads) to direct Linux kernel paths without duplicating files.
- **Emulation Modes**: Supports Read-Only and virtual CD-ROM emulation modes.
- **ConfigFS & Sysfs Support**: Direct binding support for Android ConfigFS (`/config/usb_gadget/g1` & `/sys/kernel/config`) and sysfs LUN targets.
- **Blank Image Creation**: Easily allocate and format blank disk images right inside the app.

## Installation

- **GitHub Release**: Download the latest APK from [Releases](https://github.com/mingww64/PhoneStick/releases).
- **F-Droid**: Build recipe and metadata available in [`metadata/mingww64.phonestick.yml`](metadata/mingww64.phonestick.yml).

## Requirements

- Rooted Android device (Magisk / KernelSU / APatch)
- Android kernel with USB Mass Storage gadget support (`CONFIG_USB_F_MASS_STORAGE` or ConfigFS)

## License

[MIT License](LICENSE)

Original work by streetwalrus, dratini0, donfanning, Swyter, and JinbaIttai.
