(ns talent.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what makes
  'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [talent.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "佐藤 花子" (:name (store/employee s "e-001"))))
      (is (= "e-100" (:manager (store/employee s "e-001"))) "ref resolves to manager id")
      (is (= ["e-001" "e-002"] (mapv :id (store/org-children s "e-100"))))
      (is (= 2 (count (store/goals-of s "e-001"))))
      (is (= 14 (:actual (first (store/goals-of s "e-001")))))
      (is (= -40 (:enps (store/survey-of s "e-002"))))
      (is (= {:age 34 :gender :f :nationality :jp :health "通院中"}
             (:protected (store/employee s "e-001")))
          "protected map round-trips (stored as EDN on Datomic, not a sub-entity)")
      (is (= ["e-001" "e-002" "e-100"] (mapv :id (store/all-employees s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :upsert-employee
                                 :value {:id "e-002" :dept "営業推進"}})
        (is (= "営業推進" (:dept (store/employee s "e-002"))))
        (is (= "鈴木 太郎" (:name (store/employee s "e-002"))) "name preserved")
        (is (= :G2 (:grade (store/employee s "e-002"))) "grade preserved"))
      (testing "eval / insight payloads commit and read back"
        (store/commit-record! s {:effect :set-goal-eval :path ["e-001"]
                                 :payload {:summary "達成" :by "e-900"}})
        (is (= {:summary "達成" :by "e-900"} (store/evaluation-of s "e-001")))
        (store/commit-record! s {:effect :store-insight :path ["e-002"]
                                 :payload {:summary "離職high"}})
        (is (= {:summary "離職high"} (store/insight-of s "e-002"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/employee s "nope")))
    (is (= [] (store/all-employees s)))
    (is (= [] (store/ledger s)))
    (store/with-employees s {"x" {:id "x" :name "X" :grade :G1 :dept "d"}})
    (is (= "X" (:name (store/employee s "x"))))))
