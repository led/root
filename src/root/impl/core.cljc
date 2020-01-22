(ns root.impl.core
  (:require [medley.core :as md]
            [xframe.core.alpha :as xf]
            [root.impl.multi :as multi]
            [root.impl.resolver :as rr]
            [root.impl.entity :as ent]
            [root.impl.util :as u]
            [clojure.set :as set]
            [#?(:clj  clojure.spec.alpha
                :cljs cljs.spec.alpha) :as s]))

(defonce id-gen (u/make-id-gen 1000))
(defn add-id [ent] (assoc ent :id (id-gen)))

(def state xf/db)

(defonce history-log (atom {:idx nil :log []}))

(defn op-dispatch [[op _]] op)

(declare transact vec-plop vec-pluck)

(defmulti inverted-op op-dispatch)

(defmethod inverted-op :add
  [[_ id-or-path ent]]
  [:remove id-or-path ent])

(defmethod inverted-op :add-after
  [[_ id-or-path ent]]
  [:remove-after id-or-path ent])

(defmethod inverted-op :remove-after
  [[_ id-or-path ent]]
  [:add-after id-or-path ent])

(defmethod inverted-op :remove
  [[_ id-or-path ent]]
  [:add id-or-path ent])

(defmethod inverted-op :set
  [[_ {:as ent :keys [id]}]]
  [:set ((-> ent meta :root) :lookup id)])

(defmethod inverted-op :toggle [tx] tx)

(defn- inverted-txs [txs]
  (let [methods (methods inverted-op)
        ops     (into #{} (map op-dispatch) txs)
        txs     (if (= ops (set/intersection methods ops))
                  txs
                  (let [diff (set/difference ops methods)]
                    #?(:cljs (js/console.warn "Missing inverted-ops for:" diff))
                    (remove (fn [tx] (contains? diff (op-dispatch tx))) txs)))]
    (some->> txs (mapv inverted-op) not-empty)))

(defn log-txs [ctxs]
  (let [[[op] :as txs] (s/unform ::txs ctxs)]
    (case op
      (:undo :redo) nil
      (let [{:keys [log]} @history-log]
        (when-let [inv-txs (inverted-txs txs)]
          (swap! history-log assoc
                 :log (conj log inv-txs)
                 :redo-log []))))))

(declare transact)

(defn- shift-history [root from-key to-key]
  (let [{from from-key to to-key} @history-log]
    (when-let [txs (peek from)]
      (swap! history-log assoc
             from-key (pop from)
             to-key (conj to (mapv inverted-op txs)))
      (transact root txs {:history? false}))))

(defn undo [root]
  (shift-history root :log :redo-log))

(defn redo [root]
  (shift-history root :redo-log :log))

(comment
 (undo)
 (redo)
 )

(defmulti run-tx (fn [_root tx] (:op tx)))

(defmethod run-tx :add
  [{:as root :keys [ent->ref+ent]} {:as tx :keys [path ent]}]
  (let [[ref :as ref+ent] (ent->ref+ent ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in path
                            (fn [x]
                              (cond
                                (vector? x) (conj x ref)
                                :else ref)))
                 (conj ref+ent))))))

(defn vec-plop [seq idx item]
  (vec (concat (take idx seq) [item] (drop idx seq))))

(defn vec-pluck [seq & idxs]
  (if-not idxs
    seq
    (let [idxs (set idxs)]
      (into (empty seq)
            (comp
             (map-indexed vector)
             (keep (fn [[idx i]]
                     (when (not (contains? idxs idx)) i))))
            seq))))

(defmethod run-tx :add-after
  [{:as root :keys [ent->ref+ent]} {:keys [path ent]}]
  (let [[ref :as ref+ent] (ent->ref+ent ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in (pop path)
                            (fn [x]
                              (vec-plop x (-> path peek inc) ref)))
                 (conj ref+ent))))))

(defmethod run-tx :remove
  [{:as root :keys [ent->ref]} {:keys [path ent]}]
  (let [ref      (ent->ref ent)
        ent-path (:path ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in (if ent-path (pop ent-path) path)
                            (fn [x]
                              (cond
                                (s/valid? ::ent/refs x)
                                (into [] (remove #(= % ref)) x)
                                :else nil)))
                 (dissoc ref))))))

(defmethod run-tx :remove-after
  [{:as root :keys [ent->ref]} {:keys [path ent]}]
  (let [ref (ent->ref ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in (pop path) vec-pluck (-> path peek inc))
                 (dissoc ref))))))

(defmethod run-tx :set
  [{:as root :keys [ent->ref+ent]} {:keys [path ent]}]
  (let [ref+ent (ent->ref+ent ent)]
    (swap! state
           (fn [st]
             (-> st (conj ref+ent))))))

(s/def ::op-path
  (s/and #(or (keyword? %) (vector? %))
         (s/conformer
          (fn [x]
            (if (keyword? x)
              [x]
              (and (vector? x) (not-empty x))))
          vec)))

(s/def ::tx
  (s/cat :op keyword?
         :path (s/? ::op-path)
         :ent (s/? ::ent/entity)))

(s/def ::txs (s/coll-of ::tx))

(defmethod run-tx :toggle
  [{:as root :keys [ent->ref]} {:keys [path ent]}]
  (let [ref (ent->ref ent)]
    ;; path can be nil
    (swap! state update-in (concat [ref] path) not)))

(defmethod run-tx :undo [root _] (undo root))
(defmethod run-tx :redo [root _] (redo root))

(defn transact
  ([root txs]
   (transact root txs {:history? true}))
  ([root txs {:keys [history?]}]
   #?(:cljs (js/console.log :txs txs))
   (let [conformed-txs (u/conform! ::txs (filter identity txs))]
     (when history?
       (log-txs conformed-txs))
     (doseq [ctx conformed-txs
             :let [ctx (update ctx :ent dissoc :actions :handlers :views)]]
       (run-tx root ctx)))
   (xf/notify-listeners!)))

(defn __border [color]
  {:border (str "1px solid " (name color))})

(defn default-child-view [views]
  (let [padded-view [:div {:style {:padding-left 10}}]]
    (case (::rr/type (meta views))
      :entity (conj padded-view views)
      :entities (conj padded-view views)
      :entity-map (into padded-view
                        (map
                         (fn [[k child-or-children]]
                           [:div (str (name k) ": ")
                            [default-child-view child-or-children]]))
                        views))))

(defn default-view* [{:as ent :keys [id type view markup views]}]
  [:div
   {:style (merge (__border :tomato)
                  {:padding    10
                   :margin-top 5})}
   [:div "id: " id]
   [:div "Type: " (if type (name type) "[No type]")]
   (when view
     [:div "View: " (name view)])
   (when markup (into [:div "Markup: "] markup))
   (when views [:div "Content: " [default-child-view views]])])

(defrecord UIRoot
  [add-view remove-method dispatch-view method-table]
  #?@(:clj
      [clojure.lang.IFn
       (invoke
         [this a]
         (this a nil))
       (invoke
         [this a b]
         (this a b nil))
       (invoke
         [{:as root :keys [transact lookup]} a b c]
         (case a
           :transact (transact root b c)
           :lookup (with-meta (lookup b) {:root root})
           :view (add-view b c)
           (dispatch-view a)))]
      :cljs
      [IFn
       (-invoke
        [this a]
        (this a nil))
       (-invoke
        [this a b]
        (this a b nil))
       (-invoke
        [{:as root :keys [transact lookup]} a b c]
        (case a
          :transact (transact root b c)
          :lookup (with-meta (lookup b) {:root root})
          :view (add-view b c)
          (dispatch-view a)))]))

(defn- view-multi-dispatch [opts]
  (-> opts
      multi/multi-dispatch
      (update :add-method
              (fn [add-method]
                (fn with-name [dispatch-val view]
                  #?(:cljs
                     (set! (.-displayName view)
                           (str "root-view__" (name dispatch-val))))
                  (add-method dispatch-val view))))
      (set/rename-keys {:add-method    :add-view
                        :remove-method :remove-view
                        :method-table  :view-table
                        :dispatch      :dispatch-view})))

;; Root config specs

(s/def ::ent->view-name ifn?)
(s/def ::lookup ifn?)
(s/def ::lookup-sub ifn?)
(s/def ::ent->ref ifn?)
(s/def ::transact ifn?)
(s/def ::add-id ifn?)

(s/def ::ui-root-static
  (s/keys :req-un [::ent->view-name ::lookup]))

(s/def ::ui-root-reactive
  (s/and ::ui-root-static (s/keys :req-un [::lookup-sub])))

(s/def ::ui-root-dynamic
  (s/and ::ui-root-reactive (s/keys :req-un [::transact ::add-id ::ent->ref])))

(s/def ::ui-root
  (s/or :dynamic ::ui-root-dynamic
        :reactive ::ui-root-reactive
        :static ::ui-root-static))

(defn opts-warn [root-opts]
  (let [[root-type _] (u/conform! ::ui-root root-opts)]
    #?(:cljs
       (when (contains? #{:static :reactive} root-type)
         (js/console.warn
          "Root Warning: static use only. Missing one or more required"
          "functions: lookup-sub, transact, ent->ref, add-id.")))))

(defn ui-root
  [{:as   opts
    :keys [ent->view-name default-view invoke-fn lookup lookup-sub ent->ref]
    :or   {default-view default-view*}}]
  (opts-warn opts)
  (map->UIRoot
   (merge
    (view-multi-dispatch
     {:dispatch-fn         ent->view-name
      :default-dispatch-fn default-view
      :invoke-fn           invoke-fn})
    {:ent->ref+ent (fn ent->ref+ent [ent] [(ent->ref ent) ent])}
    (when (nil? lookup-sub)
      {:lookup-sub lookup})
    opts)))
