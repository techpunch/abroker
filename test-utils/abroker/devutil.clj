(ns abroker.devutil)

;; Tools for dev time only to probe tws api for info useful during development

(defn dump-enum
  "Dumps all values of a Java enum class with their ordinal positions.
   Takes either a class object or a string class name."
  [enum-class]
  (let [class-obj (if (string? enum-class)
                    (Class/forName enum-class)
                    enum-class)
        values (.getEnumConstants class-obj)]
    (println (str "\n" (.getSimpleName class-obj) " enum values:"))
    (println (apply str (repeat 60 "-")))
    (doseq [v values]
      (println (format "%-3d %s" (.ordinal v) (.name v))))
    (println (str "\nTotal: " (count values) " values\n"))))

(defn dump-enum-with-api-string
  "Dumps enum values along with their API string representation.
   Useful to inspect TWS enums that have getApiString() method."
  [enum-class]
  (let [class-obj (if (string? enum-class)
                    (Class/forName enum-class)
                    enum-class)
        values (.getEnumConstants class-obj)]
    (println (str "\n" (.getSimpleName class-obj) " enum values:"))
    (println (apply str (repeat 80 "-")))
    (doseq [v values]
      (try
        (let [api-str (.getApiString v)]
          (println (format "%-3d %-30s -> \"%s\"" (.ordinal v) (.name v) api-str)))
        (catch Exception _
          (println (format "%-3d %s" (.ordinal v) (.name v))))))
    (println (str "\nTotal: " (count values) " values\n"))))

(comment
  ;; Dump TWS enums
  (dump-enum-with-api-string "com.ib.client.OrderType")
  (dump-enum-with-api-string "com.ib.client.Types$TimeInForce")
  (dump-enum-with-api-string "com.ib.client.Types$BarSize")
  (dump-enum-with-api-string "com.ib.client.Types$SecType")
  (dump-enum-with-api-string "com.ib.client.Types$Right")
  )