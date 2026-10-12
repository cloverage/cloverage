(ns example.core)

(defn greeting [name]
  (if name
    (str "Hello, " name)
    "Hello, stranger"))
