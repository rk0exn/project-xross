### ToDoリスト

以下に、この計画に基づいたToDoリストを作成します。このリストに沿って作業を進めていきます。

```
- Task List:
  - description: プロジェクト構造の確認と設定（`xross-macros`をProc-macroクレートとして設定、`xross-core`に`JvmClassTrait`を定義）
    status: pending
  - description: `JvmClassTrait`を`xross-core/src/lib.rs`に定義（`fn new() -> Self;`を含む）
    status: pending
  - description: `#[derive(JvmClass)]`のスケルトンを`xross-macros/src/lib.rs`に作成（`syn`と`quote`の基本的なセットアップ）
    status: pending
  - description: 入力`struct`が`repr(C)`を持つことを確認し、持たない場合はエラーまたは自動追加するロジックを実装
    status: pending
  - description: 入力`struct`が`Clone`を実装していることを確認し、持たない場合はエラーまたは自動追加するロジックを実装
    status: pending
  - description: 基本的なC-ABI関数を生成（`{クレート名}_{モジュール名}_{struct名}_new` と `{クレート名}_{モジュール名}_{struct名}_drop`）
    status: pending
  - description: `{クレート名}_{モジュール名}_{struct名}_clone`関数を生成
    status: pending
  - description: `JvmClassTrait`を`derive`された`struct`に自動実装する（`new`実装は`Default`を要求するか、引数なしコンストラクタを生成するロジックを検討・実装）
    status: pending
  - description: 公開されたプリミティブ型、`String`、別の`JvmClass`な構造体フィールドに対するGetter/Setter関数を生成
    status: pending
  - description: `String`型フィールドのGetter/SetterにおけるRust `String` と C `*const c_char` 間の変換ロジックを実装
    status: pending
  - description: `#[jvm_impl]`アトリビュートを定義し、これを持つメソッドのC-ABIラッパー関数を生成
    status: pending
  - description: Gradle PluginがRust側の情報を読み取るためのメタデータ（JSONなど）を生成する機能を検討・実装
    status: pending
  - description: Gradle Pluginのスケルトンを作成（Kotlin DSLでのセットアップ、基本的なプラグインクラス）
    status: pending
  - description: Gradle `Task`として`cargo-zigbuild`を呼び出し、ネイティブライブラリをビルドして`src/main/resources`に配置する機能を実装
    status: pending
  - description: ターゲットアーキテクチャごとのビルドをサポート
    status: pending
  - description: 生成されたメタデータまたはRustソースコードから、Kotlinコード生成に必要な情報を読み込むロジックを実装
    status: pending
  - description: 読み込んだRust情報に基づいて、Kotlinクラスのコードを生成するテンプレートエンジンを実装
    status: pending
  - description: Kotlinラッパークラスの構造を定義（`private constructor(private val ptr: Long)`, `AutoCloseable`の実装）
    status: pending
  - description: Rustの`_new`関数に対応するKotlinのファクトリメソッド（またはプライマリコンストラクタ）を生成
    status: pending
  - description: Rustの`_clone`関数に対応するKotlinの`clone`メソッドを生成
    status: pending
  - description: Rustフィールドに対応するKotlinの`var`プロパティを生成し、Getter/Setter内でRustのC-ABI関数を呼び出すロジックを実装
    status: pending
  - description: `String`型や`JvmClass`型フィールドのKotlinプロパティにおける適切な変換を実装
    status: pending
  - description: Rustのメソッドに対応するKotlinのメンバー関数または`companion object`関数を生成し、`self`の有無に応じて`ptr`を適切に渡すロジックを実装
    status: pending
  - description: 生成されたKotlinソースファイルをプロジェクトの適切なディレクトリに配置し、Gradleがコンパイル対象として認識するように設定
    status: pending
  - description: Rust Proc-macroの単体テストを実装（`trybuild`などを使用し、生成コードの正当性を検証）
    status: pending
  - description: Kotlin生成コードの単体テストを実装（生成コードのコンパイル、Rustライブラリとの相互作用を検証）
    status: pending
  - description: Gradleプラグインの統合テストを実装（エンドツーエンドでビルド、生成、実行を検証）
    status: pending
```