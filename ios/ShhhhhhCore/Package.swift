// swift-tools-version: 6.0
//
//  Package.swift
//  ShhhhhhCore
//
//  Created by Denver Coneybeare on 2026-11-02.
//

import PackageDescription

let package = Package(
  name: "ShhhhhhCore",
  platforms: [
    .iOS(.v17),
    .macOS(.v14),
  ],
  products: [
    .library(
      name: "ShhhhhhCore",
      targets: ["ShhhhhhCore"]
    )
  ],
  dependencies: [
    .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"6.0.0")
  ],
  targets: [
    .target(
      name: "ShhhhhhCore",
      dependencies: [
        .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
      ],
      swiftSettings: [
        .swiftLanguageMode(.v5)
      ]
    ),
    .testTarget(
      name: "ShhhhhhCoreTests",
      dependencies: [
        "ShhhhhhCore",
        .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux])),
      ],
      swiftSettings: [
        .swiftLanguageMode(.v5)
      ]
    ),
  ]
)
