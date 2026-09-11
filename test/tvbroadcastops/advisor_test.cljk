(ns tvbroadcastops.advisor-test
  "Unit tests of `tvbroadcastops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [tvbroadcastops.advisor :as adv]
            [tvbroadcastops.store :as store]))

(def db (store/seed-db))

(deftest propose-broadcast-record-shape
  (testing "broadcast-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-broadcast-record
                           :station-id "station-1"
                           :patch {:segment "evening-news" :on-air-log "2026-07-16T18:00Z"}})]
      (is (= :log-broadcast-record (:op p)))
      (is (= "station-1" (:station-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :station-id)))))

(deftest propose-broadcast-operation-shape
  (testing "broadcast-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-broadcast-operation
                           :station-id "station-2"
                           :patch {:programming-block "prime-time"}})]
      (is (= :schedule-broadcast-operation (:op p)))
      (is (= "station-2" (:station-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-content-concern-shape
  (testing "content-concern proposal has correct shape"
    (let [p (adv/infer db {:op :flag-content-concern
                           :station-id "station-1"
                           :patch {:concern "possible FCC borderline segment"}})]
      (is (= :flag-content-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-equipment-maintenance-shape
  (testing "equipment-maintenance proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-equipment-maintenance
                           :station-id "station-1"
                           :patch {:equipment "main-transmitter" :window "2026-09-01T03:00Z"}})]
      (is (= :coordinate-equipment-maintenance (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :flag-content-concern :coordinate-equipment-maintenance]]
      (let [p (adv/infer db {:op op :station-id "station-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :flag-content-concern :coordinate-equipment-maintenance]]
      (let [p (adv/infer db {:op op :station-id "station-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
