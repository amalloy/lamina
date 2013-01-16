;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.trace.router.core
  (:use
    [potemkin :only (defprotocol+)])
  (:require
    [lamina.trace.context])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

;;;

(defonce ^ConcurrentHashMap operators (ConcurrentHashMap.))

(defn operator [name]
  (when name
    (.get operators name)))

;;;

(defn unwrap-key-vals [keys-val-seq]
  (->> keys-val-seq
    (mapcat
      (fn [[k v]]
        (if-not (coll? k)
          [[k v]]
          (map vector k (repeat v)))))
    (apply concat)
    (apply hash-map)))

(defprotocol+ TraceOperator
  (periodic? [_])
  (distribute? [_])
  (pre-aggregate? [_])
  (transform [_ desc ch])
  (pre-aggregate [_ desc ch])
  (intra-aggregate [_ desc ch])
  (aggregate [_ desc ch]))

(defmacro def-trace-operator [name & {:as args}]
  (let [{:keys [transform
                pre-aggregate
                intra-aggregate
                aggregate
                periodic?
                distribute?]} (unwrap-key-vals args)
        ns-str (str (ns-name *ns*))]
    `(let [transform# ~transform
           pre-aggregate# ~pre-aggregate
           intra-aggregate# ~intra-aggregate
           aggregate# ~aggregate
           periodic# ~(boolean periodic?)
           distribute# ~(boolean distribute?)
           op# (reify
                 clojure.lang.Named
                 (getName [_] ~(str name))
                 (getNamespace [_] ~ns-str)
                 
                 TraceOperator

                 (periodic? [_]
                   periodic#)
                 (distribute? [_]
                   distribute#)
                 (pre-aggregate? [_]
                   (boolean pre-aggregate#))
                 
                 (transform [_ desc# ch#]
                   (transform# desc# ch#))
                 (pre-aggregate [_ desc# ch#]
                   (if pre-aggregate#
                     (pre-aggregate# desc# ch#)
                     (transform# desc# ch#)))
                 (intra-aggregate [_ desc# ch#]
                   (if intra-aggregate#
                     (intra-aggregate# desc# ch#)
                     ch#))
                 (aggregate [_ desc# ch#]
                   (if aggregate#
                     (aggregate# desc# ch#)
                     (transform# desc# ch#))))]
       
       (when-let [existing-operator# (.putIfAbsent operators ~(str name) op#)]
         (if (= ~ns-str (namespace existing-operator#))
           (.put operators ~(str name) op#)
           (throw (IllegalArgumentException. (str "An operator for '" ~(str name) "' already exists in " (namespace existing-operator#) ".")))))

       op#)))

;;;

(defn group-by? [{:strs [name] :as op}]
  (when op
    (= "group-by" name)))

(defn operator-seq [ops]
  (mapcat
    (fn [{:strs [operators aggregate pre-aggregate] :as op}]
      (concat
        (when aggregate
          [aggregate])
        (if (group-by? op)
          (cons op (operator-seq operators))
          [op])
        (when pre-aggregate
          [pre-aggregate])))
    ops))

(defn last-operation [ops]
  (let [op (last ops)]
    (if (and (group-by? op) (seq (op "operators")))
     (update-in op ["operators"] #(vector (last-operation %)))
     op)))

(defn minify-maps
  [s]
  s
  #_(map
    #(->> %
       (remove (fn [[k v]] (empty? v)))
       (map (fn [[k v]] [k (if (map? v) (first (minify-maps [v])) v)]))
       (into {}))
    s))

(defn distributable-chain
  "Operators which can be performed at the leaves of the topology."
  [ops]
  (minify-maps
    (loop [acc [], ops ops]
      (if (empty? ops)
        acc
        (let [{:strs [operators name] :as op} (first ops)]
          (if (group-by? op)
            
            ;; traverse the group-by, see if it has to terminate mid-stream
            (let [operators* (distributable-chain operators)]
              (if (= operators operators*) 
                (recur (conj acc op) (rest ops))
                (conj acc (assoc op "operators" operators*))))
            
            (if (or
                  ;; we don't know what this is, maybe the endpoint does
                  (not (operator name))
                  (distribute? (operator name)))
              (recur (conj acc op) (rest ops))
              (concat
                acc
                (when (pre-aggregate? (operator name))
                  [{"pre-aggregate" op}])))))))))

(defn non-distributable-chain
  "Operators which must be performed at the root of the topology."
  [ops]
  (minify-maps
    (loop [ops ops]
      (when-not (empty? ops)
        (let [{:strs [operators name] :as op} (first ops)]
          (if (group-by? op)
              
            ;; traverse the group-by, see if it has to terminate mid-stream
            (let [operators* (non-distributable-chain operators)]
              (if (= operators operators*)
                (recur (rest ops))
                (list*
                  {"aggregate" (assoc op "operators" operators*)}
                  (rest ops))))
              
            (if (or
                  (not (operator name))
                  (distribute? (operator name)))
              (recur (rest ops))
              (concat
                (when (operator name)
                  [{"aggregate" op}])
                (rest ops)))))))))

(defn periodic-chain?
  "Returns true if operators emit traces periodically, rather than synchronously."
  [ops]
  (->> ops
    operator-seq
    (map #(get % "name"))
    (remove nil?)
    (map operator)
    (some periodic?)
    boolean))

(defn transform-trace-stream
  ([desc ch]
     (transform-trace-stream transform desc ch))
  ([f {:strs [name operators] :as desc} ch]
     (let [ch (if-let [agg-desc (desc "aggregate")]
                (transform-trace-stream aggregate agg-desc ch)
                ch)
           ch (cond
                name
                (f (operator name) desc ch)

                (not (empty? operators))
                (reduce #(transform-trace-stream %2 %1) ch operators)

                :else
                ch)
           ch (if-let [pre-agg-desc (desc "pre-aggregate")]
                (transform-trace-stream pre-aggregate pre-agg-desc ch)
                ch)]
       ch)))
