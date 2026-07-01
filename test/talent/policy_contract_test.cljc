(ns talent.policy-contract-test
  "The policy contract as executable tests — the HR analog of robotaxi's
  safety_contract_test. The single invariant under test:

    HR-LLM never writes/discloses a record the PolicyGovernor would reject,
    and every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [talent.store :as store]
            [talent.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def hrbp {:actor-id "e-900" :actor-role :hrbp :purpose :review :consent? true})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-upsert-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :employee/upsert :subject "e-002"
                   :patch {:id "e-002" :dept "営業推進"}} hrbp)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "営業推進" (:dept (store/employee db "e-002"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "an :employee role has no upsert permission → HOLD, no write"
    (let [[db actor] (fresh)
          before (store/employee db "e-002")
          res (exec-op actor "t2"
                    {:op :employee/upsert :subject "e-002"
                     :patch {:id "e-002" :dept "経営企画"}}
                    {:actor-id "e-002" :actor-role :employee
                     :purpose :review :consent? true})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= before (store/employee db "e-002")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest manager-cannot-act-outside-reports
  (testing "a manager may only act on their own direct reports"
    (let [[_ actor] (fresh)
          ;; e-100 manages e-001/e-002; acting on a non-report must HOLD.
          res (exec-op actor "t3"
                    {:op :evaluation/draft :subject "e-100"}
                    {:actor-id "e-100" :actor-role :manager
                     :purpose :review :consent? true})]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest fairness-violation-is-held
  (testing "an evaluation citing a protected attribute (gender) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :evaluation/draft :subject "e-001" :bias? true} hrbp)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:fairness} (-> (store/ledger db) first :basis))
          "fairness is the basis for the hold")
      (is (nil? (store/evaluation-of db "e-001")) "no evaluation written"))))

(deftest over-disclosure-is-held
  (testing "a report pulling protected columns beyond the purpose → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :report/export :subject "*" :greedy? true}
                    (assoc hrbp :purpose :headcount))]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:minimal-disclosure} (-> (store/ledger db) first :basis))))))

(deftest high-stakes-low-confidence-escalates-then-human-decides
  (testing "high-risk attrition finding interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t6"
                   {:op :survey/analyze :subject "e-002"} hrbp)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "e-100"}}
                         {:thread-id "t6" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t7" {:op :survey/analyze :subject "e-002"} hrbp)
          r2 (g/run* actor {:approval {:status :rejected :by "e-100"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/insight-of db "e-002")) "nothing committed on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :employee/upsert :subject "e-002"
                       :patch {:id "e-002" :dept "X"}} hrbp)
      (exec-op actor "b" {:op :evaluation/draft :subject "e-001" :bias? true} hrbp)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
