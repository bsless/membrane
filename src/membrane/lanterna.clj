(ns membrane.lanterna
  (:require [membrane.ui :as ui
             :refer [IBounds
                     bounds
                     -bounds
                     IOrigin
                     -origin
                     vertical-layout
                     horizontal-layout
                     maybe-key-press
                     on]]
            [clojure.core.async :as async
             :refer [<!! >!!]]
            [com.rpl.specter :as spec]
            ;; need effects
            [membrane.basic-components :as basic]
            [membrane.component :as component
             :refer [defui defeffect]])
  
  (:import


   ;; import com.googlecode.lanterna.*;
   com.googlecode.lanterna.terminal.MouseCaptureMode
   com.googlecode.lanterna.input.MouseActionType
   com.googlecode.lanterna.input.MouseAction
   com.googlecode.lanterna.terminal.ansi.UnixTerminal
   com.googlecode.lanterna.graphics.TextGraphics
   com.googlecode.lanterna.input.KeyStroke
   com.googlecode.lanterna.input.KeyType
   com.googlecode.lanterna.screen.Screen
   com.googlecode.lanterna.screen.Screen$RefreshType
   com.googlecode.lanterna.screen.TerminalScreen
   com.googlecode.lanterna.TerminalPosition
   com.googlecode.lanterna.terminal.DefaultTerminalFactory
   com.googlecode.lanterna.terminal.Terminal
   com.googlecode.lanterna.TextColor
   com.googlecode.lanterna.TextColor$ANSI
   com.googlecode.lanterna.TextColor$RGB
   com.googlecode.lanterna.TextColor$Indexed
   com.googlecode.lanterna.terminal.TerminalResizeListener

   sun.misc.Signal
   sun.misc.SignalHandler

   java.nio.charset.Charset)
  (:gen-class))

(defonce in System/in)
(defonce out System/out)

;; (set! *warn-on-reflection* true)

;; The whole goal of this middleware is to make it easy to load this namespace
;; before the nrepl server starts so that System/in and out can be stored.
(defn
  preserve-system-io
  {:nrepl.middleware/descriptor {}}
  [h]
  (fn [msg]
    (h msg)))

(defonce log-lines (atom []))
(defn log [s]
  ;; (println s)
  #_(spit "/var/tmp/graal.log" (str s "\n")  :append true)
  #_(swap! log-lines (fn [lines]
                     (let [lines (conj lines (str s))
                           c (count lines)]
                       (subvec lines (max 0 (- c 30)))))))
(defn log-ui []
  (vec
   (apply
    vertical-layout
    (for [line @log-lines]
      (ui/label (subs line 0 (min 80 (count line))))))))



;; https://en.wikipedia.org/wiki/Block_Elements
;; https://en.wikipedia.org/wiki/Box-drawing_character
;; https://github.com/eliukblau/pixterm

(def ^:dynamic *context* {})
(def ^:dynamic *tg* nil)
(def ^:dynamic *screen* nil)

(defprotocol IDraw
  :extend-via-metadata true
  (draw [this]))


(defn tp [col row]
  (TerminalPosition. col row))

(ui/add-default-draw-impls! IDraw #'draw)

(defrecord Label [lines]
    IBounds
    (-bounds [this]
        [(apply max (map #(.length ^String %) lines))
         (count lines)])

  IDraw
  (draw [this]
      (let [{:keys [x y]} (:translate *context*)]
        (doseq [[i line] (map-indexed vector lines)]
          (.putString ^TextGraphics *tg* x (+ y i) line))))
    IOrigin
    (-origin [_]
        [0 0]))


(defn -label
  "Graphical elem that can draw text.

  label will use the default line spacing for newline."
  [text]
  (Label. (clojure.string/split (str text) #"\n")))
(def label (identity -label))


(defrecord Rectangle [width height]
    IOrigin
    (-origin [_]
        [0 0])
    IBounds
    (-bounds [this]
        [width height])

    IDraw
    (draw [this]
        (let [{:keys [width height]} this
              {:keys [x y]} (:translate *context*)
          
              dx (dec width)
              dy (dec height)]

          (cond

            (and (<= width 1)
                 (<= height 1))
            (.setCharacter ^TextGraphics *tg*  ^TerminalPosition (tp x y) \☐)

            :else
            (do
          
              (when (pos? (- height 2))
                ;; left edge
                (.drawLine ^TextGraphics *tg*
                           ^TerminalPosition (tp x (inc y))
                           ^TerminalPosition (tp x (dec (+ y dy)))
                           \│)
                ;; right edge
                (.drawLine ^TextGraphics *tg*
                           ^TerminalPosition (tp (+ x dx) (inc y))
                           ^TerminalPosition (tp (+ x dx) (dec (+ y dy)))
                           \│))

              (when (pos? (- width 2))
                ;; top edge
                (.drawLine ^TextGraphics *tg*
                           ^TerminalPosition (tp (inc x) y)
                           ^TerminalPosition (tp (dec (+ x dx)) y)
                           \─)
                ;; bottom edge
                (.drawLine ^TextGraphics *tg*
                           ^TerminalPosition (tp (inc x) (+ y dy))
                           ^TerminalPosition (tp (dec (+ x dx)) (+ y dy))
                           \─))

              ;; top left corner
              (.setCharacter ^TextGraphics *tg*
                             ^TerminalPosition (tp x y)
                             \┌)
              ;; bottom left corner
              (.setCharacter ^TextGraphics *tg*
                             ^TerminalPosition (tp x (+ y dy))
                             \└)
              ;; top right corner
              (.setCharacter ^TextGraphics *tg*
                             ^TerminalPosition (tp (+ x dx)  y)
                             \┐)
              ;; bottom right corner
              (.setCharacter ^TextGraphics *tg*
                             ^TerminalPosition (tp (+ x dx)  (+ y dy))
                             \┘))))))

(defn rectangle [width height]
  (Rectangle. width height))

(defrecord Button [text on-click hover?]
    ui/IOrigin
    (-origin [_]
        [0 0])

    IBounds
    (-bounds [this]
        [(+ 2 (count (:text this))) 3])

    ui/IMouseEvent
    (-mouse-event [this pos button mouse-down? mods]
      (when mouse-down?
        (on-click)))

    IDraw
    (draw [this]
        (let [text (:text this)]
          (draw
           [(rectangle (+ 2 (count text)) 3)
            (ui/translate 1 1
                          (label text))]))))

(defn button
  "Graphical elem that draws a button. Optional on-click function may be provided that is called with no arguments when button has a mouse-down event."
  ([text]
   (Button. text nil false))
  ([text on-click]
   (Button. text on-click false))
  ([text on-click hover?]
   (Button. text on-click hover?)))

;; TODO: change mx -> pos and support
;;       multi-line textareas
(defeffect ::move-cursor-to-pos [$cursor text mx]
  (run! #(apply dispatch! %)
        [[:update $cursor (fn [cursor]
                            (min (count text)
                                 mx))]]))

(defeffect ::finish-drag [$select-cursor $cursor $down-pos pos text]
  (let [[mx my] pos
        end-index (min (count text)
                       mx)]
    (run! #(apply dispatch! %)
          [
           [:update [(spec/collect-one (component/path->spec $down-pos))
                     $select-cursor]
            (fn [down-pos select-cursor]
              (when-let [[dx dy] down-pos]
                (let [idx (min (count text)
                               dx)]
                  (when (not= idx end-index)
                    (if (> idx end-index)
                      (min (count text) (inc idx))
                      idx))))
              
              )]
           [:set $down-pos nil]
           [:update [(spec/collect-one (component/path->spec $select-cursor))
                     $cursor]
            (fn [select-cursor cursor]
              (if (and select-cursor (> end-index select-cursor))
                (min (count text) (inc end-index))
                end-index))]])))

(def double-click-threshold 500)
(let [getTimeMillis (fn [] (.getTime ^java.util.Date (java.util.Date.)))
      pow (fn [n x] (Math/pow n x))
      
      find-white-space (fn [text start]
                         (let [matcher (doto (re-matcher  #"\s" text)
                                         (.region start (count text)))]
                           (when (.find matcher)
                             (.start matcher))))]
  (defeffect ::text-double-click [$last-click $select-cursor $cursor pos text]
    (let [now (getTimeMillis)
          [mx my] pos]
      (run! #(apply dispatch! %)
            [
             [:update [(spec/collect-one (component/path->spec $last-click))
                       $select-cursor]
              (fn [[last-click [dx dy]] select-cursor]
                (if last-click
                  (let [diff (- now last-click)]
                    (if (and (< diff double-click-threshold)
                             (< (+ (pow (- mx dx) 2)
                                   (pow (- my dy) 2))
                                100))
                      (let [index (min (count text)
                                       mx)]
                        (if-let [start (find-white-space text index)]
                          start
                          (count text)))
                      select-cursor))
                  select-cursor))]
             [:update [(spec/collect-one (component/path->spec $last-click))
                       $cursor]
              (fn [[last-click [dx dy]] cursor]
                (if last-click
                  (let [diff (- now last-click)]
                    (if (and (< diff double-click-threshold)
                             (< (+ (pow (- mx dx) 2)
                                   (pow (- my dy) 2))
                                100))
                      (let [index (min (count text)
                                       mx)
                            text-backwards (clojure.string/reverse text)]
                        (if-let [start (find-white-space text-backwards
                                                         (- (count text) index))]
                          (- (count text) start)
                          0)
                        )
                      cursor))
                  cursor))]

             [:set $last-click [now pos]]]))
    ))

(defrecord CheckboxView [checked?]
    IOrigin
    (-origin [_]
        [0 0])

    IDraw
    (draw [this]
        ;; looks like:
        ;; [ ] unchecked
        ;; [*] checked
        ;; tried using ☐ and ☑, but I think this looks better
        (let [{:keys [x y]} (:translate *context*)
              ]
          (.setCharacter ^TextGraphics *tg*  ^TerminalPosition (tp x y) \[)
          (.setCharacter ^TextGraphics *tg*  ^TerminalPosition (tp (+ 2 x) y) \])

          (if checked?
            (let [{:keys [x y]} (:translate *context*)]
              (.setCharacter ^TextGraphics *tg*  ^TerminalPosition (tp (inc x) y) \* )))))

    IBounds
    (-bounds [this]
        [3 1]))

(defn checkbox-view
  "Graphical elem that will draw a checkbox."
  [checked?]
  (CheckboxView. checked?))

(defui checkbox
  "Checkbox component."
  [{:keys [checked?]}]
  (on
   :mouse-down
   (fn [_]
     [[:update $checked? not]])
   (checkbox-view checked?)))


(defrecord TextSelection [text selection]
    
    IBounds
    (-bounds [this]
        (bounds (label text)))
  IDraw
  (draw [this]
      (let [old-bg (.getBackgroundColor ^TextGraphics *tg*)
            {:keys [x y]} (:translate *context*)
            
            text (:text this)
            text-length (count text)
            [start end] selection]
        (.setBackgroundColor ^TextGraphics *tg*
                             (TextColor$Indexed/fromRGB 185
                                                        215
                                                        251))
        (doseq [cur (range start end)
                :let [c (.charAt ^String text cur)]]
          (.setCharacter ^TextGraphics *tg* ^TerminalPosition (tp (+ x cur) y) c))
        (.setBackgroundColor ^TextGraphics *tg* old-bg)
        )
      )
    IOrigin
    (-origin [_]
        [0 0]))

(defn text-selection
  "Graphical elem for drawing a selection of text."
  ([text [selection-start selection-end :as selection]]
   (TextSelection. (str text) selection )))



;; TODO: add support for text selection
;;       text selection is currently annoying because of the
;;       way foreground and background work.
(defui textarea-view
  "Raw component for a basic textarea. textarea should be preferred."
  [{:keys [cursor
             focus?
             text
             down-pos
             mpos
             select-cursor
             last-click]
      :or {cursor 0
           text ""}}]
  (let [text (or text "")]
    (maybe-key-press
     focus?
     (on
      :key-press
      (fn [s]
        (when focus?
          (case s

            :up
            [[:membrane.basic-components/previous-line $cursor $select-cursor  text]]

            :enter
            [[:membrane.basic-components/insert-newline $cursor $select-cursor $text]]

            :down
            [[:membrane.basic-components/next-line $cursor $select-cursor text]]

            :left
            [[:membrane.basic-components/backward-char $cursor $select-cursor text]]

            :right
            [[:membrane.basic-components/forward-char $cursor $select-cursor text]]

            :backspace
            [[:membrane.basic-components/delete-backward $cursor $select-cursor $text]]

            ;; else
            (when (string? s)
              [[:membrane.basic-components/insert-text  $cursor $select-cursor $text s]]))))
      :mouse-up
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         [[::finish-drag $select-cursor $cursor $down-pos pos text]
          [::text-double-click $last-click $select-cursor $cursor pos text]
          ]))
      :mouse-down
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         [[::request-focus]
          [::move-cursor-to-pos $cursor text mx]
          [:membrane.basic-components/start-drag $mpos $down-pos pos]
          [:set $select-cursor nil]]))
      :mouse-move
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         (when down-pos
           [[:membrane.basic-components/drag $mpos pos]])))
      
      (let [lbl (label text)
            [label-width label-height] (bounds lbl)]
        [(ui/with-color [0.5 0.5 0.5]
           (ui/rounded-rectangle (+ 3 (count text)) (+ 2 label-height) 0))
         
         (ui/translate 1 1
                       [lbl
                        (when select-cursor
                          (text-selection text
                                          [(min select-cursor cursor)
                                           (max select-cursor cursor)]))
                        (when (and mpos down-pos)
                          (let [mx (first mpos)
                                dx (first down-pos)]
                           (text-selection text
                                           [(max 0 (min mx dx))
                                            (min (count text) (max mx dx))])))
                        (when focus?
                          (ui/text-cursor text cursor nil))])
         ])
      ))))

(defui textarea
  "Textarea component."
  [{:keys [text
             ^:membrane.component/contextual focus
             textarea-state]}]
  (on
   ::request-focus
   (fn []
     [[:set [$focus] $text]])
   (textarea-view {:text text
                   :cursor (get textarea-state :cursor 0)
                   :focus? (= focus $text)
                   :down-pos (:down-pos textarea-state)
                   :mpos (:mpos textarea-state)
                   :select-cursor (:select-cursor textarea-state)})))

(extend-type membrane.ui.Translate
  IDraw
  (draw [this]
    (binding [*context* (->> *context*
                             (spec/transform [:translate :x] (partial + (:x this)))
                             (spec/transform [:translate :y] (partial + (:y this)))) ]
      (draw (:drawable this)))))





(extend-type membrane.ui.TextCursor
  IBounds
  (-bounds [this]
    [(count (:text this)) 1])

  IDraw
  (draw [this]
    (let [old-bg (.getBackgroundColor ^TextGraphics *tg*)
          {:keys [x y]} (:translate *context*)
          cur (:cursor this)
          row y
          text (:text this)
          text-length (count text)
          col (+ x (min cur
                        text-length))
          pos (TerminalPosition. col row)
          c (if (>= cur text-length)
              \space
              (.charAt ^String text cur))]
      
      ;; (.setCursorPosition *screen* (TerminalPosition. col row))
      (.setBackgroundColor ^TextGraphics *tg*
                           ;; TextColor$ANSI/RED
                           (TextColor$Indexed/fromRGB 146
                                                      146
                                                      146))
      
      (.setCharacter ^TextGraphics *tg* pos ^Character c)
      (.setBackgroundColor ^TextGraphics *tg* old-bg)
      )))


(extend-type membrane.ui.RoundedRectangle

  IDraw
  (draw [this]
    (let [{:keys [width height border-radius]} this
          {:keys [x y]} (:translate *context*)
          
          dx (dec width)
          dy (dec height)
          ]

      

      (cond

        (and (<= width 1)
             (<= height 1))
        (.setCharacter ^TextGraphics *tg* ^Integer (int x) (int y) \O)

        :else
        (do
          
          (when (pos? (- height 2))
            ;; left edge
            (.drawLine ^TextGraphics *tg*
                       ^TerminalPosition (tp x (inc y))
                       ^TerminalPosition (tp x (dec (+ y dy)))
                       \│)
            ;; right edge
            (.drawLine ^TextGraphics *tg*
                       ^TerminalPosition (tp (+ x dx) (inc y))
                       ^TerminalPosition (tp (+ x dx) (dec (+ y dy)))
                       \│))

          (when (pos? (- width 2))
            ;; top edge
            (.drawLine ^TextGraphics *tg*
                       ^TerminalPosition (tp (inc x) y)
                       ^TerminalPosition (tp (dec (+ x dx)) y)
                       \─)
            ;; bottom edge
            (.drawLine ^TextGraphics *tg*
                       ^TerminalPosition (tp (inc x) (+ y dy))
                       ^TerminalPosition (tp (dec (+ x dx)) (+ y dy))
                       \─))

          ;; top left corner
          (.setCharacter ^TextGraphics *tg*
                         ^TerminalPosition (tp x y)
                         \╭)
          ;; bottom left corner
          (.setCharacter ^TextGraphics *tg*
                         ^TerminalPosition (tp x (+ y dy))
                         \╰)
          ;; top right corner
          (.setCharacter ^TextGraphics *tg*
                         ^TerminalPosition (tp (+ x dx)  y)
                         \╮)
          ;; bottom right corner
          (.setCharacter ^TextGraphics *tg*
                         ^TerminalPosition (tp (+ x dx)  (+ y dy))
                         \╯)))

      #_(.putString ^TextGraphics *tg*  1 1 (pr-str *context*)))))


(extend-type membrane.ui.WithColor
  IDraw
  (draw [this]
    (let [[r g b] (:color this)
          old-fg (.getForegroundColor ^TextGraphics *tg*)]
      (.setForegroundColor ^TextGraphics *tg* (TextColor$Indexed/fromRGB (int (Math/round (* 255.0 r)))
                                                           (int (Math/round (* 255.0 g)))
                                                           (int (Math/round (* 255.0 b))) ))
      (doseq [drawable (:drawables this)]
        (draw drawable))
      (.setForegroundColor ^TextGraphics *tg* old-fg))))



(defprotocol ITerminalResized (-terminal-resized [elem size]))

(defrecord OnTerminalResized [on-terminal-resized drawables]
  IOrigin
  (-origin [_]
    [0 0])

  IBounds
  (-bounds [this]
    (reduce
     (fn [[max-width max-height] elem]
       (let [[ox oy] (ui/origin elem)
             [w h] (ui/bounds elem)]
         [(max max-width (+ ox w))
          (max max-height (+ oy h))]))
     [0 0]
     drawables))

  ITerminalResized
  (-terminal-resized [this size]
    (when on-terminal-resized
      (on-terminal-resized size)))

  ui/IMakeNode
  (make-node [this childs]
    (OnTerminalResized. on-terminal-resized childs))

  IDraw
  (draw [this]
    (run! draw drawables))

  ui/IChildren
  (-children [this]
    drawables))

(def
  ^{:arglists '([elem size]),
    :doc "Returns the effects of a terminal resized event on elem."}
  terminal-resized (ui/make-event-handler "ITerminalResized" ITerminalResized -terminal-resized))

(defn on-terminal-resized [on-terminal-resized & drawables]
  (OnTerminalResized. on-terminal-resized drawables))

(defmethod ui/on-handler :terminal-resized
  [event-type handler body]
  (on-terminal-resized handler body))

;; https://github.com/mabe02/lanterna/issues/440#issuecomment-639410790
(defn add-resize-listener [^UnixTerminal terminal ch]
  (Signal/handle (Signal. "WINCH")
                 (reify
                   sun.misc.SignalHandler
                   (handle [_ sig]
                     (log "got resize signal")
                     (async/put! ch true)))))

(defn run-helper [make-ui {:keys [repaint-ch close-ch handler in out] :as options}]
  (let [
        term (doto (UnixTerminal. in out (Charset/defaultCharset))
               (.setMouseCaptureMode MouseCaptureMode/CLICK_RELEASE_DRAG_MOVE))
        screen (TerminalScreen. term)
        resize-ch (async/chan (async/dropping-buffer 1))
        _ (add-resize-listener term
                               resize-ch)

        ui (volatile! nil)
        last-term-size (volatile! nil)
        input-future (future
                       (try
                         (loop []
                           (let [input (.readInput screen)]
                             (handler @ui input)
                             (>!! repaint-ch true))
                           (when (not (Thread/interrupted))
                             (recur)))
                         (catch Exception e
                           (log e))
                         (finally
                           (log "closing input")
                           (async/close! close-ch))))
        ]

    (.setCursorPosition screen nil)
    (.startScreen screen)
    (log "starting")
    (let [tg (.newTextGraphics screen)]
      (try
        (doto tg
          (.setForegroundColor TextColor$ANSI/BLACK)
          (.setBackgroundColor TextColor$ANSI/DEFAULT))

        (>!! repaint-ch true)
        (loop []
          (let [[_ port] (async/alts!! [close-ch repaint-ch resize-ch (async/timeout 500)]
                                       :priority true)]

            (when (not= port close-ch)

              (when (= port resize-ch)
                (.getTerminalSize term)
                (.doResizeIfNecessary ^TerminalScreen screen))

              (let [last-ui @ui
                    current-ui (vreset! ui (try
                                             (make-ui)
                                             (catch Exception e
                                               (label (str e)))))
                    term-size (.getTerminalSize screen)
                    size-change? (not= @last-term-size term-size)]
                (when (or size-change?
                          (not= current-ui last-ui))

                  (when size-change?
                    (terminal-resized current-ui [(.getColumns term-size)
                                                  (.getRows term-size)]))

                  (binding [*tg* tg
                            *context* {:translate {:x 0 :y 0}}
                            *screen* screen]
                    (log "repainting")
                    (.clear screen)
                    (.setCursorPosition screen nil)
                    (draw (ui/try-draw
                           current-ui
                           (fn [& args]
                             nil)))
                    (if size-change?
                      (do (.refresh screen)
                          (vreset! last-term-size term-size))
                      (.refresh screen
                                com.googlecode.lanterna.screen.Screen$RefreshType/DELTA)))))
              (recur))))
        (catch Exception e
          (log e)
          (throw e))
        (finally
          (log "stopping repaint")
          (async/close! resize-ch)
          (.close screen)
          (future-cancel input-future))))))

(defn default-handler [ui event]
  ;; (log event)
  ;; (log (.getKeyType event))
  (condp = (.getKeyType ^KeyStroke event)


    KeyType/MouseEvent
    ;; mouse
    (condp = (.getActionType ^MouseAction event)
      
      MouseActionType/CLICK_DOWN
      (let [pos (.getPosition ^MouseAction event)]
        (ui/mouse-down ui [(.getColumn ^TerminalPosition pos)
                           (.getRow ^TerminalPosition pos)]))

      MouseActionType/CLICK_RELEASE
      (let [pos (.getPosition ^MouseAction event)]
        (ui/mouse-up ui [(.getColumn ^TerminalPosition pos)
                         (.getRow ^TerminalPosition pos)]))
      MouseActionType/DRAG
      (let [pos (.getPosition ^MouseAction event)]
        (ui/mouse-move ui [(.getColumn ^TerminalPosition pos)
                           (.getRow ^TerminalPosition pos)]))
      MouseActionType/MOVE
      (let [pos (.getPosition ^MouseAction event)]
        (ui/mouse-move ui [(.getColumn ^TerminalPosition pos)
                           (.getRow ^TerminalPosition pos)]))
      MouseActionType/SCROLL_DOWN nil
      MouseActionType/SCROLL_UP nil)

    KeyType/Character
    (do
      ;; (ui/key-event ui key scancode action mods)
      (when-let [c (.getCharacter ^KeyStroke event)]
        (ui/key-press ui (str c))))

    KeyType/Backspace
    (ui/key-press ui :backspace)

    KeyType/Enter
    (ui/key-press ui :enter)

    KeyType/ArrowDown
    (ui/key-press ui :down)
    KeyType/ArrowLeft
    (ui/key-press ui :left)
    KeyType/ArrowRight
    (ui/key-press ui :right)
    KeyType/ArrowUp
    (ui/key-press ui :up)

    ;;default
    nil))

(defn run-sync
  ([make-ui]
   (run-sync make-ui nil))
  ([make-ui {:keys [handler repaint-ch close-ch in out] :as options}]
   (let [default-options
         (let [repaint-ch (async/chan (async/sliding-buffer 1))
               close-ch (async/promise-chan)]
           {:handler default-handler
            :repaint-ch repaint-ch
            :in System/in
            :out System/out
            :close-ch close-ch})]
     (run-helper make-ui
                 (merge default-options
                        options)))))

(defn run
  ([make-ui {:keys [handler repaint-ch close-ch in out] :as options}]
   (async/thread
     (run-sync make-ui options)))
  ([make-ui]
   (async/thread
     (run-sync make-ui))))




