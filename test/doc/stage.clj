(ns doc.stage
  (:require [full.async :refer [<?? <?]]
            [clojure.core.async :refer [chan go-loop <!]]
            [midje.sweet :refer :all]
            [konserve.filestore :refer [new-fs-store]]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.peer :refer [server-peer]]
            [kabel.platform :refer [create-http-kit-handler! start stop]]
            [kabel.platform-log :refer [warn]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.fetch :refer [fetch]]))


(defn init-cdvcs [config]
  (let [{:keys [user cdvcs-id store remote peer]} config
        store (<?? (new-fs-store store) #_(new-mem-store))
        err-ch (chan)
        _ (go-loop [e (<! err-ch)]
            (when e
              (warn "ERROR:" e)
              (.printStackTrace e)
              (recur (<! err-ch))))
        peer-server (server-peer (create-http-kit-handler! peer err-ch) "LOCAL PEER"
                                 store err-ch
                                 :middleware (comp (partial block-detector :peer-core)
                                                   (partial fetch store (atom {}) err-ch)
                                                   ensure-hash
                                                   (partial block-detector :p2p-surface)))
        stage (<?? (create-stage! user peer-server err-ch))
        res {:store store
             :peer peer-server
             :stage stage
             :id cdvcs-id}]

    (when-not (= peer :client)
      (start peer-server))

    (when remote
      (<?? (connect! stage remote)))

    #_(<?? (s/create-cdvcs! stage "Profiling experiments." :id cdvcs-id))

    #_(when cdvcs-id
        (<?? (subscribe-crdts! stage {user #{cdvcs-id}})))
    res))


(comment

  (def state (init-cdvcs {:store "cdvcs/store"
                          :peer "ws://127.0.0.1:41745"
                          :user "mail:profiler@topiq.es"
                          :cdvcs-id #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"}))

  (stop (:peer state))

  (def stage (:stage state))

  (<?? (s/create-cdvcs! stage
                        :description "Profiling experiments."
                        :id #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"))

  (stop (:peer state))

  ;; TODO fix description
  (require '[konserve.protocols :refer [-get-in]])
  (let [h (<?? (-get-in (:store state) [["mail:profiler@topiq.es"
                                         #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"] :state :history]))
        hs (<?? (-get-in (:store state) [h]))]
    (for [h hs
          :let [c (count (<?? (-get-in (:store state) [h])))]
          :when (not= c 100)]
      c)) ;; '()
  (:read-handlers (:store state))
  (<?? (-get-in (:store state) [["mail:profiler@topiq.es"
                                 #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"]]))

  (count (get-in @stage ["mail:profiler@topiq.es" #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487" :state :commit-graph])) ;; 100001

  (get-in @stage ["mail:profiler@topiq.es" #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487" :state :heads])

  (keys (get-in @stage [:volatile :peer]))

  (require '[taoensso.timbre.profiling :as profiling :refer (pspy pspy* profile defnp p p*)])
  (def commit-latency
    (future
      (doall
       (for [n (range 1e4)]
         (let [start-ts (.getTime (java.util.Date.))]
           (when (= (mod n 100) 0) (println "Iteration:" n))
           (<?? (s/transact stage ["mail:profiler@topiq.es" (:id state)] 'conj
                            {:id n
                             :val (range 100)}))
           (if (= (mod n 100) 0)
             (time (<?? (s/commit! stage {"mail:profiler@topiq.es" #{(:id state)}})))
             (<?? (s/commit! stage {"mail:profiler@topiq.es" #{(:id state)}})))
           (- (.getTime (java.util.Date.)) start-ts))))))

  (spit "commit-latency-benchmark-1e5.edn" (vec @commit-latency)))
