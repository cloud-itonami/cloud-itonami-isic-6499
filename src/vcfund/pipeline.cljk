(ns vcfund.pipeline
  "Pure deal-pipeline funnel model -- sourcing through Investment Committee
  review, BEFORE any capital moves. This closes the second-biggest R0
  coverage gap: the actor tracked exactly one deal moving linearly through
  DD -> commit -> exit, with no notion of a multi-deal sourcing funnel
  (screening, pitch, term sheet, DD, IC review) or of a deal being PASSED
  on before capital ever moves.

  `:committed`/`:exited` are deliberately EXCLUDED from this funnel and
  from `valid-transition?`'s reachable set -- those two states are set only
  by the actual capital-moving ops (`:investment/commit`/
  `:exit/distribute`), never by `:deal/advance-stage` directly. This keeps
  a deal's pipeline stage and its capital status from ever diverging: the
  only way into `:committed` is actually committing capital.

  Pure data + pure functions -- no I/O, no governance decision. The
  `stage-transition-violations` / `stage-insufficient-violations` HARD
  checks in `vcfund.governor` are what actually enforce this against a
  live proposal.")

(def funnel-stages
  "Ordered pre-commitment evaluation funnel. Movement between these must be
  forward-only (see `valid-transition?`) -- real deals sometimes skip a
  stage (e.g. a well-known founder skips :screening), so forward-any-step
  is allowed, not strictly +1."
  [:sourced :screening :pitched :term-sheet :dd :ic-review])

(def capital-stages
  "Stages reached ONLY by the actual capital-moving ops, never by
  `:deal/advance-stage`."
  #{:committed :exited})

(def passable-terminal :passed)

(defn stage-index
  "Index of `stage` within `funnel-stages`, or nil if it isn't a funnel
  stage (e.g. `:passed`, `:committed`, `:exited`, or an unrecognized value)."
  [stage]
  (first (keep-indexed (fn [i s] (when (= s stage) i)) funnel-stages)))

(defn valid-transition?
  "Is `from` -> `to` a legal pipeline move?

  - `to` = `:passed` -- legal from any funnel stage (a firm can always pass
    before capital moves), illegal once `:committed`/`:exited`/already
    `:passed`.
  - `to` in `capital-stages` -- always illegal via this function; those
    states are set only by the real capital-moving ops.
  - otherwise -- both `from` and `to` must be funnel stages, and `to` must
    be strictly later than `from` (forward-only, skips allowed)."
  [from to]
  (cond
    (= to passable-terminal)
    (some? (stage-index from))

    (contains? capital-stages to)
    false

    :else
    (let [from-idx (stage-index from)
          to-idx (stage-index to)]
      (and (some? from-idx) (some? to-idx) (> to-idx from-idx)))))

(defn at-least?
  "Has `stage` reached at least `floor` in the funnel? Used to require a
  deal actually passed through Investment Committee review
  (`:ic-review`), not merely that DD data happens to be on file."
  [stage floor]
  (let [i (stage-index stage)
        f (stage-index floor)]
    (and (some? i) (some? f) (>= i f))))
