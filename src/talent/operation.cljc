(ns talent.operation
  "OperationActor — one HR operation = one supervised actor run, expressed as
  a langgraph-clj StateGraph. The advisor (HR-LLM) is sealed into a single
  node (:advise); its proposal is ALWAYS routed through the PolicyGovernor
  (:govern) and the rollout phase gate (:decide) before anything commits to
  the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store     (MemStore | DatomicStore | kotoba-server) — `store` arg
    - the Advisor   (mock | real LLM)                          — :advisor opt
    - the Phase     (0→3 rollout)                              — :phase in ctx

  One graph run = one HR operation (intake → advise → govern → decide →
  commit | hold | approval). No unbounded inner loop — each operation is
  auditable and checkpointed.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to the ApprovalActor (an HRBP / manager). The approver resumes with
  `{:approval {:status :approved}}` (or :rejected)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [talent.hrllm :as hrllm]
            [talent.policy :as policy]
            [talent.phase :as phase]
            [talent.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request context proposal]
  {:effect  (:effect proposal)
   :value   (:value proposal)
   :path    [(:subject request)]
   :payload {:summary (:summary proposal) :by (:actor-id context)}})

(defn build
  "Compiles an OperationActor graph bound to `store` (any `talent.store/Store`).
  opts:
    :advisor      — a `talent.hrllm/Advisor` (default: mock-advisor)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (hrllm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected RBAC/purpose/consent/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; HR-LLM inference (the contained intelligence node) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (hrllm/-advise advisor store request)]
            {:proposal p :audit [(hrllm/trace request p)]})))

      ;; PolicyGovernor — independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (policy/check request context proposal store)}))

      ;; Decide: policy disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD policy violations → HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (policy/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :high-stakes
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff — paused by interrupt-before; ApprovalActor (human)
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload {:summary (:summary proposal)
                                      :approved-by (:by approval)})
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (policy/hold-fact request context
                                              (assoc verdict :violations
                                                     [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:policy-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
