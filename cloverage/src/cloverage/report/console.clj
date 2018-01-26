(ns cloverage.report.console
  (:gen-class)
  (:require
   [clojure.string :as cs]
   [cloverage.report :refer [file-stats total-stats]]))

(def ansi
  (let [esc \u001b
        reset "[0m"
        tbl {:red "[31m"
             :yellow "[33m"
             :green "[32m"
             :bright "[1m"}]
    (fn [code text]
      (when text
        (str esc (get tbl code reset) text esc reset)))))

(defn strip-ansi [text]
  (when text
    (cs/replace text #"\u001b\[\d+m" "")))

(defn printable-count [text]
  (->> text strip-ansi count))

(defn colorize [formatter low-watermark high-watermark]
  (cond
    (neg? low-watermark)
    (throw (IllegalArgumentException. "Low-watermark cannot be less than 0%"))

    (> high-watermark 100)
    (throw (IllegalArgumentException. "High-watermark cannot be more than 100%"))

    (> low-watermark high-watermark)
    (throw (IllegalArgumentException. "Low-watermark must be under high-watermark")))

  :else
  (letfn [(f
            ([pct] (f pct (format formatter pct)))
            ([pct text]
             (cond
               (< pct low-watermark)
               (ansi :red text)

               (>= pct high-watermark)
               (ansi :green text)

               :else
               (ansi :yellow text))))]
    f))

(defn pad-right [width text]
  (let [len (printable-count text)]
    (str (cs/join (repeat (- width len) " ")) text)))

(defn print-table [ks rows total]
  (when (seq rows)
    (let [widths (map
                  (fn [k]
                    (apply max
                           (printable-count (str k))
                           (map #(printable-count (str (get % k))) rows)))
                  ks)
          spacers (map #(apply str (repeat % "-")) widths)
          fmt-row (fn [leader divider trailer row]
                    (str leader
                         (apply str (interpose divider
                                               (for [[col w] (map vector (map #(get row %) ks) widths)]
                                                 (pad-right w (str col)))))
                         trailer))]
      (println)
      (println (fmt-row "|-" "-+-" "-|" (zipmap ks spacers)))
      (println (fmt-row "| " " | " " |" (zipmap ks ks)))
      (println (fmt-row "|-" "-+-" "-|" (zipmap ks spacers)))
      (doseq [row rows]
        (println (fmt-row "| " " | " " |" row)))
      (println (fmt-row "|-" "-+-" "-|" (zipmap ks spacers)))
      (println (fmt-row "| " " | " " |" (zipmap ks total)))
      (println (fmt-row "|-" "-+-" "-|" (zipmap ks spacers))))))

(defn summary
  "Create a text summary for output on the command line"
  [forms low-watermark high-watermark]
  (let [colorizer (colorize "%.2f" low-watermark high-watermark)
        line-calc (fn [file-stat]
                    (let [libname   (:lib  file-stat)
                          forms     (:forms file-stat)
                          cov-forms (:covered-forms file-stat)
                          instrd    (:instrd-lines  file-stat)
                          covered   (:covered-lines file-stat)
                          partial   (:partial-lines file-stat)
                          forms-pct (/ (* 100.0 cov-forms) forms)
                          lines-pct (/ (* 100.0 (+ covered partial)) instrd)]
                      {"Namespace" (colorizer (min forms-pct lines-pct) libname)
                       "% Forms"   (colorizer forms-pct)
                       "% Lines"   (colorizer lines-pct)}))
        namespaces (->> (file-stats forms) (sort-by :lib) (map line-calc))
        totalled-stats (total-stats forms)
        all-forms-pct (:percent-forms-covered totalled-stats)
        all-lines-pct (:percent-lines-covered totalled-stats)
        totals [(colorizer (min all-forms-pct all-lines-pct) "ALL FILES")
                (colorizer all-forms-pct)
                (colorizer all-lines-pct)]]
    (print-table ["Namespace" "% Forms" "% Lines"] namespaces totals)))
