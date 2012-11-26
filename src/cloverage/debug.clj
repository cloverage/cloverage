(ns cloverage.debug)

;; debug output
(defn tprn [& args]
  (do
    #_(apply prn args)
    #_(newline)
    nil))

(defn tprnl [& args]
  (do
    #_(apply println args)
    nil))

(defn tprf [& args]
  (do
    #_(apply printf args)
    nil))
