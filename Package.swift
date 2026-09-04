// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "cordova-plugin-echo",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(name: "cordova-plugin-echo", targets: ["cordova-plugin-echo"])
    ],
    dependencies: [
        .package(url: "https://github.com/apache/cordova-ios.git", branch: "master"),
        .package(url: "https://github.com/clerk/clerk-ios", from: "1.2.0")
    ],
    targets: [
        .target(
            name: "cordova-plugin-echo",
            dependencies: [
                .product(name: "Cordova", package: "cordova-ios"),
                .product(name: "ClerkKit", package: "clerk-ios")
            ],
            path: "src/ios",
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("WebKit")
            ]
        )
    ]
)
