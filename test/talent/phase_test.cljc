(ns talent.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only make
  the actor MORE conservative than policy: hold writes that aren't enabled yet,
  force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [talent.store :as store]
            [talent.operation :as op]))

(def hrbp {:actor-id "e-900" :actor-role :hrbp :purpose :review :consent? true})
(def upsert {:op :employee/upsert :subject "e-002" :patch {:id "e-002" :dept "X"}})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 upsert hrbp)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (= "営業" (:dept (store/employee s "e-002"))) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/export is a read → phase 0 lets it through (policy still applies)"
    (let [[_ res] (run 0 {:op :report/export :subject "*"}
                       (assoc hrbp :purpose :headcount))]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-write
  (testing "a clean upsert that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 upsert hrbp)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-survey-writes-under-approval
  (let [[_ res] (run 2 {:op :survey/analyze :subject "e-001"} hrbp)]
    ;; e-001 survey is healthy → policy-clean, but phase 2 still requires approval
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-write
  (let [[s res] (run 3 upsert hrbp)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "X" (:dept (store/employee s "e-002"))))))

(deftest policy-hold-beats-phase
  (testing "a hard policy violation holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :evaluation/draft :subject "e-001" :bias? true} hrbp)]
      (is (= :hold (get-in res [:state :disposition]))))))
