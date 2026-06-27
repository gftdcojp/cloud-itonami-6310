(ns talent.store
  "SSoT for the talent actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store (datalog q / pull / ref attrs / upsert). Pure
                       `.cljc`, so it runs offline AND can be pointed at a real
                       Datomic Local or a kotoba-server pod by swapping
                       `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/talent/store_contract_test.clj), which is the whole point: the actor,
  the PolicyGovernor and the audit ledger never know which SSoT they run on.

  The ledger stays append-only on every backend — 'who changed/disclosed
  whose record, on what basis' is always a query over an immutable log, the
  data-sovereignty property a SaaS won't give you."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (employee [s id])
  (all-employees [s])
  (org-children [s mgr-id])
  (goals-of [s id])
  (survey-of [s id])
  (evaluation-of [s id] "committed evaluation payload for an employee, or nil")
  (insight-of [s id]    "committed survey insight payload for an employee, or nil")
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-employees [s employees] "replace/seed the employee directory (map id→emp)")
  (with-goals [s goals]         "replace/seed goals (map id→[goal])")
  (with-surveys [s surveys]     "replace/seed surveys (map id→survey)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "A small, self-contained org so the actor + tests run offline. In prod,
  seed from m365-archive facts via `talent.facts` (shared with gftd-keiei-sim).

  Each employee carries operational fields AND a `:protected` map. The
  protected attributes exist precisely so the PolicyGovernor's fairness gate
  has something real to defend — they must never become a basis for an
  evaluation or be over-disclosed in a report."
  []
  {:employees
   {"e-100" {:id "e-100" :name "田中 部長" :grade :G5 :dept "営業" :manager nil
             :protected {:age 51 :gender :m :nationality :jp :health "—"}}
    "e-001" {:id "e-001" :name "佐藤 花子" :grade :G3 :dept "営業" :manager "e-100"
             :protected {:age 34 :gender :f :nationality :jp :health "通院中"}}
    "e-002" {:id "e-002" :name "鈴木 太郎" :grade :G2 :dept "営業" :manager "e-100"
             :protected {:age 27 :gender :m :nationality :us :health "—"}}}
   :goals
   {"e-001" [{:id "g-1" :title "新規受注 12 件" :target 12 :actual 14 :period "2026H1"}
             {:id "g-2" :title "提案資料テンプレ整備" :target 1 :actual 1 :period "2026H1"}]
    "e-002" [{:id "g-3" :title "新規受注 8 件" :target 8 :actual 5 :period "2026H1"}]}
   :surveys
   {"e-001" {:engagement 4.2 :enps 30 :free "裁量が増えてやりがいがある"}
    "e-002" {:engagement 2.1 :enps -40 :free "評価基準が不透明。転職も考えている"}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (employee [_ id] (get-in @a [:employees id]))
  (all-employees [_] (sort-by :id (vals (:employees @a))))
  (org-children [_ mgr-id]
    (->> (vals (:employees @a)) (filter #(= mgr-id (:manager %))) (sort-by :id)))
  (goals-of [_ id] (get-in @a [:goals id] []))
  (survey-of [_ id] (get-in @a [:surveys id]))
  (evaluation-of [_ id] (get-in @a [:evaluations id]))
  (insight-of [_ id] (get-in @a [:insights id]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :upsert-employee (swap! a update-in [:employees (:id value)] merge value)
      :set-goal-eval   (swap! a assoc-in [:evaluations (first path)] payload)
      :store-insight   (swap! a assoc-in [:insights (first path)] payload)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-employees [s emps] (when (seq emps) (swap! a assoc :employees emps)) s)
  (with-goals [s g]        (when (seq g)    (swap! a assoc :goals g)) s)
  (with-surveys [s sv]     (when (seq sv)   (swap! a assoc :surveys sv)) s))

(defn seed-db
  "A MemStore seeded with the demo org. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :evaluations {} :insights {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (protected attrs, payloads, ledger facts) are stored as
  EDN strings so `langchain.db` doesn't expand them into sub-entities."
  {:emp/id      {:db/unique :db.unique/identity}
   :emp/mgr     {:db/valueType :db.type/ref}
   :goal/id     {:db/unique :db.unique/identity}
   :goal/emp    {:db/valueType :db.type/ref}
   :survey/emp  {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :eval/emp    {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :insight/emp {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :ledger/seq  {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- emp->tx [{:keys [id name grade dept manager protected]}]
  (cond-> {:emp/id id}
    name      (assoc :emp/name name)
    grade     (assoc :emp/grade grade)
    dept      (assoc :emp/dept dept)
    protected (assoc :emp/protected (enc protected))
    manager   (assoc :emp/mgr [:emp/id manager])))

(defn- pull->emp [m]
  (when (:emp/id m)
    {:id      (:emp/id m)
     :name    (:emp/name m)
     :grade   (:emp/grade m)
     :dept    (:emp/dept m)
     :manager (get-in m [:emp/mgr :emp/id])
     :protected (or (dec* (:emp/protected m)) {})}))

(def ^:private emp-pull
  [:emp/id :emp/name :emp/grade :emp/dept :emp/protected {:emp/mgr [:emp/id]}])

(defn- goal->tx [emp-id {:keys [id title target actual period]}]
  {:goal/id id :goal/emp [:emp/id emp-id]
   :goal/title title :goal/target target :goal/actual actual :goal/period period})

(defn- pull->goal [m]
  {:id (:goal/id m) :title (:goal/title m) :target (:goal/target m)
   :actual (:goal/actual m) :period (:goal/period m)})

(defrecord DatomicStore [conn]
  Store
  (employee [_ id]
    (pull->emp (d/pull (d/db conn) emp-pull [:emp/id id])))
  (all-employees [_]
    (->> (d/q '[:find [?id ...] :where [?e :emp/id ?id]] (d/db conn))
         (map #(pull->emp (d/pull (d/db conn) emp-pull [:emp/id %])))
         (sort-by :id)))
  (org-children [_ mgr-id]
    (->> (d/q '[:find [?cid ...] :in $ ?mid
                :where [?m :emp/id ?mid] [?c :emp/mgr ?m] [?c :emp/id ?cid]]
              (d/db conn) mgr-id)
         (map #(pull->emp (d/pull (d/db conn) emp-pull [:emp/id %])))
         (sort-by :id)))
  (goals-of [_ id]
    (->> (d/q '[:find [?gid ...] :in $ ?eid
                :where [?e :emp/id ?eid] [?g :goal/emp ?e] [?g :goal/id ?gid]]
              (d/db conn) id)
         (map #(pull->goal (d/pull (d/db conn)
                                   [:goal/id :goal/title :goal/target :goal/actual :goal/period]
                                   [:goal/id %])))
         (sort-by :id)
         vec))
  (survey-of [_ id]
    (when-let [sid (d/q '[:find ?s . :in $ ?eid
                          :where [?e :emp/id ?eid] [?s :survey/emp ?e]]
                        (d/db conn) id)]
      (let [m (d/pull (d/db conn) [:survey/engagement :survey/enps :survey/free] sid)]
        {:engagement (:survey/engagement m) :enps (:survey/enps m) :free (:survey/free m)})))
  (evaluation-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                 :where [?e :emp/id ?eid] [?v :eval/emp ?e] [?v :eval/payload ?p]]
               (d/db conn) id)))
  (insight-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                 :where [?e :emp/id ?eid] [?v :insight/emp ?e] [?v :insight/payload ?p]]
               (d/db conn) id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :upsert-employee (d/transact! conn [(emp->tx value)])
      :set-goal-eval   (d/transact! conn [{:eval/emp [:emp/id (first path)]
                                           :eval/payload (enc payload)}])
      :store-insight   (d/transact! conn [{:insight/emp [:emp/id (first path)]
                                           :insight/payload (enc payload)}])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-employees [s emps]
    (when (seq emps) (d/transact! conn (mapv emp->tx (vals emps)))) s)
  (with-goals [s goals]
    (when (seq goals)
      (d/transact! conn (vec (for [[eid gs] goals g gs] (goal->tx eid g))))) s)
  (with-surveys [s surveys]
    (when (seq surveys)
      (d/transact! conn (vec (for [[eid sv] surveys]
                               {:survey/emp [:emp/id eid]
                                :survey/engagement (:engagement sv)
                                :survey/enps (:enps sv) :survey/free (:free sv)}))))
    s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:employees .. :goals .. :surveys ..}); empty when omitted. Employees must
  be transacted before goals/surveys (ref lookups)."
  ([] (datomic-store {}))
  ([{:keys [employees goals surveys]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-employees employees) (with-goals goals) (with-surveys surveys)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo org — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
