(ns cloverage.sample.warn-on-reflection-sample)

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(defn plus-one [x]
  (+ x 1))
