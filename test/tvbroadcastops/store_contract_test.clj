(ns tvbroadcastops.store-contract-test
  "Contract tests for `tvbroadcastops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [tvbroadcastops.store :as store]))

(deftest mem-store-station-lookup
  (testing "MemStore can store and retrieve stations by ID (string keys)"
    (let [stations {"t1" {:station-id "t1" :name "Alice's Broadcast Group" :registered? true :verified? true}}
          s (store/mem-store stations)]
      (is (some? (store/station s "t1")))
      (is (nil? (store/station s "t99"))))))

(deftest mem-store-all-stations
  (testing "MemStore returns all stations in sorted order"
    (let [stations {"t2" {:station-id "t2" :name "Bob's Broadcasting"}
                     "t1" {:station-id "t1" :name "Alice's Broadcast Group"}
                     "t3" {:station-id "t3" :name "Carol's Community TV"}}
          s (store/mem-store stations)
          all-s (store/all-stations s)]
      (is (= 3 (count all-s)))
      (is (= "t1" (:station-id (first all-s))))
      (is (= "t3" (:station-id (last all-s)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-broadcast-log
  (testing "MemStore commit-record! appends to broadcast-log"
    (let [s (store/mem-store {})
          record {:op :log-broadcast-record :station-id "t1" :value {:segment "evening-news"}}]
      (is (= 0 (count (store/broadcast-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/broadcast-log s))))
      (is (= record (first (store/broadcast-log s)))))))

(deftest mem-store-with-stations
  (testing "MemStore with-stations replaces the station directory"
    (let [s (store/mem-store {})
          new-stations {"t1" {:station-id "t1" :name "Alice's Broadcast Group"}}]
      (is (= 0 (count (store/all-stations s))))
      (store/with-stations s new-stations)
      (is (= 1 (count (store/all-stations s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo stations"
    (let [s (store/seed-db)]
      (is (> (count (store/all-stations s)) 0))
      (is (some? (store/station s "station-1")))
      (is (some? (store/station s "station-2")))
      (is (some? (store/station s "station-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for station-id"
    (let [demo (store/demo-data)
          stations (:stations demo)]
      (doseq [[k v] stations]
        (is (string? k) "keys must be strings")
        (is (string? (:station-id v)) "station-id must be string")
        (is (= k (:station-id v)) "key must match station-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
