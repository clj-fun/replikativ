(ns doc.replicate
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [go-loop]]
            [kabel.peer :refer [drain]]
            [replikativ.core :refer [wire]]
            [replikativ.connect :refer [connect]]
            [replikativ.peer :refer [server-peer client-peer]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [kabel.middleware.log :refer [logger]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [kabel.platform :refer [create-http-kit-handler! start stop]]
            [kabel.platform-log :refer [warn info debug]]
            [konserve.memory :refer [new-mem-store]]
            [full.async :refer [go-try go-loop-try <? <??]]
            [clojure.core.async :refer [>! >!! timeout chan pub sub]]))

[[:chapter {:tag "replication" :title "Replication protocol of replikativ"}]]

"This chapter describes the synching protocol of replikativ. The synching protocol is the stateful network layer which ensures that updates (commits) to CRDTs propagate quickly and without conflicts. It is out of necessity eventual consistent, but tries to keep the diverging time frames as small as possible. "


[[:section {:tag "full-message-protocol" :title "Full Message Protocol"}]]

"The messaging protocol benefits from the *CRDT* nature of the metadata and has as little state as possible. Propagation can fail at any point and the network is still in a (locally) consistent state, so clients can keep writing without any synchronization. There is no server/client distinction except for the fact that some peers cannot accept connections (e.g. web-clients, clients behind a NAT). Each operation is acknowledged. As you can see in the following test, fetching actual transaction values happens based on need, only the metadata changes are pushed. User authentication as well as a trust mechanism between servers is not yet implemented, but will limit the propagation of values in the network at some point. For privacy encryption of transactional data is planned. Metadata here contains little private information and can be obfuscated."

"This is a demonstration of the low-level message API of the protocol. This API is subject to change and not supposed to be used by applications directly. For sake of simplicity we have replaced ids and values with small integers here as they are sufficient for the actual synching procedure."


(def log-atom (atom {}))

(facts
 (try
   (let [err-ch (chan)
         ;; remote server to sync to
         remote-store (<?? (new-mem-store))
         _ (go-loop [e (<? err-ch)]
             (when e
               (warn "ERROR:" e)
               (recur (<? err-ch))))
         _ (def remote-peer (<?? (server-peer remote-store err-ch "ws://127.0.0.1:9090/"
                                              :id "SERVER"
                                              :middleware (comp (partial block-detector :remote)
                                                                #_(partial logger log-atom :remote-core)
                                                                (partial fetch remote-store (atom {}) err-ch)))))

         ;; start it as its own server (usually you integrate it in ring e.g.)
         _ (start remote-peer)
         ;; local peer (e.g. used by a stage)
         local-store (<?? (new-mem-store))
         local-middlewares (comp (partial block-detector :local)
                                 #_(partial logger log-atom :local-core)
                                 (partial fetch local-store (atom {}) err-ch))

         _ (def local-peer (<?? (client-peer local-store err-ch
                                             :id "CLIENT"
                                             :middleware local-middlewares)))
         ;; hand-implement stage-like behaviour with [in out] channels
         in (chan)
         out (chan)]
     ;; to steer the local peer one needs to wire the input as our 'out' and output as our 'in'
     ((comp drain
            wire
            connect
            (partial block-detector :local)
            #_(partial logger log-atom :local-core)
            (partial fetch local-store (atom {}) err-ch))
      [local-peer [out in]])
     ;; subscribe to publications of CDVCS '1' from user 'john'
     (>!! out {:type :sub/identities
               :identities {"john" #{42}}
               :id 43})
     ;; ack sub
     (<?? in) => {:type :sub/identities-ack
                  :id 43}
     ;; subscription (back-)propagation (in peer network)
     (dissoc (<?? in) :id)
     => {:type :sub/identities,
         :extend? false,
         :identities {"john" #{42}}}
     (>!! out {:identities {"john" #{42}},
               :type :sub/identities-ack
               :id :ignored})
     ;; connect to the remote-peer
     (>!! out {:type :connect/peer
               :url "ws://127.0.0.1:9090/"
               :id 101})
     ;; ack
     (<?? in) => {:type :connect/peer-ack,
                  :url "ws://127.0.0.1:9090/",
                  :peer-id nil
                  :id 101}
     ;; publish a new value of CDVCS '42' of user 'john'
     (>!! out {:type :pub/downstream,
               :id 1001
               :user "john"
               :crdt-id 42
               :downstream {:crdt :cdvcs
                            :op {:method :handshake
                                 :commit-graph {1 []
                                                2 [1]}
                                 :heads #{2}}
                            :description "Bookmark collection."
                            :public false}})
     ;; the peer replies with a request for missing commit values
     (<?? in) => {:type :fetch/edn,
                  :id 1001
                  :ids #{1 2}}
     ;; send them...
     (>!! out {:type :fetch/edn-ack,
               :id 1001
               :values {1 {:transactions [[10 11]]}
                        2 {:transactions [[20 21]]}}})
     ;; fetch trans-values
     (<?? in) => {:type :fetch/edn,
                  :id 1001
                  :ids #{10 11 20 21}}
     ;; send them
     (>!! out {:type :fetch/edn-ack,
               :values {10 100
                        11 110
                        20 200
                        21 210}})
     ;; ack
     (<?? in) => {:type :pub/downstream-ack
                  :user "john"
                  :crdt-id 42
                  :id 1001}
     ;; back propagation of update
     (<?? in) => {:type :pub/downstream,
                  :id 1001
                  :user "john"
                  :crdt-id 42
                  :downstream {:crdt :cdvcs,
                               :op {:method :handshake,
                                    :commit-graph {1 []
                                                   2 [1]},
                                    :heads #{2}}
                               :public false,
                               :description "Bookmark collection."}}

     ;; ack
     (>!! out {:type :pub/downstream-ack
               :id 1001
               :user "john"
               :crdt-id 42})
     ;; send another update
     (>!! out {:type :pub/downstream,
               :id 1002
               :user "john"
               :crdt-id 42
               :downstream {:crdt :cdvcs
                            :op {:method :handshake
                                 :commit-graph {1 []
                                                2 [1]
                                                3 [2]}
                                 :heads #{3}},
                            :description "Bookmark collection.",
                            :public false}})
     ;; again a new commit value is needed
     (<?? in) => {:type :fetch/edn,
                  :id 1002
                  :ids #{3}}
     ;; send it...
     (>!! out {:type :fetch/edn-ack,
               :id 1002
               :values {3 {:transactions [[30 31]]}}})
     ;; again new tranaction values are needed
     (<?? in) => {:type :fetch/edn,
                  :id 1002
                  :ids #{30 31}}
     ;; send it...
     (>!! out {:type :fetch/edn-ack,
               :id 1002
               :values {30 300
                        31 310}})
     ;; ack
     (<?? in) => {:type :pub/downstream-ack,
                  :id 1002
                  :user "john"
                  :crdt-id 42}
     ;; and back-propagation
     (<?? in) => {:downstream {:crdt :cdvcs
                               :op {:method :handshake
                                    :heads #{3},
                                    :commit-graph {1 [], 2 [1], 3 [2]}},
                               :description "Bookmark collection.",
                               :public false},
                  :user "john"
                  :crdt-id 42
                  :id 1002,
                  :type :pub/downstream}
     ;; ack
     (>!! out {:type :pub/downstream-ack
               :id 1002})
     ;; wait for the remote peer to sync
     (<?? (timeout 500)) ;; let network settle
     ;; check the store of our local peer
     (-> @local-peer :volatile :store :state deref)
     => {:peer-config {:id "CLIENT", :sub {:subscriptions {"john" #{42}}
                                           :extend? false}}
         1 {:transactions [[10 11]]},
         2 {:transactions [[20 21]]},
         3 {:transactions [[30 31]]},
         10 100,
         11 110,
         ["john" 42] {:crdt :cdvcs,
                      :public false,
                      :description "Bookmark collection.",
                      :state #replikativ.crdt.CDVCS{:commit-graph {1 [], 2 [1], 3 [2]},
                                                    :version 1,
                                                    :heads #{3}}}

         20 200,
         21 210,
         30 300,
         31 310}
     ;; check the store of the remote peer
     (-> @remote-peer :volatile :store :state deref)
     => {:peer-config {:id "SERVER", :sub {:subscriptions {"john" #{42}}
                                           :extend? true}}
         1 {:transactions [[10 11]]},
         2 {:transactions [[20 21]]},
         3 {:transactions [[30 31]]},
         10 100,
         11 110,
         ["john" 42] {:crdt :cdvcs,
                      :public false,
                      :description "Bookmark collection.",
                      :state #replikativ.crdt.CDVCS{:commit-graph {1 [], 2 [1], 3 [2]},
                                                    :version 1,
                                                    :heads #{3}}}

         20 200,
         21 210,
         30 300,
         31 310}

     ;; stop peers
     (stop local-peer)
     (stop remote-peer))
   (catch Exception e
     (.printStackTrace e))))


(comment
  (aprint local-peer))
