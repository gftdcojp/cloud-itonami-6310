(ns talent.policy
  "PolicyGovernor — the independent compliance layer that earns the HR-LLM
  the right to commit. The LLM has no notion of permission, purpose,
  fairness or disclosure limits, so this MUST be a separate system
  (rules + permission table + protected-attribute table) able to *reject*
  a proposal and fall back to HOLD (write nothing) — the HR analog of
  robotaxi's Minimal Risk Condition.

  Six checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past a discrimination or a permission breach). The last two are SOFT:
  they only ask a human to look (low confidence / high-stakes), and the
  human may approve.

    1. RBAC                — role × operation × subject-relation permitted?
    2. Purpose limitation  — declared purpose + subject consent/legal basis?
    3. Fairness            — did the rationale cite a PROTECTED attribute?
    4. Minimal disclosure  — does an export exceed the purpose's allowed cols?
    5. Confidence floor    — LLM confidence below threshold → escalate.
    6. High-stakes gate    — grade-change/termination/... → escalate."
  (:require [clojure.set :as set]
            [talent.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def protected-attrs
  "Attributes that must NEVER be a basis for evaluation or be over-disclosed."
  #{:age :gender :nationality :creed :health :marital :pregnancy})

(def confidence-floor 0.6)

(def high-stakes
  "Operations grave enough to always require a human, even when clean."
  #{:grade-change :termination :pay-cut :retention-action})

(def permissions
  "role → set of operations it may perform. `:manager` is further
  restricted to its own reports by `subject-allowed?`."
  {:hrbp     #{:employee/upsert :evaluation/draft :survey/analyze :report/export}
   :manager  #{:evaluation/draft :survey/analyze}
   :employee #{}})

(def purpose-columns
  "For :report/export — the columns each declared purpose may disclose.
  Anything beyond this is over-disclosure (minimal-disclosure violation)."
  {:headcount   #{:id :name :grade :dept}
   :org-chart   #{:id :name :grade :dept :manager}
   :payroll     #{:id :name :grade :dept}})

;; ───────────────────────── checks ─────────────────────────

(defn- subject-allowed?
  "A manager may only act on their own direct reports; HRBP on anyone."
  [{:keys [actor-role actor-id]} subject st]
  (case actor-role
    :hrbp true
    :manager (= actor-id (:manager (store/employee st subject)))
    false))

(defn- rbac-violations [{:keys [op]} {:keys [actor-role] :as ctx} subject st]
  (cond-> []
    (not (contains? (get permissions actor-role #{}) op))
    (conj {:rule :rbac :detail (str actor-role " は " op " の権限を持たない")})
    (and (contains? (get permissions actor-role #{}) op)
         (not (subject-allowed? ctx subject st)))
    (conj {:rule :rbac-subject :detail (str actor-role " は対象 " subject " に権限が及ばない")})))

(defn- purpose-violations [{:keys [purpose consent?]}]
  (cond-> []
    (nil? purpose)         (conj {:rule :purpose :detail "利用目的が宣言されていない"})
    (false? consent?)      (conj {:rule :consent :detail "対象者の同意/法的根拠が無い"})))

(defn- fairness-violations
  "Scoped to evaluative ops: did the rationale CITE a protected attribute as
  a basis for a judgement? Report disclosure is governed separately by
  `disclosure-violations`, so reports don't double-count here."
  [{:keys [op]} proposal]
  (when (not= op :report/export)
    (let [cited (set (map keyword (:cites proposal)))
          bad   (set/intersection cited protected-attrs)]
      (when (seq bad)
        [{:rule :fairness :detail (str "保護属性を判断根拠に使用: " (vec bad))}]))))

(defn- disclosure-violations [{:keys [op]} {:keys [purpose]} proposal]
  (when (= op :report/export)
    (let [allowed (get purpose-columns purpose #{})
          cols    (set (:columns proposal))
          extra   (set/difference cols allowed)]
      (when (seq extra)
        [{:rule :minimal-disclosure
          :detail (str "目的 " purpose " に対し過剰な列: " (vec extra))}]))))

(defn check
  "Censors an HR-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       — at least one HARD violation (RBAC/purpose/fairness/
                    disclosure). Forces HOLD; a human cannot override.
   - :escalate?   — soft: low confidence OR high-stakes. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit."
  [request context proposal st]
  (let [subject (:subject request)
        hard    (into []
                      (concat (rbac-violations request context subject st)
                              (purpose-violations context)
                              (fairness-violations request proposal)
                              (disclosure-violations request context proposal)))
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard?   (boolean (seq hard))]
    {:ok?         (and (not hard?) (not low?) (not stakes?))
     :violations  hard
     :confidence  conf
     :hard?       hard?
     ;; soft escalation only matters when there is no hard violation —
     ;; a hard violation always wins and goes straight to HOLD.
     :escalate?   (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD). The HR analog
  of robotaxi logging a safety-reject + MRC."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
