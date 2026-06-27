(ns talent.facts
  "Production seed adapter — hydrate the SSoT from the real org facts in
  `m365-archive/facts/people.edn` (line-delimited `:person/*` EDN, the same
  source gftd-keiei-sim seeds from) instead of the built-in demo employees.

  Graceful fallback is the whole point: `m365-archive` is a DataLad/git-annex
  dataset that is OFF by default (the working copy is just a pointer until
  `west annex-get` materializes it with B2 creds). So when the file is
  absent, empty, or still an annex pointer, this returns nil and the caller
  keeps the deterministic demo seed — the demo and the policy tests never
  depend on credentialed data.

  JVM-only (filesystem seed is a dev/ops concern); the actor core stays
  portable `.cljc`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-people-path
  "Relative to the project root (cwd when run via clojure -M)."
  "../m365-archive/facts/people.edn")

(defn annex-pointer?
  "True when `content` is an unmaterialized git-annex pointer rather than
  real EDN (datalad dataset not fetched)."
  [content]
  (boolean (re-find #"^/annex/objects/" (str/trim (or content "")))))

(defn read-people
  "Read line-delimited `:person/*` EDN. Returns [] when the file is missing,
  an annex pointer, or unparseable — never throws."
  [path]
  (let [f (io/file path)]
    (if (and (.exists f) (not (annex-pointer? (slurp f))))
      (->> (str/split-lines (slurp f))
           (map str/trim)
           (filter #(str/starts-with? % "{"))
           (keep #(try (edn/read-string %) (catch Exception _ nil))))
      [])))

(defn- person-id [p]
  (or (:person/id p) (:person/mail p) (some-> (:db/id p) str)))

(defn ->employee
  "Map one m365 `:person/*` fact to the actor's employee schema. Facts carry
  no HR grade, so default to :G1; protected attributes are intentionally NOT
  imported from M365 (the fairness gate has nothing to leak unless HR adds it
  deliberately)."
  [p]
  (let [id (person-id p)]
    (when id
      {:id        id
       :name      (or (:person/name p) id)
       :grade     (or (:person/grade p) :G1)
       :dept      (or (:person/dept p) (:person/department p) "—")
       :manager   (:person/manager p)
       :protected (or (:person/protected p) {})})))

(defn people->employees
  "Internal persons only → {id employee}. Externals (vendors/customers in the
  same facts file) are excluded from the employee directory."
  [people]
  (->> people
       (filter #(= :internal (:person/role %)))
       (keep ->employee)
       (map (juxt :id identity))
       (into {})))

(defn load-employees
  "Employees map hydrated from facts, or nil when facts are unavailable
  (caller falls back to the demo seed). `path` defaults to the m365 dataset."
  ([] (load-employees default-people-path))
  ([path]
   (let [emps (people->employees (read-people path))]
     (when (seq emps) emps))))
