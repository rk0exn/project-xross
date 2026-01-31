package org.xross

// Gradle DSL設定用
interface XrossExtension {
    var rustProjectDir: String
    var crateName: String

    var packageName:String
}

