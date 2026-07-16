(ns tvbroadcastops.governor-test
  "Pure unit tests of `tvbroadcastops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [tvbroadcastops.advisor :as advisor]
            [tvbroadcastops.governor :as gov]
            [tvbroadcastops.store :as store]))

(def station-1 {:station-id "station-1" :call-sign "KNDA-TV" :name "Kanda Community Television" :registered? true :verified? true})
(def station-3 {:station-id "station-3" :call-sign "SGMI-TV" :name "Sugamo Independent Media" :registered? true :verified? false})

(defn- clean-proposal [op station-id]
  {:op op :station-id station-id :summary "s" :rationale "routine broadcast coordination"
   :cites [station-id] :effect :propose :value {} :confidence 0.85})

(deftest station-unregistered-is-hard
  (testing "no station record at all -> HARD hold"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (clean-proposal :log-broadcast-record "unknown-station") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:station-unverified} (map :rule (:violations verdict)))))))

(deftest station-unverified-is-hard
  (testing "station registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"station-3" station-3})
          verdict (gov/check {} nil (clean-proposal :log-broadcast-record "station-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:station-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-broadcast-operation "station-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (clean-proposal :broadcast-emergency-alert "station-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest content-decision-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale finalizes an on-air-content decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :log-broadcast-record "station-1")
                          :rationale "decided to finalize the on-air content decision for tonight's broadcast"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest emergency-alert-finalization-content-is-hard
  (testing "a proposal touching finalizing an emergency-alert-broadcast decision is HARD-blocked, same as content"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :schedule-broadcast-operation "station-1")
                          :rationale "decided to authorize the emergency alert broadcast and trigger the emergency alert broadcast for Q4"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest on-air-content-decision-in-summary-is-hard
  (testing "a proposal touching finalizing the on-air content decision via summary is HARD-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :coordinate-equipment-maintenance "station-1")
                          :summary "authorize the on-air content decision ahead of the maintenance handoff")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest emergency-alert-content-in-value-is-hard
  (testing "a proposal whose draft value issues the emergency alert broadcast is HARD-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :log-broadcast-record "station-1")
                          :value {:decision "issue the emergency alert broadcast for the tri-county area"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-content-concern-is-not-scope-excluded
  (testing "flagging a possible FCC/on-air/emergency-alert CONCERN (not a finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          concern (assoc (clean-proposal :flag-content-concern "station-1")
                         :value {:concern "possible FCC indecency-standard borderline segment during a live broadcast, and an unrelated emergency alert test glitch"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (content/emergency-alert doubts) is exactly what this op exists to surface"))))

;; ----------------------------- self-trip regression (mandatory) -----------------------------
;;
;; A known bug class in this exact codebase family: a governor's own
;; scope-exclusion term list phrased as a bare noun can accidentally
;; match inside the mock advisor's own DEFAULT rationale/disclaimer
;; text for a legitimate, allowed proposal -- causing the actor to
;; self-block on its own happy path. This actor's `scope-excluded-terms`
;; are deliberately phrased as the finalization/execution ACTION
;; ('finalize the on-air content decision', not bare 'content
;; decision'). This test asserts the default mock advisor's own
;; proposals for all four allowed ops, for a clean registered+verified
;; station, NEVER trip scope-exclusion -- i.e. the actor never
;; self-blocks on its own happy path.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "none of the four default proposal generators' own rationale/summary/value text self-trips scope-exclusion"
    (let [s (store/mem-store {"station-1" station-1})]
      (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                  :flag-content-concern :coordinate-equipment-maintenance]]
        (let [proposal (advisor/infer nil {:op op :station-id "station-1"
                                            :patch {:segment "evening-news" :on-air-log "2026-07-16T18:00Z"
                                                    :programming-block "prime-time"
                                                    :equipment "main-transmitter"
                                                    :window "2026-09-01T03:00Z"
                                                    :concern "possible FCC indecency-standard borderline segment"}})
              verdict (gov/check {:station-id "station-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default proposal for op " op " must never self-trip scope-exclusion; got violations: "
                   (:violations verdict)))
          (is (not (:hard? verdict))
              (str "default proposal for op " op " (clean, registered+verified station) must never HARD hold")))))))
