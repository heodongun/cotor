// swift-tools-version: 6.0
import PackageDescription

// A plain Swift Package keeps the first macOS client easy to build from the repo
// without introducing an Xcode project as an additional source of truth.
let package = Package(
    name: "CotorDesktop",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "CotorDesktopApp", targets: ["CotorDesktopApp"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-testing.git", from: "0.6.0")
    ],
    targets: [
        .executableTarget(
            name: "CotorDesktopApp",
            path: "Sources/CotorDesktopApp",
            resources: [
                .copy("Resources/Terminal")
            ]
        ),
        .testTarget(
            name: "CotorDesktopAppTests",
            dependencies: [
                "CotorDesktopApp",
                .product(name: "Testing", package: "swift-testing")
            ],
            path: "Tests/CotorDesktopAppTests"
        )
    ]
)
