(ns membrane.webgltest
  (:require-macros [membrane.webgl-macros
                    :refer [add-image!]])
  (:require
   [membrane.component :refer [defui]]
   membrane.audio
   [membrane.webgl :as webgl]
   [com.rpl.specter :as spec
    :refer [ATOM ALL FIRST LAST MAP-VALS META]]
   membrane.basic-components
   [membrane.ui :as ui
    :refer [vertical-layout
            label
            width
            height
            translate
            origin-x
            origin-y]]
   [membrane.example.todo :as todo]))

(def canvas (.getElementById js/document "canvas"))

(defui test-ui [& {:keys [a b]}]
  (let [l (label a)
        [w h] (ui/text-bounds ui/default-font a)
        border (ui/rectangle w h)]
    [(translate 20 20
                (membrane.basic-components/textarea :text a))]
    #_(translate 20 20
                 )
    #_(ui/with-color [255 0 0]
      (ui/rectangle 100 100))
    #_(ui/rectangle 10 10)
    #_(vertical-layout
     (label "hi there")
     (ui/on
      :mouse-move
      (fn [[x y]]
        [[:set $b (str [x y]
                       (ui/index-for-position ui/default-font a x y))]]
        )
      (label a))
     (label b))

    
    )
  )

(def enlarge-bottom-button (.getElementById js/document "enlarge-canvas-bottom"))

(defonce enlargeBottomEventHandler
  (doto enlarge-bottom-button
    (.addEventListener "mousedown"
                       (fn []
                         (let [style (.-style canvas)
                               ch (.-clientHeight canvas)]
                           (set! (.-height style) (str (int (+ ch 200)) "px")))))))

(def enlarge-right-button (.getElementById js/document "enlarge-canvas-right"))

(defonce enlargeRightEventHandler
  (doto enlarge-right-button
    (.addEventListener "mousedown"
                       (fn []
                         (let [style (.-style canvas)
                               cw (.-clientWidth canvas)]
                           (set! (.-width style) (str (int (+ cw 200)) "px")))
                         ))))

;; (defonce start-app (membrane.component/run-ui #'test-ui (atom {:a "there"})))
(defonce start-todo-app (membrane.component/run-ui #'todo/todo-app todo/todo-state nil {:canvas canvas}))

#_(defonce start-app (membrane.webgl/run #(ui/label "Hello World") {:canvas canvas}))
#_(let [canvas (webgl/create-canvas 300 400)]
  (.appendChild (.-body js/document) canvas)
  (defonce start-app (membrane.webgl/run #(ui/label "Hello World") {:canvas canvas})))

#_(let [new-canvas (webgl/create-canvas 300 400)]
       (.appendChild (.-body js/document) new-canvas)
       (defonce start-other-app (membrane.component/run-ui #'todo/todo-app @todo/todo-state nil {:canvas new-canvas})))





#_(ns component-example
  (:require [membrane.component ]
            [membrane.webgl :as webgl]
            [membrane.example.todo :as todo]))

#_(let [canvas (webgl/create-canvas 500 500)]
  (.appendChild (.-body js/document) canvas)
  (defonce start-app (membrane.component/run-ui #'todo/todo-app @todo/todo-state nil {:canvas canvas})))
