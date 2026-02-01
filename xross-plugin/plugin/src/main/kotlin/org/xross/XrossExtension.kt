package org.xross

abstract class XrossExtension {
    // デフォルト値を空文字などで初期化
    var rustProjectDir: String = ""
    var packageName: String = ""

    // ユーザーが明示的に設定した値を保持する変数
    private var customMetadataDir: String? = null

    // Getterで動的にパスを生成する
    var metadataDir: String
        get() = customMetadataDir ?: "$rustProjectDir/target/xross"
        set(value) {
            customMetadataDir = value
        }
}