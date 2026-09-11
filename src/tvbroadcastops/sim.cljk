(ns tvbroadcastops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean broadcast-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a programming-block scheduling
  request, equipment-maintenance-coordination request (both auto-commit
  clean at phase 3), then a content-concern flag (ALWAYS escalates, at
  any phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered station, a station registered but not yet verified, a
  proposal whose own `:effect` is not `:propose`, and a proposal that
  has drifted into the permanently-excluded on-air-content-decision-
  finalization/emergency-alert-broadcast-decision-finalization scope."
  (:require [langgraph.graph :as g]
            [tvbroadcastops.advisor :as advisor]
            [tvbroadcastops.store :as store]
            [tvbroadcastops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "broadcast-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :broadcast-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :broadcast-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-broadcast-record station-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-broadcast-record :station-id "station-1"
                                  :patch {:segment "evening-news" :on-air-log "2026-07-16T18:00Z"}} coordinator-phase-1)]
      (println r)
      (println "-- human broadcast coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-broadcast-record station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-broadcast-record :station-id "station-1"
                                  :patch {:segment "late-edition" :on-air-log "2026-07-16T23:00Z"}} coordinator-phase-3))

    (println "\n== schedule-broadcast-operation station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-broadcast-operation :station-id "station-1"
                                  :patch {:programming-block "prime-time" :start "2026-09-18T19:00Z"}} coordinator-phase-3))

    (println "\n== coordinate-equipment-maintenance station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-equipment-maintenance :station-id "station-1"
                                  :patch {:equipment "main-transmitter" :window "2026-09-01T03:00Z"}} coordinator-phase-3))

    (println "\n== flag-content-concern station-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-content-concern :station-id "station-1"
                                 :patch {:concern "possible FCC indecency-standard borderline segment" :confidence 0.9}} coordinator-phase-3)]
      (println r)
      (println "-- human broadcast coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-broadcast-record station-99 (unregistered station -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-broadcast-record :station-id "station-99"
                                  :patch {:segment "unknown"}} coordinator-phase-3))

    (println "\n== log-broadcast-record station-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-broadcast-record :station-id "station-3"
                                  :patch {:segment "unknown"}} coordinator-phase-3))

    (println "\n== schedule-broadcast-operation station-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-broadcast-operation :station-id "station-1"
                                           :patch {:programming-block "overnight"}} coordinator-phase-3)))

    (println "\n== log-broadcast-record station-1, advisor drifts into on-air-content/emergency-alert-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-broadcast-record :station-id "station-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed broadcast log ==")
    (doseq [r (store/broadcast-log db)] (println r))))
