# DepUp Office for iOS

This is the native iOS target inside the same repository as the Android app.

## Open in Xcode

Open `DepupOffice.xcodeproj` and run the shared `DepupOffice` scheme on an iPhone or iPad simulator/device. The target currently supports iOS 18 and later.

The iOS shell uses SwiftUI navigation and the system `TabView`. On iOS 26 and later, iOS renders the tab and navigation controls with its native Liquid Glass appearance. Earlier supported iOS versions keep the standard native system appearance.

## Current iOS scope

- Native five-section navigation: Beranda, Berkas, Scan, Alat, and Saya.
- Import files from Files into the app's `Documents/DepUp Office` folder and preview formats supported by Quick Look.
- Capture multi-page documents with Apple's VisionKit document scanner and save the result as a PDF.
- System appearance and dynamic iOS controls.

## Migration boundary

Android remains in `app/` and keeps its Kotlin/Android build. iOS is in `iosApp/` and uses SwiftUI plus native Apple frameworks. The initial iOS target is a working foundation, not a feature-complete port of the Android office editors, OCR workflows, vault, conversions, and sharing flows. Those features should be ported in focused slices and checked on real Apple hardware before release.
