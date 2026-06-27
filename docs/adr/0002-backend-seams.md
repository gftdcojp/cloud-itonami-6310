# ADR-0002: Store / Advisor / Phase を注入境界にし、SaaS非依存の本番化を swap にする

- Status: Accepted (2026-06-27)
- 関連: ADR-0001（HR-LLM 封じ込め + PolicyGovernor）, langchain-clj（`langchain.db` Datomic互換EAV・`langchain.model` ChatModel）, langgraph-clj（StateGraph/checkpoint）

## 課題

ADR-0001 で actor の骨格（OperationActor + PolicyGovernor + 監査台帳）を決めた。
本番運用には次の3つを実体化する必要があるが、いずれも外部インフラ（Datomic、LLM
エンドポイント、人事データ）に依存し、**コアを書き換えずに差し替えたい**:

1. SSoT を dev の in-mem から永続 **Datomic** へ。
2. 助言ノードを mock から **実 LLM** へ。
3. 自動化の範囲を一度に全開にせず **段階導入**したい。

加えて、これらを入れても ADR-0001 の単一不変条件（「PolicyGovernor が拒否する
書き込み・開示を LLM は行わない」）と監査台帳の性質を壊してはならない。

## 決定

**3つの依存をすべて注入境界（プロトコル / コンテキスト）にする。** コア
（OperationActor のグラフ、PolicyGovernor、台帳）はどれにも依存しない。

### 1. `Store` プロトコル（SSoT）

`talent.store/Store`。実装:
- `MemStore` — atom。決定的な既定（dev/test/demo、依存ゼロ）。
- `DatomicStore` — `langchain.db`（Datomic-API 互換 EAV、datalog `q`/`pull`/ref/
  upsert、pure `.cljc`）。`langchain.db` の `:db-api` を差し替えれば **実 Datomic
  Local / kotoba-server pod**（`langchain.kotoba-db`）にそのまま乗る。

両者は **同一契約テスト**（`store_contract_test`）で等価性を保証する。これにより
「Datomic 化」は設定変更であって書き換えではない。台帳は全 backend で append-only。

### 2. `Advisor` プロトコル（助言ノード）

`talent.hrllm/Advisor`。実装:
- `mock-advisor` — 決定的（既定）。
- `llm-advisor` — `langchain.model/ChatModel`（Anthropic / OpenAI互換=Ollama・
  vLLM・kotoba）。offline テストは `langchain.model/mock-model` で駆動。

**安全側フォールバック**: 応答が EDN として壊れていれば `parse-proposal` が
confidence 0 の noop に落とす。→ governor が必ず escalate/hold。**LLM の不調が
auto-commit になる経路を構造的に塞ぐ**（ADR-0001 の不変条件を LLM 化後も維持）。

### 3. `Phase` 0→3（段階導入）

`talent.phase`。context の `:phase 0..3`（既定 3）。policy 判定の**後段**で、
policy より保守的にしか働かない（commit を approval / hold に**降格**するのみ、
昇格しない）:

| phase | writes | auto-commit | 意味 |
|---|---|---|---|
| 0 read-only | なし | なし | 影武者運用。書き込みは全て hold |
| 1 assisted-eval | upsert/eval | なし | 書くが必ず人間承認 |
| 2 assisted-insight | +survey | なし | サーベイ分析も承認付きで |
| 3 supervised-auto | 全 write | 全 write | policy clean & 高確信は自動 commit |

robotaxi の ODD 段階拡大（介入率が下がった範囲から自動化を広げる）と同型。
**hard policy 違反は最も緩い phase 3 でも hold**（phase は違反を上書きしない）。

### 4. CI（lint + workspace 再構成テスト）

本 project は `com-junkawasaki/root` superproject の west project であり、deps は
sibling を `:local/root`（`../../com-junkawasaki/langgraph-clj` 等）で参照する。
`langgraph-clj` / `langchain-clj` は **public** なので、CI の test job は両者を
期待相対パスに checkout して west レイアウトを再構成し、`clojure -M:dev:test` を
回す（token 不要）。lint job は `clojure -M:lint`（clj-kondo、自己完結）。

**現状の制約**: gftdcojp org は GitHub Actions を**組織方針で無効化**している
（repo 単位で有効化できない＝409）。そのため `.github/workflows/ci.yml` は正しく
登録されるが**実行されない**。org 方針の変更は本 actor のスコープ外（組織のセキュ
リティ/コスト判断）。それまでのゲートはローカル / west superproject での
`clojure -M:lint` と `-M:dev:test`。superproject（com-junkawasaki）側は Actions が
有効なので、結合 CI はそちらに寄せるのが本筋。

## 帰結

- 本番化は **3つの注入を差し替えるだけ**で、グラフ・governor・台帳は不変。
- `.cljc` 内の `edn`/`Exception` は reader 条件化し、DatomicStore 込みで JVM/cljs/
  WASM 可搬性を維持（langchain/langgraph の前提に合わせる）。
- 残課題: 実 Datomic Local / kotoba pod・実 LLM での結合確認（要 creds/infra）、
  `west annex-get` 後の m365 実体 seed。これらは注入境界の外側の運用作業。

## 代替案と不採用理由

- **最初から Datomic 直書き / LLM 直結**: テストが creds/infra に縛られ、ADR-0001
  の不変条件の検証が難しくなる。注入境界なら mock/in-mem で不変条件を常に回せる。
- **phase を PolicyGovernor に内包**: コンプライアンス（恒久ルール）と rollout
  （時限の運用判断）は寿命が違う。混ぜると「承認で違反を押し通す」温床になるため
  分離し、phase は policy の後段で**保守化のみ**に限定した。
