// swift-tools-version: 5.10
import PackageDescription

// A plain Swift Package keeps the first macOS client easy to build from the repo
// without introducing an Xcode project as an additional source of truth.
let package = Package(
    name: "CotorDesktop",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "CotorDesktopApp", targets: ["CotorDesktopApp"])
    ],
    targets: [
        .executableTarget(
            name: "CotorDesktopApp",
            path: "Sources/CotorDesktopApp",
            resources: [
                .copy("Resources/Terminal")
            ]
        )
    ]
)
