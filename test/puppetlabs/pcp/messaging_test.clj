(ns puppetlabs.pcp.messaging-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.pcp.broker.service :refer [broker-service]]
            [puppetlabs.pcp.client :as client]
            [puppetlabs.pcp.message :as message]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-test-logging with-test-logging-debug]]
            [slingshot.test]))

(def broker-config
  "A broker with ssl and own spool"
  {:authorization {:version 1
                   :rules [{:name "allow all"
                            :match-request {:type "regex"
                                            :path "^/.*$"}
                            :allow-unauthenticated true
                            :sort-order 1}]}

   :webserver {:ssl-host     "127.0.0.1"
               ;; Default port is 8142.  Use 8143 here so we don't clash.
               :ssl-port     8143
               :client-auth  "want"
               :ssl-key      "./test-resources/ssl/private_keys/localhost.pem"
               :ssl-cert     "./test-resources/ssl/certs/localhost.pem"
               :ssl-ca-cert  "./test-resources/ssl/ca/ca_crt.pem"
               :ssl-crl-path "./test-resources/ssl/ca/ca_crl.pem"}

   :web-router-service {:puppetlabs.pcp.broker.service/broker-service {:websocket "/pcp"
                                                                       :metrics "/"}}

   :metrics {:enabled true}

   :pcp-broker {:broker-spool "test-resources/tmp/spool"
                :accept-consumers 2
                :delivery-consumers 2}})

(defn default-request-handler
  [conn request]
  (log/debug "Default handler got message" request))

(defn client-config
  "returns a client config for a given cn against the test-resource/ssl ca"
  [cn]
  {:server      "wss://localhost:8143/pcp/"
   :cert        (format "test-resources/ssl/certs/%s.example.com.pem" cn)
   :private-key (format "test-resources/ssl/private_keys/%s.example.com.pem" cn)
   :cacert      "test-resources/ssl/certs/ca.pem"
   :type        "demo-client"})

(defn connect-client-config
  "connect a client with a handler function"
  [config handler-function]
  (client/connect config
   {"example/any_schema"  handler-function
    :default              default-request-handler}))

(defn connect-client
  "connect a client with a handler function, uses default configuration strategy"
  [cn handler-fn]
  (connect-client-config (client-config cn) handler-fn))

(deftest send-message-and-assert-received-unchanged-test
  (testing "binary payloads"
    (with-app-with-config
      app
      [authorization-service broker-service jetty9-service webrouting-service metrics-service]
      broker-config
      (let [expected-data "Hello World!Ѱ$£%^\"\t\r\n(*)"
            message       (-> (message/make-message)
                              (message/set-expiry 3 :seconds)
                              (message/set-data (byte-array (.getBytes expected-data "UTF-8")))
                              (assoc :targets      ["pcp://client02.example.com/demo-client"]
                                     :message_type "example/any_schema"))
            received      (promise)]
        (with-open [sender        (connect-client "client01" (constantly true))
                    receiver      (connect-client "client02" (fn [conn msg] (deliver received msg)))]
          (client/wait-for-connection sender (* 40 1000))
          (client/wait-for-association sender (* 40 1000))
          (client/wait-for-connection receiver (* 40 1000))
          (client/wait-for-association receiver (* 40 1000))
          (client/send! sender message)
          (deref received)
          (is (= expected-data (String. (message/get-data @received) "UTF-8")))
          (is (= (:id message) (:id @received)))
          (is (= (:message_type message) (:message_type @received)))
          (is (= (:expires message) (:expires @received)))
          (is (= (:targets message) (:targets @received)))
          (is (= "pcp://client01.example.com/demo-client" (:sender @received))))))))

(deftest connect-to-a-down-broker-test
  (with-open [client (connect-client "client01" (constantly true))]
    (is (not (client/connected? client)) "Should not be connected yet")
    (with-app-with-config
      app
      [authorization-service broker-service jetty9-service webrouting-service metrics-service]
      broker-config
      (client/wait-for-connection client (* 40 1000))
      (is (client/connected? client) "Should now be connected"))
    (is (not (client/connected? client)) "Should be disconnected")))

(deftest connect-to-a-broker-with-the-wrong-name-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    (update-in broker-config [:webserver] merge
               {:ssl-key "./test-resources/ssl/private_keys/client01.example.com.pem"
                :ssl-cert "./test-resources/ssl/certs/client01.example.com.pem"})
    (with-open [client (connect-client "client01" (constantly true))]
      (client/wait-for-connection client (* 4 1000))
      (is (not (client/connected? client)) "Should never connect - ssl certificate of the broker is client01.example.com not localhost"))))

(deftest ssl-ca-cert-permutation-test
  ;; This test checks that ssl verification is happening
  ;; against the expected certificate chain.  We do this using an
  ;; alternate signing authority (test-resources/ssl-alt), and then
  ;; permute the ssl-ca-cert configured for client and broker.
  (doseq [[broker-ca client-ca associates]
          [["ssl"     "ssl"     true]  ;; well-configured case
           ["ssl"     "ssl-alt" false] ;; client should reject server cert
           ["ssl-alt" "ssl"     false] ;; server should reject client cert
           ["ssl-alt" "ssl-alt" false] ;; mutual rejection
           ]]
    (testing (str "broker-ca: " broker-ca " client-ca: " client-ca)
      (with-app-with-config
        app
        [authorization-service broker-service jetty9-service webrouting-service metrics-service]
        (assoc-in broker-config [:webserver :ssl-ca-cert]
                  (str "./test-resources/" broker-ca "/ca/ca_crt.pem"))
        (with-open [client (connect-client-config (assoc (client-config "client01")
                                                         :cacert (str "test-resources/" client-ca "/certs/ca.pem"))
                                                  (constantly true))]
          (client/wait-for-association client (* 4 1000))
          (is (= associates (client/associated? client))))))))

(deftest send-when-not-connected-test
  (with-open [client (connect-client "client01" (constantly true))]
    (is (thrown+? [:type :puppetlabs.pcp.client/not-associated]
                  (client/send! client (message/make-message))))))

(deftest connect-to-a-down-up-down-up-broker-test
  (with-open [client (connect-client "client01" (constantly true))]
    (is (not (client/connected? client)) "Should not be connected yet")
    (with-app-with-config
      app
      [authorization-service broker-service jetty9-service webrouting-service metrics-service]
      broker-config
      (client/wait-for-connection client (* 40 1000))
      (is (client/connected? client) "Should now be connected"))
    (is (not (client/connected? client)) "Should be disconnected")
    (with-app-with-config
      app
      [authorization-service broker-service jetty9-service webrouting-service metrics-service]
      broker-config
      (client/wait-for-connection client (* 40 1000))
      (is (client/connected? client) "Should be reconnected"))))

(deftest association-checkers-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (connect-client "client01" (constantly true))]
      (is (= client (client/wait-for-association client (* 40 1000))))
      (is (= false (client/associating? client)))
      (is (= true (client/associated? client))))))
