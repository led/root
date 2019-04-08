(ns root.impl.core
  (:require [medley.core :as md]
            [reagent.core :as r]
            [cljs.spec.alpha :as s]
            [den1k.shortcuts :refer [shortcuts global-shortcuts]]))

;; register/remove views
;; db lookup / transact
;; view state: dom node, etc.

(s/def ::id integer?)
(s/def ::type keyword?)
(s/def ::markup (s/coll-of string?))

(s/def ::ref integer? #_(s/tuple #{:id} ::id))
(s/def ::refs (s/coll-of ::ref))
(s/def ::refs-map (s/map-of keyword? (s/or :ref ::ref :refs ::refs)))
(s/def ::content (s/or :ref ::ref :refs ::refs :refs-map ::refs-map))

(s/def ::attrs map?)

(s/def ::partial-entity (s/keys :opt-un [::id ::type ::content ::markup ::attrs]))
(s/def ::entity (s/keys :req-un [::id] :opt-un [::type ::content ::markup ::attrs]))

(defn conform! [spec x]
  (let [ret (s/conform spec x)]
    (if (= ret ::s/invalid)
      (do
        (js/console.error (s/explain-str spec x))
        (throw
         (ex-info "Value doesn't match spec"
                  {:value        x
                   :spec         spec
                   :explain-data (s/explain-data spec x)})))
      ret)))

(defn is? [x spec]
  (not= (s/conform spec x) ::s/invalid))

(defn make-id-gen [start]
  (let [current (atom start)]
    (fn []
      (swap! current inc))))

(defonce id-gen (make-id-gen 1000))

(defn ->ref [ent]
  (:id ent))

(defn ref+ent-tuple [ent]
  [(->ref ent) ent])

(def data
  [{:id 1 :type :container :content [10 2]}
   {:id      2 :type :todo-list :view :toggle-list :markup ["Shopping List"]
    :open?   true
    :content [6 3 4 5]}
   {:id 3 :type :todo-item :markup ["Buy Bananas \uD83C\uDF4C️"]}
   {:id 4 :type :todo-item :markup ["Buy strawberries"]}
   {:id 5 :type :todo-item :checked? true :markup ["Buy Cabbage"]}
   {:id 6 :type :button :markup ["New Todo"] :handlers {:on-click [:todo-item :add]}}
   {:id 10 :content [11 12]}
   {:id 11 :type :button :markup ["undo"] :handlers {:on-click [:global :undo]}}
   {:id 12 :type :button :markup ["redo"] :handlers {:on-click [:global :redo]}}])

(def state (r/atom (into {} (map ref+ent-tuple) data)))
(defn lookup [id] (get @state id))

(def entity-actions
  {:global
   {:undo [[:undo]]
    :redo [[:redo]]}
   :todo-item
   {:add            [[:add
                      [:<- :content]
                      {:type :todo-item :active? true :markup ["New Todo"]}]]
    :remove         [[:remove [:<- :content]]]
    :toggle-checked [[:toggle :checked?]]}})


(def history-log (atom {:idx nil :log []}))

(defn op-dispatch [[op _]] op)

(defmulti inverted-op op-dispatch)

(defmethod inverted-op :add
  [[_ id-or-path ent]]
  [:remove id-or-path ent])

(defmethod inverted-op :remove
  [[_ id-or-path ent]]
  [:add id-or-path ent])

(defmethod inverted-op :set
  [[_ {:as ent :keys [id]}]]
  [:set (lookup id)])

(defmethod inverted-op :toggle [tx] tx)

(defn log-txs [[[op] :as txs]]
  (case op
    (:undo :redo) nil
    (let [{:keys [log]} @history-log]
      (swap! history-log assoc
             :log (conj log (mapv inverted-op txs))
             :redo-log []))))

(declare transact)

(defn- shift-history [from-key to-key]
  (let [{from from-key to to-key} @history-log]
    (when-let [txs (peek from)]
      (swap! history-log assoc
             from-key (pop from)
             to-key (conj to (mapv inverted-op txs)))
      (transact txs {:history? false}))))

(defn undo []
  (shift-history :log :redo-log))

(defn redo []
  (shift-history :redo-log :log))

(comment
 (undo)
 (redo)
 )

(defn- ensure-vec [x]
  (cond
    (vector? x) x
    (sequential? x) (vec x)
    :else [x]))

(defmulti run-tx :op)

(defmethod run-tx :add
  [{:keys [path ent]}]
  (let [[ref ent :as ref+ent] (ref+ent-tuple ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in path
                            (fn [x]
                              (cond
                                (vector? x) (conj x ref)
                                :else ref)))
                 (conj ref+ent))))))

(defmethod run-tx :remove
  [{:keys [path ent]}]
  (let [ref (->ref ent)]
    (swap! state
           (fn [st]
             (-> st
                 (update-in path
                            (fn [x]
                              (cond
                                (is? x ::refs)
                                (vec (remove (fn [r] (= r ref)) x))
                                :else nil)))
                 (dissoc ref))))))

(defmethod run-tx :set
  [{:keys [path ent]}]
  (let [ref+ent (ref+ent-tuple (dissoc ent :actions :handlers))]
    (swap! state
           (fn [st]
             (-> st (conj ref+ent))))))

(s/def ::op-path
  (s/and #(or (keyword? %) (vector? %))
         (s/conformer
          (fn [x]
            (if (keyword? x)
              [x]
              (and (vector? x) (not-empty x)))))))

(s/def ::tx
  (s/cat :op keyword?
         :path (s/? ::op-path)
         :ent ::entity))

(s/def ::txs (s/coll-of ::tx))

(s/def ::partial-tx
  (s/cat :op keyword?
         :path (s/? ::op-path)
         :ent (s/? ::partial-entity)))

(s/def ::partial-txs (s/coll-of ::partial-tx))


(defmethod run-tx :toggle
  [{:keys [path ent]}]
  (let [ref (->ref ent)]
    ;; path can be nil
    (swap! state update-in (concat [ref] path) not)))

(defmethod run-tx :undo [_] (undo))
(defmethod run-tx :redo [_] (redo))

(defn transact
  ([txs]
   (transact txs {:history? true}))
  ;; could return ids for to trigger re-render based on id->views index
  ([txs {:keys [history?]}]
   (when history?
     (log-txs txs))
   (doseq [tx txs]
     (->> tx
          (conform! ::tx)
          run-tx))))

(defn __border [color]
  {:border (str "1px solid " (name color))})

(defn multi-dispatch
  [{:keys [dispatch-fn add-method-key remove-method-key dispatch-method-key default-dispatch-fn]}]
  {:pre [(ifn? dispatch-fn)
         add-method-key remove-method-key dispatch-method-key]}
  (let [table (atom {:default
                     (or default-dispatch-fn
                         (fn [x]
                           (throw
                            (ex-info
                             "No method defined for :default" {:arg x}))))})]
    {add-method-key      (fn add-view [dispatch-val f]
                           (swap! table assoc dispatch-val f))
     remove-method-key   (fn remove-view [dispatch-val]
                           (swap! table dissoc dispatch-val))
     dispatch-method-key (fn dispatch-view [x]
                           (let [dispatch-val (dispatch-fn x)
                                 t            @table
                                 f            (or (get t dispatch-val)
                                                  (get t :default))]
                             [f x]))}))

(defn resolve-content
  ([content] (resolve-content content identity))
  ([content f]
   (let [[type refs*] (conform! ::content content)]
     (case type
       :ref (with-meta (f refs*) ::entity)
       :refs (with-meta (mapv f refs*) ::entities)
       :refs-map (with-meta (md/map-vals #(resolve-content % f) content) ::entity-map)))))

(defn resolve-child-views
  ([ent] (resolve-child-views ent identity))
  ([{:as ent :keys [content]} f]
   (cond-> ent content (update :content resolve-content f))))


(s/def ::txs-path (s/coll-of keyword?))

(s/def ::partial-txs-or-txs-path
  (s/or :partial-txs ::partial-txs
        :path ::txs-path))

(defn wrap-actions-and-handlers [{:as orig-ent :keys [type actions handlers parent-id]}]
  (letfn [(resolve-txs [conformed txs-or-txs-path]
            (case (first conformed)
              :partial-txs txs-or-txs-path
              :path (get-in entity-actions txs-or-txs-path)))
          (form-tx [tx]
            (let [{:keys [op path ent]} (conform! ::partial-tx tx)]
              (cond-> [op]
                path (conj (mapv #(if (= :<- %)
                                    parent-id
                                    %) path))
                ent (conj (cond-> ent (nil? (:id ent)) (assoc :id (id-gen))))
                (not ent) (conj orig-ent))))
          (form-txs [txs]
            (mapv form-tx txs))
          (wrap [actions-map]
            (md/map-vals
             (fn [txs-or-path]
               (fn []
                 (let [txs-or-path-conformed
                       (conform! ::partial-txs-or-txs-path txs-or-path)]
                   (->> txs-or-path
                        (resolve-txs txs-or-path-conformed)
                        form-txs
                        transact))))
             actions-map))]
    (cond-> orig-ent
      true (assoc :actions (merge (wrap (get entity-actions type))
                                  (wrap actions)))
      actions (update :actions merge (wrap actions))
      handlers (assoc :handlers (wrap handlers)))))

(defn resolved-view
  ([{:as root :keys [root-id]}] (resolved-view root root-id nil))
  ([root id] (resolved-view root id nil))
  ([{:as root :keys [dispatch-view lookup]} id parent-id]
   (-> id
       lookup
       (cond-> parent-id (assoc :parent-id parent-id))
       wrap-actions-and-handlers
       (resolve-child-views #(resolved-view root % id))
       dispatch-view)))

(defn default-child-view [content]
  (case (meta content)
    ::entity content
    ::entities (into [:div {:style {:padding-left 10}}] content)
    ::entity-map (into [:div {:style {:padding-left 10}}]
                       (map
                        (fn [[k child-or-children]]
                          [:div (str (name k) ": ")
                           [default-child-view child-or-children]]))
                       content)))

(defn default-view [{:as ent :keys [id type view markup content]}]
  [:div
   {:style (merge (__border :tomato)
                  {:padding    10
                   :margin-top 5})}
   [:div "id: " id]
   [:div "Type: " (if type (name type) "[No type]")]
   (when view
     [:div "View: " (name view)])
   (cond
     markup (into [:div "Markup: "] markup)
     content [:div "Content: " [default-child-view content]])])

(defn ui-root [{:as opts :keys [ent->view-name]}]
  {:pre [ent->view-name lookup]}        ;; todo add transact as a required function
  (merge
   (multi-dispatch {:dispatch-fn         (:ent->view-name opts)
                    :add-method-key      :add-view
                    :remove-method-key   :remove-view
                    :dispatch-method-key :dispatch-view
                    :default-dispatch-fn default-view})
   opts))

(def root (ui-root {:lookup         lookup
                    :ent->view-name (fn [x] (or (:view x) (:type x)))}))

;; not great because this is an implementation detail instead
;; a spec of the behavior – also not using transactions
(global-shortcuts {"cmd+z"       #(undo)
                   "cmd+shift+z" #(redo)})

(let [{:keys [add-view]} root]

  (add-view
   :button
   (fn [{:keys [markup handlers]}]
     [:button handlers (first markup)]))

  (add-view
   :toggle-list
   (fn [{:as ent :keys [markup content open?]}]
     (cond-> [:div
              [:div.flex.items-center
               [:div
                {:style {:padding 5}}
                [:div
                 {:style    (merge
                             {:font-size   12
                              :line-height 1
                              :user-select :none
                              :cursor      :pointer}
                             (when open?
                               {:transform        "rotate(90deg)"
                                :transform-origin :center}))
                  :on-click #(transact [[:toggle :open? (lookup (:id ent))]])}
                 "▶"]]
               (first markup)]]
       open? (into content))))

  (add-view
   :todo-item
   (fn [{:as                             ent
         :keys                           [id parent-id markup checked? active? actions]
         {:keys [toggle-checked remove]} :actions}]
     [:div.flex.items-center.hide-child
      [:input {:type      :checkbox
               :checked   (boolean checked?)
               :on-change toggle-checked}]

      (if-not active?
        [:label {:style    {:padding "0 5px"}
                 :on-click #(transact [[:set (assoc ent :active? true)]]
                                      {:history? false})}
         (str (first markup) " " id)]
        [:input (merge
                 (shortcuts {"enter" #(-> % .-target .blur)})
                 {:default-value (first markup)
                  :auto-focus    true
                  :on-blur       #(let [v (-> % .-target .-value)]
                                    (transact
                                     [[:set (assoc ent :active? false
                                                       :markup [v])]]
                                     {:history? (not= v (first markup))}))})])
      [:div.flex.dim.light-silver.pointer.f7.child
       [:div
        {:on-click remove}
        "remove"]
       (into [:select {:on-change #(let [opt-kw (-> % .-target .-value keyword)]
                                     (transact [[:set (assoc ent :type opt-kw)]]))}]
             (map (fn [x]
                    (let [opt-kw (keyword x)]
                      [:option {:value x #_(= opt-kw type)} x])))
             ["todo-item" "toggle-list"])]])))


(defn test-root [id]
  (doto (resolved-view root id)
    #_js/console.log))

