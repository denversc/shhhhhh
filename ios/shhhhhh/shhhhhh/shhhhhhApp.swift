//
//  shhhhhhApp.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import SwiftUI

// swift-format-ignore: TypeNamesShouldBeCapitalized
@main
struct shhhhhhApp: App {
  init() {
    if CommandLine.arguments.contains("-reset-storage") {
      KeychainAppStorage.shared.clearAll()
    }
  }

  var body: some Scene {
    WindowGroup {
      ContentView()
    }
  }
}
