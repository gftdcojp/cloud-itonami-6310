(ns talent.hrllm
  "HR-LLM client — the *contained intelligence node*.

  It drafts evaluations, analyzes surveys, scores attrition risk and
  proposes approval routing. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record. Every output is censored downstream
  by `talent.policy` before anything touches the SSoT.

  Like robotaxi.ar1, this is a deterministic mock so the actor graph runs
  offline and the policy contract is exercised end-to-end. In production
  this calls a real LLM (kotoba-llm) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the fairness gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used — SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :grade-change/:termination/... if high-stakes
     :confidence 0..1}"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [langchain.model :as model]
            [talent.store :as store]))

(defn- pct [{:keys [target actual]}]
  (if (and target (pos? target)) (/ (double actual) target) 0.0))

(defn- draft-evaluation
  "Evaluation/goal draft from MBO/OKR actuals. The `:bias?` flag injects
  the failure mode we must defend against: citing a PROTECTED attribute
  (gender) as a basis — the PolicyGovernor must reject this."
  [db {:keys [subject bias?]}]
  (let [gs   (store/goals-of db subject)
        emp  (store/employee db subject)
        rate (if (seq gs) (/ (reduce + (map pct gs)) (count gs)) 0.0)
        band (cond (>= rate 1.0) "達成" (>= rate 0.7) "概ね達成" :else "未達")]
    (if bias?
      ;; OOD / unfair generation: leaks a protected attribute into rationale.
      {:summary    (str (:name emp) " は " band "。次期は昇給見送りが妥当。")
       :rationale  (str "女性で時短勤務のため成長期待は限定的と判断。達成率 "
                        (int (* 100 rate)) "%。")
       :cites      [:goals :gender :marital]
       :effect     :set-goal-eval
       :stake      :grade-change
       :confidence 0.82}
      {:summary    (str (:name emp) " は目標を" band "（達成率 "
                        (int (* 100 rate)) "%）。強み: 受注遂行力。")
       :rationale  (str "MBO 実績に基づく。" (str/join " / "
                          (map #(str (:title %) " " (:actual %) "/" (:target %)) gs)))
       :cites      [:goals]
       :effect     :set-goal-eval
       :stake      (when (< rate 0.5) :grade-change)
       :confidence (max 0.6 (min 0.95 (+ 0.5 (* 0.4 rate))))})))

(defn- analyze-survey
  "Engagement summary + attrition-risk score from survey + tenure signals.
  Low/sparse signal yields low confidence → governor escalates to human."
  [db {:keys [subject]}]
  (let [s (store/survey-of db subject)]
    (if (nil? s)
      {:summary "回答データなし" :rationale "サーベイ未回答"
       :cites [] :effect :store-insight :stake nil :confidence 0.2}
      (let [risk (cond (or (< (:engagement s) 2.5) (neg? (:enps s))) :high
                       (< (:engagement s) 3.5) :medium :else :low)]
        {:summary    (str "エンゲージメント " (:engagement s) " / eNPS " (:enps s)
                          " → 離職リスク " (name risk))
         :rationale  (str "定量2指標 + 自由記述の傾向。自由記述: 「" (:free s) "」")
         :cites      [:engagement :enps :free]
         :effect     :store-insight
         :stake      (when (= :high risk) :retention-action)
         ;; high-risk single-source reads are deliberately low-confidence:
         ;; a retention call should pass a human, not auto-commit.
         :confidence (if (= :high risk) 0.55 0.8)}))))

(defn- normalize-upsert
  "Directory upsert — the LLM only normalizes/validates the patch; it does
  not invent fields. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "従業員レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :upsert-employee
   :value      patch
   :stake      (when (contains? patch :grade) :grade-change)
   :confidence 0.97})

(defn- propose-columns
  "Report column proposal. `:greedy?` injects over-disclosure (pulls
  protected columns) — the minimal-disclosure gate must reject it."
  [_db {:keys [greedy?]}]
  (if greedy?
    {:summary "出力列: id,name,grade,age,health,gender"
     :rationale "分析に有用そうな列を広めに含めた。"
     :cites [:id :name :grade :age :health :gender]
     :columns [:id :name :grade :age :health :gender]
     :effect :report-export :stake nil :confidence 0.9}
    {:summary "出力列: id,name,grade,dept"
     :rationale "帳票目的に必要な最小列のみ。"
     :cites [:id :name :grade :dept]
     :columns [:id :name :grade :dept]
     :effect :report-export :stake nil :confidence 0.95}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :employee/upsert  (normalize-upsert db request)
    :evaluation/draft (draft-evaluation db request)
    :survey/analyze   (analyze-survey db request)
    :report/export    (propose-columns db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the PolicyGovernor
;; still censors — the single invariant never depends on which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは人事の助言者です。与えられた事実のみに基づき、提案を1つだけ "
       "EDN マップで返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(:set-goal-eval|:store-insight|:upsert-employee) "
       ":stake(:grade-change 等/無ければ nil) :confidence(0..1)。\n"
       "重要: 保護属性(:age :gender :nationality :creed :health :marital :pregnancy)を "
       "根拠(:cites/:rationale)にしてはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :evaluation/draft {:employee (store/employee st subject) :goals (store/goals-of st subject)}
    :survey/analyze   {:employee (store/employee st subject) :survey (store/survey-of st subject)}
    {:employee (store/employee st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the PolicyGovernor escalates/holds — an LLM
  hiccup can never auto-commit."
  [content]
  (let [p (try (edn/read-string (str/trim (str content))) (catch Exception _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (evaluation appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :hrllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
