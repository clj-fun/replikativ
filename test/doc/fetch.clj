(ns doc.fetch
  (:require [replikativ.p2p.fetch :refer :all]
            [replikativ.protocols :refer [-missing-commits]]
            [replikativ.environ :refer [store-blob-trans-id]]
            [replikativ.crdt.cdvcs.impl :refer :all]
            [konserve.memory :refer [new-mem-store]]
            [clojure.core.async :refer [chan put!]]
            [full.async :refer [<?? <? go-loop-try go-try]]
            [midje.sweet :refer :all]))



(fact
 (let [out (chan)
       fetched-ch (chan)
       binary-fetched-ch (chan)
       store (<?? (new-mem-store))
       atomic-fetch-atom (atom {})]
   (go-loop-try [o (<? out)]
                (println "OUT" o)
                (recur (<? out)))
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {1 {:transactions [[11 12]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {2 []}
                                                                     :heads #{2}}}}}})
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {2 {:transactions [[21 22]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {3 []}
                                                                     :heads #{2}}}}}})
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {3 {:transactions [[store-blob-trans-id #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {1 []}
                                                                     :heads #{2}}}}}})
   (let [pub {:crdt :cdvcs
              :op {:method :new-state
                   :commit-graph {1 []}
                   :heads #{1}}}
         cvs (<?? (fetch-commit-values! out fetched-ch store atomic-fetch-atom ["a" 1] pub 42))
         txs (mapcat :transactions (vals cvs))]
     (put! fetched-ch {:type :fetch/edn-ack
                       :values {11 11
                                12 12
                                21 21
                                22 22
                                31 31
                                32 32}})
     (<?? (fetch-and-store-txs-values! out fetched-ch store txs 42))
     (put! binary-fetched-ch {:value 1123})
     (<?? (fetch-and-store-txs-blobs! out binary-fetched-ch store txs 42))
     (<?? (store-commits! store cvs)) =>
     '([nil {:crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {2 []}, :heads #{2}, :version nil}}, :transactions [[11 12]]}] [nil {:crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {3 []}, :heads #{2}, :version nil}}, :transactions [[21 22]]}] [nil {:crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {1 []}, :heads #{2}, :version nil}}, :transactions [[#uuid "3b0197ff-84da-57ca-adb8-94d2428c6227" #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]}]))))
