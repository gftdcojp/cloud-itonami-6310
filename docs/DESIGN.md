# Talent Actor Design — HR-LLM as a contained intelligence node

タレントマネジメント SaaS（kaonavi 等）に課金せず、同等以上を OSS の actor として
自前運用するための設計。robotaxi-actor が研究モデル AR1 を SafetyGovernor で
封じ込めた構図を、人事ドメインへそのまま写像している。

## 1. 前提: なぜ actor 層が要るのか

人事の中核業務は LLM で加速できる（評価ドラフト・サーベイ分析・離職予兆・承認経路
提案）。しかし LLM は次の理由で **人事レコードの最終権限を持てない**:

| LLM が起こしうる失敗 | 人事での帰結 |
|---|---|
| 保護属性（性別・年齢・国籍・病歴…）を根拠に評価 | 差別・違法評価 |
| 過剰な個人情報を帳票/回答に含める | 目的外利用・情報漏洩 |
| 権限を越えた他部署/上位等級レコードの更新 | 権限分離の崩壊 |
| 幻覚（存在しない実績・数値） | 誤った人事判断 |

そこで設計問題は「LLM で人事を回す」ことではなく、**「LLM を信頼境界の内側に封じ込め、
権限・公正性・最小開示・人間承認・監査の層をどう被せるか」**になる。以下すべてが
ここから導かれる。

**SaaS に払わない論拠**: SaaS が課金で握っている価値の実体は DB・承認ワークフロー・
帳票・分析であり、いずれも OSS スタック（Datomic/EDN + langgraph-clj StateGraph +
LLM）で代替できる。actor 化で上乗せされるのは、SaaS が原理的に渡さない
**データ主権**（自分の Datomic が SSoT）と**不変の監査台帳**である。

## 2. アクター・トポロジ（監督ツリー）

```
TalentSystem (root supervisor)
│
├── DirectoryActor ……… 従業員DB・組織図（:employee/upsert・OrgGraph 投影）
├── PerformanceActor …… 評価・目標 MBO/OKR（:evaluation/draft・GoalProgress）
├── InsightActor ……… サーベイ・分析（:survey/analyze・AttritionRisk）
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; HR-LLM 封じ込め ★
│     ├── HR-LLM (sealed)     proposal only（src/talent/hrllm.cljc）
│     ├── PolicyGovernor      INDEPENDENT 規程ゲート（src/talent/policy.cljc）
│     ├── Committer           SSoT/台帳への書き込み（src/talent/store.cljc）
│     └── Recorder            監査台帳（append-only）
│
├── ApprovalActor ……… 上長/HRBP 承認（interrupt を受ける human-in-the-loop）
└── ReportActor ……… 帳票/CSV（PolicyGovernor を通した governed read; report.cljc）
```

原則:

1. **HR-LLM は最下層ノードで、台帳に直接触れない。** 出力は常に PolicyGovernor で
   検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold（書き込まない）** に倒す。
   robotaxi の MRC（安全停止）に相当する「何もせず安全側へ倒す」既定。
3. **すべてが台帳に積まれる。** 「なぜこの評価/開示になったか」は監査台帳への
   Datalog クエリ — 監査・規程適合・人事フィードバックが同一ファクトログから出る。

## 3. OperationActor 内部（HR-LLM ラッパー）

`src/talent/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 HR操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

チャネル: `:request :context :proposal :verdict :disposition :record :approval :audit`

- **`:context` は外部注入**（`{:actor-id .. :actor-role .. :purpose .. :consent? ..}`）。
  HR-LLM はこれを持たない（robotaxi の route 注入と同型）。
- **`:govern` は HR-LLM と別系統**（規程ルール + 権限表 + 保護属性表）。LLM 提案を
  *拒否*して hold に substitute できる。
- **`interrupt-before #{:request-approval}`** で実際の承認ワークフローへ。
  承認者は resume 時に `{:approval {:status :approved}}` を注入する。

### 3.1 注入される3つの依存（すべて swap）

OperationActor は次の3点を注入で受け、コアは不変のまま本番化できる:

- **Store**（`talent.store/Store` プロトコル）: `MemStore`（既定）/ `DatomicStore`
  （`langchain.db` = Datomic-API 互換 EAV。`:db-api` で実 Datomic Local /
  kotoba-server pod に差し替え）。両者は同一契約テストで等価性を保証。
- **Advisor**（`talent.hrllm/Advisor` プロトコル）: `mock-advisor`（既定）/
  `llm-advisor`（`langchain.model` の ChatModel = Anthropic / OpenAI互換）。
  応答破損時は confidence 0 の noop に落ち、LLM 不調が auto-commit にならない。
- **Phase**（`talent.phase`、context の `:phase 0..3`）: 段階導入。read-only →
  assisted → supervised-auto。policy より保守的にしか働かない（hold/escalate を
  上乗せするだけ）。robotaxi の ODD 段階拡大と同型。

## 4. PolicyGovernor（独立検閲層）

`src/talent/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に判定する。

```clojure
(policy/check context proposal db)
;; => {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool}
```

判定の優先順位（上が強い）:

1. **権限分離 (RBAC)** — `permission` 表で `actor-role × operation × subject 関係`
   を引く。NG なら hard violation（人間承認でも上書き不可）。
2. **利用目的拘束** — `:purpose` 宣言と対象者 `:consent?`/法的根拠。欠落は hard violation。
3. **評価の公正性** — proposal の `:rationale`/`:cites` が**保護属性**を参照したら
   hard violation。`protected-attrs = #{:age :gender :nationality :creed :health :marital :pregnancy}`。
4. **個人情報の最小開示** — `:report/export` の列が目的の許可列を超えたら hard violation。
5. **確信度フロア** — `:confidence < 0.6` → `escalate?`（人間承認へ; soft）。
6. **重大操作** — `:grade-change :termination :pay-cut` → `high-stakes?`（必ず人間承認; soft）。

**hard violation は hold 固定**（規程違反を承認で押し通せない）。soft（escalate/
high-stakes）だけが ApprovalActor で人間が可否を決める。

## 5. SSoT と監査台帳

`src/talent/store.cljc`。dev は in-mem の EDN 事実層（本番は Datomic）。

- **entities**: `employees` `org`（manager 関係）`goals`（MBO/OKR）`surveys`。
- **commit-record!**: 操作結果を SSoT に反映。
- **append-ledger!**: 全 commit/reject を**不変台帳**に積む
  `{:t .. :op .. :actor .. :subject .. :disposition .. :basis ..}`。
- 種データは `m365-archive/facts/people` から seed 可能（gftd-keiei-sim と共有）。

「誰が誰の何をどの根拠で」を台帳の述語で問えることが、SaaS では得られない監査性。

## 6. 帳票/CSV（governed read）

`src/talent/report.cljc`。読み取りも OperationActor の `:report/export` を通し、
PolicyGovernor の**最小開示**ゲートで許可列のみを出力する。CSV/帳票は SaaS の
出力機能の代替で、列ポリシーをコードで固定できる点が上回る。

## 7. デモ（`clojure -M:dev:run`）

`src/talent/sim.cljc` が 4 ドメインを actor に通す:

```
op1  従業員DB upsert（HRBP・正当）            → commit
op2  評価ドラフトが「女性なので」と性別を引用   → PolicyGovernor が公正性で REJECT → hold
op3  帳票 export が病歴・年齢を過剰列に含む      → 最小開示で REJECT → hold
op4  サーベイ分析が離職予兆 high（重大）        → 人間承認へ escalate（interrupt）
                                              → HRBP approve → resume → commit
```

最後に監査台帳（commit/reject/承認）を表示 — 規程適合の証跡が同一ログから出る。

## 8. テスト（`clojure -M:dev:test`）

`test/talent/policy_contract_test.clj` が**規程契約を実行可能**にする:

- 正当な upsert は commit され台帳に残る。
- 無権限 role の操作は書き込まれない（RBAC hard violation → hold）。
- 保護属性を引いた評価は書き込まれない（公正性 hard violation → hold）。
- 過剰列の帳票は出力されない（最小開示 hard violation → hold）。
- 低確信は人間承認に interrupt し、approve で commit・reject で hold。
- すべての判定が監査台帳に1件ずつ積まれる（不変条件: 書き込みは必ず台帳経由）。

## 9. 実装と SaaS の対応（kaonavi → talent-actor）

| kaonavi の機能 | talent-actor での実体 |
|---|---|
| 従業員データベース | `store` employees + `:employee/upsert` |
| 組織図 | `store` org（manager 関係）の投影 |
| 人事評価ワークフロー | `:evaluation/draft`（LLMドラフト）+ ApprovalActor 承認 |
| 目標管理 MBO/OKR | `store` goals + GoalProgress |
| エンゲージメントサーベイ | `store` surveys + `:survey/analyze` |
| 離職予兆分析 | InsightActor AttritionRisk（LLM proposal + governor） |
| 帳票・CSV 出力 | `report` governed export（最小開示ゲート） |
| 承認フロー | `interrupt-before :request-approval`（human-in-the-loop） |
| アクセス権限 | PolicyGovernor RBAC 表 |
| （SaaS には無い）監査台帳 | `store` append-only ledger ← **上乗せ価値** |
| （SaaS には無い）データ主権 | SSoT = 自分の Datomic ← **上乗せ価値** |
