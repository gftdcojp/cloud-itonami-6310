(ns talent.store
  "Fact store — the SSoT for the talent actor, Datomic-shaped EDN entities
  held in-memory for dev (mirrors the gftd-keiei-sim 'kotoba-datomic SSoT +
  意思決定台帳' idiom; production swaps this for Datomic).

  Two surfaces:
    1. entities  — employees / org (manager links) / goals (MBO·OKR) /
                   surveys. The current talent state.
    2. ledger    — APPEND-ONLY audit log. Every commit AND every reject is
                   recorded here, so 'who changed/disclosed whose record,
                   when, on what basis' is a query over an immutable log.

  The ledger is the data-sovereignty + audit property a SaaS like kaonavi
  cannot give you: the SSoT is yours, and no decision touches a record
  without leaving a fact behind."
  (:require [clojure.string :as str]))

(defn seed-db
  "A small, self-contained org so the actor + tests run offline. In prod,
  seed from m365-archive/facts/people (shared with gftd-keiei-sim).

  Each employee carries operational fields AND a `:protected` map. The
  protected attributes exist precisely so the PolicyGovernor's fairness
  gate has something real to defend — they must never become a basis for
  an evaluation or be over-disclosed in a report."
  []
  (atom
   {:employees
    {"e-100" {:id "e-100" :name "田中 部長" :grade :G5 :dept "営業" :manager nil
              :protected {:age 51 :gender :m :nationality :jp :health "—"}}
     "e-001" {:id "e-001" :name "佐藤 花子" :grade :G3 :dept "営業" :manager "e-100"
              :protected {:age 34 :gender :f :nationality :jp :health "通院中"}}
     "e-002" {:id "e-002" :name "鈴木 太郎" :grade :G2 :dept "営業" :manager "e-100"
              :protected {:age 27 :gender :m :nationality :us :health "—"}}}
    ;; MBO/OKR goals keyed by employee id.
    :goals
    {"e-001" [{:id "g-1" :title "新規受注 12 件" :target 12 :actual 14 :period "2026H1"}
              {:id "g-2" :title "提案資料テンプレ整備" :target 1 :actual 1 :period "2026H1"}]
     "e-002" [{:id "g-3" :title "新規受注 8 件" :target 8 :actual 5 :period "2026H1"}]}
    ;; engagement survey responses keyed by employee id.
    :surveys
    {"e-001" {:engagement 4.2 :enps 30 :free "裁量が増えてやりがいがある"}
     "e-002" {:engagement 2.1 :enps -40 :free "評価基準が不透明。転職も考えている"}}
    ;; append-only audit ledger.
    :ledger []}))

(defn with-employees
  "Return `db` with its employee directory replaced by `employees` (e.g. the
  map hydrated from m365-archive facts by `talent.facts/load-employees`).
  Goals/surveys/ledger are untouched — production wires those from their own
  facts files the same way. No-op when `employees` is nil/empty, so callers
  can pass the facts result directly and fall back to the demo seed."
  [db employees]
  (when (seq employees)
    (swap! db assoc :employees employees))
  db)

;; ───────────────────────── read surface ─────────────────────────

(defn employee [db id] (get-in @db [:employees id]))

(defn org-children
  "Direct reports of `mgr-id` — the org-chart projection."
  [db mgr-id]
  (->> (vals (:employees @db))
       (filter #(= mgr-id (:manager %)))
       (sort-by :id)))

(defn goals-of [db id] (get-in @db [:goals id] []))

(defn survey-of [db id] (get-in @db [:surveys id]))

(defn ledger [db] (:ledger @db))

;; ───────────────────────── write surface ─────────────────────────
;; Only `operation.cljc`'s :commit node calls these, and only after the
;; PolicyGovernor has approved (or a human has approved an escalation).

(defn commit-record!
  "Apply a committed operation's `record` to the SSoT. The record's
  `:effect` says how to mutate state; unknown effects are inert (the
  ledger still captures intent)."
  [db {:keys [effect path value] :as _record}]
  (case effect
    :upsert-employee (swap! db update-in [:employees (:id value)] merge value)
    :set-goal-eval   (swap! db assoc-in [:evaluations (first path)] value)
    :store-insight   (swap! db assoc-in [:insights (first path)] value)
    ;; :report/export and analysis-only ops have no SSoT mutation.
    db)
  db)

(defn append-ledger!
  "Append one immutable decision fact. Returns the fact appended."
  [db fact]
  (swap! db update :ledger conj fact)
  fact)

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
