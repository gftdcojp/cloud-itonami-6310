(ns talent.facts-test
  "Tests for the m365-archive facts seed adapter, including the graceful
  fallback when the DataLad/git-annex dataset isn't materialized."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [talent.facts :as facts]
            [talent.store :as store]))

(def fixture
  "Line-delimited :person/* EDN, the m365-archive/facts/people.edn shape.
  Mixes an internal employee, an internal employee with explicit grade/dept,
  and an external contact that must be excluded from the directory."
  (str "{:person/id \"u-1\" :person/name \"山田 一郎\" :person/role :internal "
       ":person/dept \"開発\" :person/manager \"u-0\"}\n"
       "{:person/id \"u-2\" :person/name \"X 社 担当\" :person/role :external}\n"
       "; a comment line and a blank line below should be ignored\n\n"
       "{:person/id \"u-3\" :person/name \"鈴木 三郎\" :person/role :internal "
       ":person/grade :G4 :person/dept \"営業\"}\n"))

(defn- write-tmp! [content]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "talent-facts-" (hash content) ".edn"))]
    (spit f content)
    (.getPath f)))

(deftest reads-and-maps-internal-people
  (let [emps (facts/load-employees (write-tmp! fixture))]
    (is (= #{"u-1" "u-3"} (set (keys emps))) "externals excluded; internals kept")
    (is (= {:id "u-1" :name "山田 一郎" :grade :G1 :dept "開発"
            :manager "u-0" :protected {}}
           (get emps "u-1"))
        "facts with no grade default to :G1; protected attrs not imported")
    (is (= :G4 (:grade (get emps "u-3"))) "explicit grade preserved")))

(deftest annex-pointer-falls-back
  (testing "an unmaterialized git-annex pointer yields no employees (→ demo seed)"
    (let [ptr "/annex/objects/MD5E-s928039--32c9b4cd6ef89c12178b162c78a99088.edn\n"]
      (is (facts/annex-pointer? ptr))
      (is (= [] (facts/read-people (write-tmp! ptr))))
      (is (nil? (facts/load-employees (write-tmp! ptr)))
          "load-employees returns nil so the caller keeps the demo seed"))))

(deftest missing-file-falls-back
  (is (= [] (facts/read-people "/no/such/people.edn")))
  (is (nil? (facts/load-employees "/no/such/people.edn"))))

(deftest store-hydration-is-opt-in-and-safe
  (testing "with-employees replaces the directory; nil/empty is a no-op"
    (let [db (store/seed-db)
          demo-ids (set (keys (:employees @db)))]
      (store/with-employees db nil)
      (is (= demo-ids (set (keys (:employees @db)))) "nil → demo seed kept")
      (store/with-employees db (facts/load-employees (write-tmp! fixture)))
      (is (= #{"u-1" "u-3"} (set (keys (:employees @db)))) "facts hydrate the SSoT")
      ;; goals/ledger untouched by employee hydration
      (is (contains? (:goals @db) "e-001"))
      (is (= [] (:ledger @db))))))
