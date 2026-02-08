## プロジェクト全体改修計画

### 現状分析と課題

`xross`プロジェクトは、RustとKotlin/Java間の相互運用性を提供するクロス言語フレームワークです。現在の設計では、主に以下の点が課題として挙げられています。

1.  **Boxと実体の見分け**: Rust側で`Box<T>`としてヒープに確保されたオブジェクトと、スタックや他の場所に配置された`T`（実体）の区別が、FFI層およびKotlin側のコード生成において曖昧になっている可能性があります。これにより、メモリ管理やライフサイクル管理に混乱が生じるリスクがあります。
2.  **Owned/Ref/Mut Refの厳密な管理**: Rustの強力な所有権・借用システム（Owned, `&T` (Ref), `&mut T` (Mut Ref)）がFFI境界を越えてKotlin側で正確に表現・強制されていない可能性があります。これにより、意図しない二重解放、Use-After-Free、データ競合といったメモリ安全性の問題や、予期せぬ実行時エラーが発生する可能性があります。
    *   **Owned**: データへの唯一の所有権。Rust側で`drop`が呼ばれるとメモリが解放される。Kotlin側では`close()`を呼び出す責任がある。
    *   **Ref (`&T`)**: 不変な参照。データは借りているだけで、所有権は持たない。Kotlin側では`close()`を呼び出すべきではない。
    *   **Mut Ref (`&mut T`)**: 可変な参照。データは借りているだけで、所有権は持たない。Mut Refがある間は他の参照（特にMut Ref）は存在できない。Kotlin側では`close()`を呼び出すべきではない。
3.  **メモリ管理の非効率性/複雑性**: 現在、Kotlin側で`parent`参照を用いたり、`Cleaner`を登録したりする手動に近いメモリ管理が行われています。これは複雑性を増し、デバッグを困難にする可能性があります。特にFFM APIの`Arena`機能が提供する`arena.ofAuto()`のような自動リソース管理メカニズムを活用できていません。

### 目標

上記課題を解決するため、以下の目標を設定します。

1.  **Rust側で所有権情報を明確化**: `xross-macros`において、Rustの型シグネチャから`Owned`, `Ref`, `MutRef`の区別を正確に抽出し、メタデータ(`XrossType.ownership`)に反映させる。特に`Box<T>`と`T`、`&T`、`&mut T`の間の区別を厳密にする。
2.  **FFI関数シグネチャの最適化**: Rust側で生成されるFFI関数の引数と戻り値の型（`*mut T`, `*const T`, `T`値渡し）を、抽出された所有権情報に基づいて決定し、Rustの借用セマンティクスに沿ったものにする。
3.  **Kotlin側のメモリ管理を`Arena`ベースに移行**:
    *   `parent`フィールドを廃止し、Kotlin側で生成されるクラスのメモリ管理を`Arena.ofAuto()`を中心としたFFM APIの自動リソース管理に全面的に移行する。
    *   `Owned`オブジェクトは`Arena`のライフタイムにバインドされることで自動的に解放されるようにし、`Ref`/`MutRef`オブジェクトは借りた`MemorySegment`を直接操作するようにする。
    *   `AutoCloseable`の実装は、`Owned`オブジェクトに対してのみ意味を持つように変更する。
4.  **Kotlinコード生成ロジックの改善**: Kotlin側で生成されるクラス、メソッド、プロパティのコードが、所有権情報と新しい`Arena`ベースのメモリ管理モデルを正確に反映するように修正する。これにより、Kotlin開発者がより安全かつRustらしいセマンティクスでRustコードを扱えるようにする。

### 計画の詳細

#### フェーズ1: 現状把握とメタデータ拡張 (完了)

ユーザーからのコードスニペットとファイル構造から、以下の点が確認できました。

*   **`xross-metadata`**: `Ownership` enum (`Owned`, `Ref`, `MutRef`) が既に定義されており、`XrossType.Object`に`ownership`フィールドが存在します。これはRustの所有権モデルを表現するための基礎が既に存在することを示しています。
*   **`xross-macros`**:
    *   `resolve_type_with_attr`関数で、参照型 (`&T`, `&mut T`) から`Ownership`を判定しています。
    *   `jvm_class`属性マクロのメソッド生成部分で、`XrossType.Object`の`ownership`に基づいて`Box::into_raw`するかどうかを分岐しています。
    *   `generate_common_ffi`の`_drop`と`_clone`関数は`Box::from_raw(ptr)`を使用しており、これは`ptr`が`Box::into_raw`で作成された`Owned`ポインタであることを前提としています。
    *   `opaque_class`マクロは`is_clonable`フラグを持ち、`_clone`FFIを生成するかどうかを制御しています。
*   **`xross-plugin`**:
    *   `XrossTypeSerializer`は`Ownership`をシリアライズ/デシリアライズしています。
    *   `MethodGenerator`、`PropertyGenerator`、`EnumVariantGenerator`で`XrossType.ownership`を利用してKotlin側のコードを生成するロジックが存在します。特に`MethodGenerator`の`generateInvokeLogic`では`isBorrowed`フラグが使われており、`parent`に`this`を渡すか`null`を渡すかなどを決定しています。
    *   `OpaqueGenerator`と`StructureGenerator`は`parent`フィールドと`Cleaner`を使用してメモリ管理を行っています。

**課題の特定:**

*   **`Box<T>`の扱い**: 現在の`resolve_type_with_attr`は`Box<T>`を`Type::Path`として処理し、その内部の`T`を`Ownership::Owned`としてしまう可能性があります。`Box<T>`自体が所有権を持つ構造であるため、これをどのように`XrossType`で表現するか検討が必要です。Rustの`Box::into_raw`と`Box::from_raw`は`Owned`セマンティクスですが、Kotlin側でそれらを`Arena`と連携させる必要があります。
*   **`Arena`への移行**: `parent`フィールドと`Cleaner`ベースの管理から`Arena.ofAuto()`への移行は、Kotlin側のクラスのインスタンス化、メソッド呼び出し、`close()`の動作に大きな変更を必要とします。特に`MemorySegment`が`Arena`にアロケートされるタイミングと、そのセグメントの有効期間をどう管理するかが重要です。
*   **`isCopy`の再検討**: `XrossType.isCopy`の定義が、FFIレイヤーでのバイトコピーとKotlin側の値渡し・参照渡しを混同している可能性があります。`Arena`移行に伴い、セグメントのコピーを最小限にし、可能な限り参照渡しに切り替えるべきです。

#### フェーズ2: `xross-macros`の修正

1.  **`Box<T>`の`Ownership`明示**: `xross-macros/src/type_mapping.rs`の`map_type`と`xross-macros/src/utils.rs`の`resolve_type_with_attr`を修正し、`Box<T>`型が渡された際に、その内側の`T`が`Owned`であるという情報を正しく伝達できるようにする。`Box<T>`はポインタを介してアクセスされるため、`XrossType::Object`として表現されるべきですが、その`ownership`は`Owned`です。
2.  **FFIシグネチャの`Ownership`整合性**: `xross-macros/src/lib.rs`の`jvm_class`マクロにおいて、メソッドの引数と戻り値のFFIシグネチャ生成ロジックを、`XrossType.ownership`に厳密に従うように修正する。
    *   `Ownership::Owned`を返す場合は`Box::into_raw`を使う。
    *   `Ownership::Ref`や`Ownership::MutRef`を返す場合は、Rust側の参照（`&T`, `&mut T`）から直接`*const T`または`*mut T`にキャストする。`Box::new`や`Box::into_raw`は不要です。
    *   引数も同様に、`Owned`オブジェクトを受け取る場合は`Box::from_raw`で所有権を受け取り、`Ref`/`MutRef`オブジェクトを受け取る場合は`*const T`または`*mut T`として参照を受け取る。
3.  **`generate_common_ffi`の修正**: `_drop`と`_clone`関数は`Ownership::Owned`のオブジェクトにのみ適用されるべきであることを明確にする。`_clone`は、`Arena`ベースの管理下で新しい`Owned`インスタンスを生成する場合にのみ必要となる。

#### フェーズ3: `xross-plugin`の修正 (`Arena`移行と`parent`廃止)

1.  **`parent`フィールドの廃止**:
    *   `XrossDefinition.kt`を含む構造体で定義されている`parent`フィールド（あるいはそれに相当する概念）を削除する。
    *   `OpaqueGenerator.kt`、`StructureGenerator.kt`、`MethodGenerator.kt`、`PropertyGenerator.kt`、`EnumVariantGenerator.kt`から`parent`に関連するロジック（コンストラクタ引数、プロパティ、条件分岐など）を全て削除する。
2.  **`Arena.ofAuto()`の導入**:
    *   `OpaqueGenerator.kt`、`StructureGenerator.kt`において、`MemorySegment`の取得と管理を`Arena.ofAuto()`を使用するように変更する。これにより、Kotlin側のインスタンスがスコープを抜けると自動的にネイティブメモリが解放されるようにする。
    *   `MethodGenerator.kt`におけるメソッド呼び出しの際、引数が`XrossType.Object { ownership: Owned }`である場合、`arena.allocateFrom`などを用いて`Arena`にコピーし、その`MemorySegment`をFFIに渡すように修正する。
    *   戻り値が`XrossType.Object { ownership: Owned }`の場合、返された`MemorySegment`を`Arena`にアロケートされた新しいKotlinオブジェクトにラップし、その`Arena`がKotlinオブジェクトのライフタイムにバインドされるようにする。
3.  **`Cleaner`ロジックの修正**: `Arena`が自動でリソースを管理するため、`Owned`オブジェクト以外では`Cleaner`の登録は不要になります。`Owned`オブジェクトの場合も、`Arena`が`Cleaner`と同様の役割を果たすため、`Cleaner`の使用箇所を見直すか、`Arena`に置き換える。
4.  **`AutoCloseable`のセマンティクス変更**:
    *   `XrossType.Object { ownership: Owned }`を持つKotlinクラスのみが`AutoCloseable`インターフェースを実装し、その`close()`メソッド内で、`Arena`の閉鎖またはRust側`_drop`FFIの呼び出しを適切に行うようにする。
    *   `Ref`または`MutRef`の`XrossType.Object`を表現するKotlinクラスは`AutoCloseable`を実装しないか、実装しても`close()`メソッドが何もしない（あるいはエラーを投げる）ようにする。これは、借りている参照を解放してはならないというRustの借用セマンティクスを強制するためです。
5.  **`isCopy`とセグメントの扱いの改善**: `XrossType.isCopy`の定義が`MemorySegment`をコピーする状況と一致しない場合があるため、再検討し、`Arena`への移行と合わせてセグメントの受け渡しがより効率的かつ安全になるように調整する。

#### フェーズ4: `xross-example`のテストと検証

1.  `xross-example`プロジェクトのビルドと実行を行い、`Arena`ベースのメモリ管理が正しく機能しているか確認する。
2.  既存のメモリリークテストや参照・所有権テストが、変更後のセマンティクスと整合しているか検証する。必要に応じてテストコードを修正・追加する。
3.  `Box<T>`、`&T`、`&mut T`を引数・戻り値として持つ新しいテストケースを追加し、所有権と借用セマンティクスがKotlin側で正しく扱われていることを確認する。

### 進捗管理

この`plan.md`ファイルをプロジェクトルートに保存し、各フェーズの完了時に更新します。

---

**初期のTODOリスト:**

- [x] 1. `xross-macros`クレート内の型の解決、FFI生成、メタデータ保存に関する主要ファイルを読み込み、現状のOwned/Ref/MutRefの扱いやメモリ管理の仕組みを把握する。
- [x] 2. `xross-metadata`クレート内の`XrossType`と`Ownership`の定義を詳細に確認し、現在のモデルがRustの借用セマンティクスとどの程度整合しているかを評価する。
- [x] 3. `xross-plugin`クレート内のKotlinコード生成ロジックを読み込み、特に`MemorySegment`の利用、`Cleaner`、`parent`フィールド、そして`AutoCloseable`の実装について、現在のメモリ管理モデルを把握する。
- [x] 4. `xross-example`クレートの`App.kt`と`lib.rs`を読み、現在のメモリリークテストや参照・所有権テストがどのような挙動を期待しているか理解する。
- [x] 5. 上記の調査結果を基に、より詳細な計画を`plan.md`に記述する。 (現在のこのmdファイルです)
- [x] 6. `plan.md`をプロジェクトのルートディレクトリに保存する。