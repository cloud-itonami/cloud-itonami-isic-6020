(ns tvbroadcastops.governor
  "BroadcastLicenseGovernor -- the independent compliance layer that
  earns the BroadcastOpsAdvisor the right to commit. The advisor has
  no notion of whether a station/license record is actually registered
  and verified, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has
  silently drifted into a permanently out-of-scope decision area, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor's scope is deliberately narrow -- TV BROADCASTING
  OPERATIONS COORDINATION ONLY (broadcast-record logging,
  programming-block scheduling proposals, content-concern flagging,
  transmitter/studio-equipment maintenance coordination). It NEVER
  performs or authorizes:
    - finalizing an on-air-content decision (what actually airs, when,
      in what form)
    - finalizing an emergency-alert-broadcast decision (whether/when
      to trigger an Emergency Alert System transmission)

  Both of those are ALWAYS either a hard permanent block (this
  governor) or an always-escalate op (`:flag-content-concern`) --
  NEVER an auto-commit-eligible op in any phase. This actor coordinates
  the back office around those decisions; it never makes them.

  Two HARD checks, ALL permanent, un-overridable by any human approval:

    1. Station unverified        -- the target station/license record
                                     must exist AND be independently
                                     confirmed `:registered?`/
                                     `:verified?` in the store before
                                     ANY proposal for it may commit or
                                     even escalate. Never trusts a
                                     proposal's own claim about the
                                     station -- re-derived from the
                                     station's own store record, the
                                     same 'ground truth, not
                                     self-report' discipline every
                                     sibling actor's governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST
                                     be `:propose`. Any other effect
                                     value is, by construction, a
                                     claim to directly actuate/commit
                                     outside governance -- HARD block,
                                     not merely low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                     whose op, rationale, summary,
                                     citations or draft value touches
                                     the ACT of finalizing an on-air-
                                     content decision, or the ACT of
                                     finalizing an emergency-alert-
                                     broadcast decision, is a HARD,
                                     PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated
                                     UNCONDITIONALLY on every
                                     proposal. An op outside the
                                     closed four-op allowlist is the
                                     SAME failure mode (an advisor
                                     proposing something it was never
                                     authorized to propose) and is
                                     folded into this same check.

  IMPORTANT (self-trip discipline): `scope-excluded-terms` below are
  phrased as the FINALIZATION/EXECUTION ACTION ('finalize the on-air
  content decision', 'authorize the emergency alert broadcast'), never
  as a bare noun ('content', 'broadcast', 'emergency alert', 'on-air').
  This actor's own legitimate happy-path proposals -- especially
  `:flag-content-concern`, whose entire purpose is to talk ABOUT
  content/emergency-alert concerns -- routinely use those bare nouns in
  their default rationale text. A bare-noun term list would self-trip
  the actor on its own default mock-advisor proposals;
  `governor-test` and `governor-contract-test` both assert this never
  happens.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-content-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `tvbroadcastops.phase` independently agrees: `:flag-content-concern`
  is never a member of any phase's `:auto` set either -- two layers,
  not one."
  (:require [clojure.string :as str]
            [tvbroadcastops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-broadcast-record :schedule-broadcast-operation
    :flag-content-concern :coordinate-equipment-maintenance})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-content-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as attempting to
  directly FINALIZE an on-air-content decision or an
  emergency-alert-broadcast decision -- this actor's two permanently
  out-of-scope decision areas. Phrased as the finalization/execution
  ACTION, never as a bare noun, so this list never matches inside this
  actor's own legitimate proposals (which routinely discuss content/
  broadcast/emergency-alert as topics without ever finalizing them).
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["finalize the on-air content decision" "finalize on-air content decision"
   "finalize the broadcast content decision" "finalize broadcast content decision"
   "authorize the on-air content decision" "authorize on-air content decision"
   "approve the on-air content decision" "approve on-air content decision"
   "confirm the on-air content decision" "lock the on-air content decision"
   "decide what airs" "decide what to air"
   "オンエア内容決定を確定" "放送内容の決定を確定" "番組内容の決定を確定"
   "放送内容を確定する決定" "オンエア内容を許諾する決定"
   "finalize the emergency alert broadcast" "finalize emergency alert broadcast"
   "authorize the emergency alert broadcast" "authorize emergency alert broadcast"
   "authorize the emergency alert transmission" "authorize emergency alert transmission"
   "trigger the emergency alert broadcast" "trigger emergency alert broadcast"
   "activate the emergency alert system broadcast" "activate emergency alert system broadcast"
   "issue the emergency alert broadcast" "issue emergency alert broadcast"
   "confirm the emergency alert broadcast decision" "execute the emergency alert broadcast"
   "緊急警報放送を確定" "緊急警報放送を発出する決定" "緊急警報放送を実行する決定"
   "EAS放送を確定" "緊急警報システムの発報を確定"])

;; ----------------------------- checks -----------------------------

(defn- station-unverified-violations
  "The target station/license record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:station-id` claim without a store lookup."
  [{:keys [station-id]} st]
  (let [r (store/station st station-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :station-unverified
        :detail (str station-id " は未登録または未検証の放送局/免許 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing an on-air-content decision or
  finalizing an emergency-alert-broadcast decision, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob (str/lower-case %)) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "オンエア内容決定/緊急警報放送の確定判断に踏み込む提案は永久に禁止"}])))

(defn check
  "Censors a BroadcastOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [station-id (or (:station-id proposal) (:station-id request))
        hard (into []
                   (concat (station-unverified-violations {:station-id station-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :station-id (:station-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
