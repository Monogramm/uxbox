;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.interactions
  (:require [promesa.core :as p]
            [uxbox.util.dom :as dom]
            [uxbox.util.rstore :as rs]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.main.state :as st]
            [uxbox.view.data.viewer :as dv]
            [vendor.snapsvg])
  ;; Documentation about available events:
  ;; https://google.github.io/closure-library/api/goog.events.EventType.html
  (:import goog.events.EventType))

(defn- translate-trigger
  "Translates the interaction trigger name (keyword) into
  approriate dom event name (keyword)."
  [trigger]
  {:pre [(keyword? trigger)]}
  (case trigger
    :click EventType.CLICK
    :doubleclick EventType.DBLCLICK
    :rightclick EventType.CONTEXTMENU
    :mousein EventType.MOUSEENTER
    :mouseout EventType.MOUSELEAVE
    :hover ::hover
    (throw (ex-info "not supported at this moment" {:trigger trigger}))))

(defn- translate-ease
  "Translates the uxbox ease settings to one
  that are compatible with anime.js library."
  [ease]
  {:pre [(keyword? ease)]}
  (case ease
    :linear js/mina.linear
    :easein js/mina.easin
    :easeout js/mina.easout
    :easeinout js/mina.easeinout
    (throw (ex-info "invalid ease value" {:ease ease}))))

(defn- animate
  [& opts]
  (js/anime (clj->js (apply hash-map opts))))

(defn- animate*
  [dom {:keys [delay duration easing] :as opts}]
  (let [props (dissoc opts :delay :duration :easing)
        snap (js/Snap. dom)]
    (p/schedule delay #(.animate snap (clj->js props) duration easing))))

;; --- Interactions to Animation Compilation

(defn- run-moveby-interaction
  [{:keys [element moveby-x moveby-y easing delay duration direction]}]
  (let [dom (dom/get-element (str "shape-" element))]
    (if (= direction :reverse)
      (animate* dom {:transform (str "translate(" (- moveby-x)" " (- moveby-y) ")")
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration})
      (animate* dom {:transform (str "translate(" moveby-x " " moveby-y ")")
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration}))))

(declare run-hide-interaction)

(defn- run-show-interaction
  [{:keys [element easing delay duration
           animation direction] :as itx}]
  (let [dom (dom/get-element (str "shape-" element))]
    (if (= direction :reverse)
      (run-hide-interaction (dissoc itx :direction))
      (animate* dom {:fillOpacity "1"
                     :strokeOpacity "1"
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration}))))

(defn- run-hide-interaction
  [{:keys [element easing delay duration
           animation direction] :as itx}]
  (let [dom (dom/get-element (str "shape-" element))]
    (if (= direction :reverse)
      (run-show-interaction (dissoc itx :direction))
      (animate* dom {:fillOpacity "0"
                     :strokeOpacity "0"
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration}))))

(defn- run-opacity-interaction
  [{:keys [element opacity easing delay
           duration animation direction]}]
  (let [shape (get-in @st/state [:shapes-by-id element])
        dom (dom/get-element (str "shape-" element))]
    (if (= direction :reverse)
      (animate* dom {:fillOpacity (:fill-opacity shape "1")
                     :strokeOpacity (:stroke-opacity shape "1")
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration})
      (animate* dom {:fillOpacity opacity
                     :strokeOpacity opacity
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration}))))

;; (defn- run-size-interaction
;;   [{:keys [element resize-width resize-height
;;            easing delay duration] :as opts}]
;;   (let [shape (get-in @st/state [:shapes-by-id element])
;;         tf (geom/transformation-matrix
;;             (geom/resize shape (gpt/point resize-width resize-height)))]
;;     (animate :targets [(str "#shape-" element)]
;;              :transform (str tf)
;;              :easing (translate-ease easing)
;;              :delay delay
;;              :duration duration
;;              :loop false)))

(defn- run-size-interaction-icon
  [{:keys [x1 y1 view-box rotation] :as shape}
   {:keys [resize-width resize-height easing
           element delay duration direction] :as opts}]
  (if (= direction :reverse)
    (let [end (geom/transformation-matrix shape)]
      (animate :targets [(str "#shape-" element)]
               :transform (str end)
               :easing (translate-ease easing)
               :delay delay
               :duration duration
               :loop false))
    (let [orig-width (nth view-box 2)
          orig-height (nth view-box 3)
          scale-x (/ resize-width orig-width)
          scale-y (/ resize-height orig-height)
          center-x (- resize-width (/ resize-width 2))
          center-y (- resize-height (/ resize-height 2))
          end (-> (gmt/matrix)
                  (gmt/translate x1 y1)
                  (gmt/translate center-x center-y)
                  (gmt/rotate rotation)
                  (gmt/translate (- center-x) (- center-y))
                  (gmt/scale scale-x scale-y))
          dom (dom/get-element (str "shape-" element))]
      (animate* dom {:transform (str end)
                     :easing (translate-ease easing)
                     :delay delay
                     :duration duration}))))

(defn- run-size-interaction-rect
  [{:keys [x1 y1 rotation] :as shape}
   {:keys [resize-width resize-height easing
           element delay duration direction] :as opts}]
  (if (= direction :reverse)
    (let [end (geom/transformation-matrix shape)]
      (animate :targets [(str "#shape-" element)]
               :transform (str end)
               :easing (translate-ease easing)
               :delay delay
               :duration duration
               :loop false))
    (let [dom (dom/get-element (str "shape-" element))]
      (animate* dom {:easing (translate-ease easing)
                     :delay delay
                     :duration duration
                     :width resize-width
                     :height resize-height}))))

(defn- run-size-interaction
  [{:keys [element] :as opts}]
  (let [shape (get-in @st/state [:shapes-by-id element])]
    (case (:type shape)
      :icon (run-size-interaction-icon shape opts)
      :rect (run-size-interaction-rect shape opts))))

(defn- run-gotourl-interaction
  [{:keys [url]}]
  (set! (.-href js/location) url))

(defn- run-gotopage-interaction
  [{:keys [page]}]
  (rs/emit! (dv/select-page page)))

(defn- run-color-interaction
  [{:keys [element fill-color stroke-color direction easing delay duration]}]
  (let [shape (get-in @st/state [:shapes-by-id element])
        dom (dom/get-element (str "shape-" element))]
    (if (= direction :reverse)
      (animate* dom {:easing (translate-ease easing)
                     :delay delay
                     :duration duration
                     :fill (:fill shape "#000000")
                     :stroke (:stroke shape "#000000")})
      (animate* dom {:easing (translate-ease easing)
                     :delay delay
                     :duration duration
                     :fill fill-color
                     :stroke stroke-color}))))

(defn- run-rotate-interaction
  [{:keys [element rotation direction easing delay duration] :as opts}]
  (let [shape (get-in @st/state [:shapes-by-id element])
        dom (dom/get-element (str "shape-" element))
        mtx1 (geom/transformation-matrix (update shape :rotation + rotation))
        mtx2 (geom/transformation-matrix shape)]
    (if (= direction :reverse)
      (animate* dom {:easing (translate-ease easing)
                     :delay delay
                     :duration duration
                     :transform (str mtx2)})
      (animate* dom {:easing (translate-ease easing)
                     :delay delay
                     :duration duration
                     :transform (str mtx1)}))))

(defn- run-interaction
  "Given an interaction data structure return
  a precompiled animation."
  [{:keys [action] :as itx}]
  (case action
    :moveby (run-moveby-interaction itx)
    :show (run-show-interaction itx)
    :hide (run-hide-interaction itx)
    :size (run-size-interaction itx)
    :opacity (run-opacity-interaction itx)
    :color (run-color-interaction itx)
    :rotate (run-rotate-interaction itx)
    :gotourl (run-gotourl-interaction itx)
    :gotopage (run-gotopage-interaction itx)
    (throw (ex-info "undefined interaction" {:action action}))))

;; --- Main Api

(defn- build-hover-evt
  "A special case for hover event."
  [itx]
  (letfn [(on-mouse-enter [event]
            (dom/prevent-default event)
            (run-interaction itx))
          (on-mouse-leave [event]
            (dom/prevent-default event)
            (run-interaction (assoc itx :direction :reverse)))]
    [[EventType.MOUSEENTER on-mouse-enter]
     [EventType.MOUSELEAVE on-mouse-leave]]))

(defn- build-generic-evt
  "A reducer function that compiles interaction data structures
  into apropriate event handler attributes."
  [evt itx]
  (letfn [(on-event [event]
            (dom/prevent-default event)
            (run-interaction itx))]
    [[evt on-event]]))

(defn build-events
  "Compile a sequence of interactions into a hash-map of event-handlers."
  [shape]
  (reduce (fn [acc itx]
            (let [evt (translate-trigger (:trigger itx))]
              (if (= evt ::hover)
                (into acc (build-hover-evt itx))
                (into acc (build-generic-evt evt itx)))))
          []
          (vals (:interactions shape))))