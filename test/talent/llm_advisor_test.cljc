(ns talent.llm-advisor-test
  "The real-inference advisor (langchain.model ChatModel), driven offline by
  langchain's mock-model. Proves: a real LLM proposal is parsed, still fully
  censored by the PolicyGovernor, and that an unparseable/garbage response can
  never auto-commit."
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [talent.hrllm :as hrllm]
            [talent.policy :as policy]
            [talent.store :as store]))

(def hrbp {:actor-id "e-900" :actor-role :hrbp :purpose :review :consent? true})
(def req  {:op :evaluation/draft :subject "e-001"})

(defn- advise-with [content]
  (hrllm/-advise (hrllm/llm-advisor (model/mock-model [{:role :assistant :content content}]))
                 (store/seed-db) req))

(deftest clean-llm-proposal-is-parsed-and-accepted
  (let [p (advise-with (str "{:summary \"目標を達成\" :rationale \"MBO実績に基づく\" "
                            ":cites [:goals] :effect :set-goal-eval :stake nil :confidence 0.82}"))]
    (is (= :set-goal-eval (:effect p)))
    (is (= [:goals] (:cites p)))
    (is (= 0.82 (:confidence p)))
    (testing "the governor accepts the clean LLM proposal"
      (is (:ok? (policy/check req hrbp p (store/seed-db)))))))

(deftest llm-citing-protected-attr-is-rejected
  (testing "even a confident LLM can't evaluate on gender — fairness gate holds"
    (let [p (advise-with (str "{:summary \"昇給見送り\" :rationale \"女性で時短のため\" "
                              ":cites [:goals :gender] :effect :set-goal-eval :confidence 0.85}"))
          v (policy/check req hrbp p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:fairness} (map :rule (:violations v)))))))

(deftest unparseable-llm-output-never-auto-commits
  (testing "garbage / refusal → safe noop at confidence 0 → governor won't pass it"
    (let [p (advise-with "すみません、その操作には対応できません。")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p)))
      (is (not (:ok? (policy/check req hrbp p (store/seed-db))))))))
