(ns tvbroadcastops.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (tvbroadcastops.operation -> tvbroadcastops.governor
  -> tvbroadcastops.store). No invented numbers, no timestamps, byte-identical
  across reruns."
  (:require [clojure.string :as str]
            [tvbroadcastops.store :as store]
            [tvbroadcastops.operation :as op]
            [tvbroadcastops.advisor :as advisor]
            [tvbroadcastops.phase :as phase]
            [tvbroadcastops.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private operator {:actor-id "coord-1" :actor-role :broadcast-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor StateGraph through a scenario built
  directly from `tvbroadcastops.store/demo-data` and
  `tvbroadcastops.governor`'s actual rules (this repo's `tvbroadcastops.sim`
  was run and checked -- its ids/ops match the real seed data and rules,
  so this mirrors the same scenario rather than reusing sim.cljc's -main
  directly, to keep this namespace's demo self-contained):

    1. `:log-broadcast-record` station-1 -- clean, phase-3 auto-commit.
    2. `:schedule-broadcast-operation` station-1 -- clean, phase-3
       auto-commit.
    3. `:coordinate-equipment-maintenance` station-1 -- clean, phase-3
       auto-commit.
    4. `:flag-content-concern` station-1 -- `governor/always-escalate-ops`,
       ALWAYS escalates even when clean -> human broadcast-coordinator
       approval -> commit.
    5. `:log-broadcast-record` station-99 -- no station record exists at
       all -> HARD hold, rule `:station-unverified`.
    6. `:log-broadcast-record` station-3 -- registered but NOT verified
       (`:registered? true :verified? false` in `store/demo-data`) ->
       HARD hold, rule `:station-unverified` (same rule, the other
       failure flavor: exists-but-unverified vs. does-not-exist-at-all).
    7. `:schedule-broadcast-operation` station-1, advisor drifts into
       claiming a direct actuation (`:effect :commit` instead of
       `:propose`) -> HARD hold, rule `:effect-not-propose`.
    8. `:log-broadcast-record` station-1, advisor drifts into the
       permanently-excluded on-air-content/emergency-alert-broadcast
       finalization scope (`:out-of-scope? true`) -> HARD hold, rule
       `:scope-excluded`.

  Returns the seeded `db` (a `tvbroadcastops.store/MemStore`) after the
  run, so `render` can read every value straight off it."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :log-broadcast-record :station-id "station-1"
                        :patch {:segment "evening-news" :on-air-log "2026-07-16T18:00Z"}})

    (exec! actor "t2" {:op :schedule-broadcast-operation :station-id "station-1"
                        :patch {:programming-block "prime-time" :start "2026-09-18T19:00Z"}})

    (exec! actor "t3" {:op :coordinate-equipment-maintenance :station-id "station-1"
                        :patch {:equipment "main-transmitter" :window "2026-09-01T03:00Z"}})

    (exec! actor "t4" {:op :flag-content-concern :station-id "station-1"
                        :patch {:concern "possible FCC indecency-standard borderline segment" :confidence 0.9}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :log-broadcast-record :station-id "station-99"
                        :patch {:segment "unknown"}})

    (exec! actor "t6" {:op :log-broadcast-record :station-id "station-3"
                        :patch {:segment "unknown"}})

    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "t7" {:op :schedule-broadcast-operation :station-id "station-1"
                                 :patch {:programming-block "overnight"}}))

    (exec! actor "t8" {:op :log-broadcast-record :station-id "station-1"
                        :out-of-scope? true
                        :patch {}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `station-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:station-id` (see `tvbroadcastops.operation/commit-fact` and
  `tvbroadcastops.governor/hold-fact`)."
  [ledger station-id]
  (last (filter #(= station-id (:station-id %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a station --
  the same cond pattern used fleet-wide."
  [fact]
  (cond
    (nil? fact)                       ["muted" "in progress"]
    (= :committed (:t fact))          ["ok" "committed"]
    (= :approval-granted (:t fact))   ["ok" "approval-granted"]
    (= :governor-hold (:t fact))      ["err" (str "governor-hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))  ["err" "approval-rejected"]
    (= :approval-requested (:t fact)) ["warn" "approval-requested"]
    :else                             ["muted" "in progress"]))

(defn- stations-table [db]
  (let [stations (store/all-stations db)
        ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>station-id</th><th>call sign</th><th>name</th><th>registered?</th><th>verified?</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [st stations
            :let [fact (last-fact-for ledger (:station-id st))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:station-id st)) "</code></td>"
             "<td><code>" (esc (:call-sign st)) "</code></td>"
             "<td>" (esc (:name st)) "</td>"
             "<td>" (if (:registered? st) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:verified? st) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- broadcast-log-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>op</th><th>station-id</th><th>value</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [r (store/broadcast-log db)]
      (str "<tr>"
           "<td><code>" (esc (:op r)) "</code></td>"
           "<td><code>" (esc (:station-id r)) "</code></td>"
           "<td><code>" (esc (:value r)) "</code></td>"
           "</tr>")))
   "\n</tbody></table>"))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `tvbroadcastops.phase/phases` (phase 3, this actor's `default-phase`)
  and `tvbroadcastops.governor/always-escalate-ops` -- not invented,
  just rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th><th>auto-eligible?</th><th>always escalates?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort phase/write-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (contains? governor/always-escalate-ops op) "<span class=\"critical\">yes</span>" "no") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>station-id</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:station-id f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "err" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>tvbroadcastops.render-html -- Broadcast License Governor operator console</title>\n"
   "<style>\n" css "\n</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Broadcast License Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 6020 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Stations</h2>\n" (stations-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Committed broadcast log</h2>\n" (broadcast-log-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (tvbroadcastops.phase &middot; tvbroadcastops.governor/always-escalate-ops)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
