# gftd-talent-actor

A talent-management **actor design** — the OSS replacement for a HR SaaS
(kaonavi 等) that you run yourself, so you **never pay a SaaS to hold your
people data hostage**. Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) — the same actor pattern as
[`robotaxi-actor`](../../com-junkawasaki/robotaxi-actor).

> **Why an actor layer at all?** An HR-LLM is great at drafting
> evaluations, analyzing surveys and scoring attrition — but it has **no
> notion of permission, fairness, purpose-limitation or disclosure
> limits**. Letting it write personnel records directly invites
> discrimination, over-disclosure and permission breaches. This project
> seals the HR-LLM into a single node and wraps it with an independent
> **PolicyGovernor**, a human **approval workflow**, and an immutable
> **audit ledger** — the compliance, sovereignty and auditability a SaaS
> either charges for or won't give you at all.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record.

## The core contract

```
request + injected RBAC/purpose/consent context
        │
        ▼
   ┌─────────┐     proposal      ┌────────────────┐
   │ HR-LLM  │ ────────────────▶ │ PolicyGovernor │  (independent system)
   │ (sealed)│  draft + rationale│  RBAC · 公正性  │
   └─────────┘                   └───────┬────────┘
                            commit ◀─────┼─────▶ hold (規程違反; 上書き不可)
                                │              │
                          SSoT + 台帳     escalate ─▶ 人間承認 (interrupt)
```

**HR-LLM never commits or discloses a record the PolicyGovernor would
reject.** That single invariant is what lets a generative model run HR.
Hard violations (permission / purpose / fairness / over-disclosure) fall
back to **hold** and *cannot* be overridden by a human; only soft cases
(low confidence / high-stakes) go to the approval workflow.

## Run

```bash
clojure -M:dev:run     # drive the 4 kaonavi-equivalent domains through one OperationActor
clojure -M:dev:test    # the policy contract as executable tests
```

Demo output walks four operations: directory upsert (committed) → an
evaluation that cites gender as a basis (**fairness reject → hold**) → a
report that over-discloses health/age columns (**minimal-disclosure
reject → hold**) → a high-risk attrition finding (**escalate → human
approves → commit**), then prints the immutable audit ledger.

## Layout

| File | Actor / role |
|---|---|
| `src/talent/hrllm.cljc` | HR-LLM client — the contained intelligence node (mock inference) |
| `src/talent/policy.cljc` | **PolicyGovernor** — RBAC · purpose · fairness · minimal-disclosure · escalation |
| `src/talent/operation.cljc` | **OperationActor** — the langgraph-clj StateGraph (1 run = 1 HR op) |
| `src/talent/store.cljc` | **SSoT + audit ledger** — Datomic-shaped EDN facts (in-mem for dev) |
| `src/talent/facts.clj` | **seed adapter** — hydrate the SSoT from `m365-archive/facts/people.edn` (annex-aware fallback) |
| `src/talent/report.cljc` | **ReportActor** — governed CSV/帳票 + org-chart projection |
| `src/talent/sim.cljc` | demo driver |
| `test/talent/policy_contract_test.clj` | the policy invariant, executable |

## kaonavi 相当機能の対応

| kaonavi | gftd-talent-actor |
|---|---|
| 従業員DB / 組織図 | `store` employees/org + `:employee/upsert` |
| 人事評価・目標 MBO/OKR | `:evaluation/draft`（LLMドラフト）+ 承認ワークフロー |
| エンゲージメントサーベイ・離職予兆 | `:survey/analyze`（LLM + governor） |
| 帳票・CSV 出力 | `report` governed export（最小開示ゲート） |
| 承認フロー | `interrupt-before :request-approval`（human-in-the-loop） |
| アクセス権限 | PolicyGovernor RBAC 表 |
| — (SaaS には無い) | **不変の監査台帳** ＋ **データ主権**（SSoT は自分の Datomic） |

## 本番データへの接続（seed）

実データ（gftdcojp の社員）でSSoTを満たすには `talent.facts` を使う:

```clojure
(require '[talent.store :as store] '[talent.facts :as facts])
(-> (store/seed-db)
    (store/with-employees (facts/load-employees)))  ; m365-archive/facts/people.edn
```

`m365-archive` は DataLad/git-annex（既定 off）なので、実体が未取得（annex
pointer）・欠落のときは `load-employees` が `nil` を返し、**デモseedに自動
フォールバック**する。実体取得は `west update --group-filter +datalad m365-archive
&& west annex-get`（B2 creds は環境変数）。デモ（`sim`）は再現性のため常にデモseed。

## Status

設計実装 + 本番データ seed 接続まで完了（runnable + 11 tests / 31 assertions）。
残りの本番化 TODO: SSoT を in-mem から Datomic へ（`store` を protocol 化）、
HR-LLM mock を kotoba-llm 実推論へ（`hrllm/infer` を protocol 化）、goals/surveys
も facts 接続、Phase 0→3 の段階導入（ADR §帰結）。
