(ns talent.report
  "ReportActor — 帳票/CSV output as a GOVERNED read. The column set is not
  chosen here; it is whatever the PolicyGovernor's minimal-disclosure gate
  approved for the declared purpose (see :report/export). This namespace
  only renders the approved columns, so a report can never disclose more
  than policy allows — the kaonavi 'CSV出力' feature, with the column
  policy fixed in code."
  (:require [clojure.string :as str]
            [talent.store :as store]))

(defn render-csv
  "Render employees as CSV over exactly `columns` (already policy-approved).
  Protected attributes live under :protected, so only explicitly-approved
  protected columns can ever appear (and the governor blocks those)."
  [db columns]
  (let [emps (store/all-employees db)
        cell (fn [e c] (str (or (get e c) (get-in e [:protected c]) "")))
        head (str/join "," (map name columns))
        rows (map (fn [e] (str/join "," (map #(cell e %) columns))) emps)]
    (str/join "\n" (cons head rows))))

(defn org-chart-text
  "Plain-text org chart from the manager links — the kaonavi 組織図 view."
  ([db root] (org-chart-text db root 0))
  ([db root depth]
   (let [e (store/employee db root)
         line (str (apply str (repeat depth "  ")) "└ " (:name e)
                   " (" (name (:grade e)) " / " (:dept e) ")")
         kids (store/org-children db root)]
     (str/join "\n" (cons line (map #(org-chart-text db (:id %) (inc depth)) kids))))))
