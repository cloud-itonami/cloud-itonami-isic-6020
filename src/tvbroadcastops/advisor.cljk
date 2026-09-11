(ns tvbroadcastops.advisor
  "BroadcastOpsAdvisor -- the *contained intelligence node* for the
  ISIC-6020 television programming and broadcasting operations-
  coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: broadcast-record logging (program-schedule/segment/
  on-air-log data), programming-block scheduling, content-concern
  flagging (FCC-compliance/on-air-incident/emergency-alert), and
  transmitter/studio-equipment maintenance coordination. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `tvbroadcastops.governor` before anything touches the SSoT.

  This advisor NEVER finalizes an on-air-content decision and NEVER
  finalizes an emergency-alert-broadcast decision -- those are
  permanently out of scope for this actor, not merely un-implemented.
  `tvbroadcastops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode
  (a compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :station-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-broadcast-record
  "Draft a program-schedule/segment/on-air-log data-logging entry. Pure
  metadata logging (segment, timestamp, program title) -- never an
  on-air-content decision."
  [_db {:keys [station-id patch]}]
  {:op         :log-broadcast-record
   :station-id station-id
   :summary    (str station-id " の番組表・セグメント・オンエアログ記録を記録: " (pr-str (keys patch)))
   :rationale  "番組表・セグメント・オンエアログのメタデータ記録のみ。放送内容そのものの決定とは無関係。"
   :cites      [station-id]
   :effect     :propose
   :value      (merge {:station-id station-id} patch)
   :confidence 0.93})

(defn- propose-broadcast-operation
  "Draft a programming-block scheduling proposal (an internal ops
  calendar entry, never a binding on-air-content decision)."
  [_db {:keys [station-id patch]}]
  {:op         :schedule-broadcast-operation
   :station-id station-id
   :summary    (str station-id " の番組編成ブロック調整を提案: " (pr-str (keys patch)))
   :rationale  "番組編成ブロックの社内調整提案のみ。オンエア内容そのものを決定するものではない。"
   :cites      [station-id]
   :effect     :propose
   :value      (merge {:station-id station-id} patch)
   :confidence 0.88})

(defn- propose-content-concern
  "Surface an FCC-compliance concern, on-air-incident, or
  emergency-alert concern for HUMAN triage. This op ALWAYS escalates in
  `tvbroadcastops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [station-id patch]}]
  {:op         :flag-content-concern
   :station-id station-id
   :summary    (str station-id " のコンテンツ懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "FCC準拠上の懸念・オンエア事故・緊急警報に関する観察事実の報告。可否判断は常に人間が行う。"
   :cites      [station-id]
   :effect     :propose
   :value      (merge {:station-id station-id} patch)
   :confidence (or (:confidence patch) 0.85)})

(defn- propose-equipment-maintenance
  "Draft a transmitter/studio-equipment maintenance coordination
  proposal (technical/logistics coordination only, never a content or
  emergency-alert decision)."
  [_db {:keys [station-id patch]}]
  {:op         :coordinate-equipment-maintenance
   :station-id station-id
   :summary    (str station-id " の送信機・スタジオ設備メンテナンス調整を提案: " (pr-str (keys patch)))
   :rationale  "送信機・スタジオ設備の技術的メンテナンス調整のみ。放送内容や緊急警報の判断とは無関係。"
   :cites      [station-id]
   :effect     :propose
   :value      (merge {:station-id station-id} patch)
   :confidence 0.90})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-broadcast-record (propose-broadcast-record _db request)
                   :schedule-broadcast-operation (propose-broadcast-operation _db request)
                   :flag-content-concern (propose-content-concern _db request)
                   :coordinate-equipment-maintenance (propose-equipment-maintenance _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually decided to finalize the on-air content decision and authorize the emergency alert broadcast")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :station-id (:station-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
