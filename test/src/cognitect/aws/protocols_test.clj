;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols-test
  "Test the protocols implementations."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cognitect.aws.util :as util]
            [cognitect.aws.client :as client]
            [cognitect.aws.protocols.json]
            [cognitect.aws.protocols.rest-json]
            [cognitect.aws.protocols.rest-xml]
            [cognitect.aws.protocols.query]
            [cognitect.aws.protocols.ec2]))

(defn test-request-method
  [expected {:keys [request-method]}]
  (when expected
    (is (= (keyword (str/lower-case expected)) request-method))))

(defn test-request-uri
  [expected {:keys [uri]}]
  (is (= expected uri)))

(defn test-request-headers
  [expected {:keys [headers] :as foo}]
  (doseq [[k v] expected
          :let [s (str/lower-case (name k))]]
    (is (contains? headers s))
    (is (= v (get headers s)))))

(defmulti test-request-body (fn [protocol expected request] protocol))

(defmethod test-request-body :default
  [_ expected {:keys [body]}]
  (let [body-str (util/bbuf->str body)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (is (= expected body-str)))))

(defmethod test-request-body "json"
  [_ expected http-request]
  (is (= (some-> expected json/read-str)
         (some-> http-request :body util/bbuf->str json/read-str))))

(defmethod test-request-body "rest-xml"
  [_ expected http-request]
  (is (= (not-empty expected)
         (some-> http-request :body util/bbuf->str))))

(defmethod test-request-body "rest-json"
  [_ expected http-request]
  (let [body-str (some-> http-request :body util/bbuf->str)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (if-let [expected-json (try (json/read-str expected)
                                  (catch Throwable t))]
        (is (= expected-json (some-> body-str json/read-str)))
        ;; streaming, no JSON payload, we compare strings directly
        (is (= expected body-str))))))

(defmulti run-test (fn [io service test-case] io))

(defmethod run-test "input"
  [_ service {expected :serialized
              :keys    [given params]
              :as      test-case}]
  (try
    (let [op-map       {:op (:name given) :request params}
          http-request (client/build-http-request service op-map)]
      (test-request-method (:method expected) http-request)
      (test-request-uri (:uri expected) http-request)
      (test-request-headers (:headers expected) http-request)
      (test-request-body (get-in service [:metadata :protocol]) (:body expected) http-request))
    (catch Exception e
      (is (nil?
           {:expected  expected
            :test-case test-case
            :exception e})))))

(defmethod run-test "output"
  [_ service {:keys [given response result] :as test-case}]
  (try
    (let [op-map          {:op (:name given)}
          parsed-response (client/parse-http-response service
                                                      op-map
                                                      {:status  (:status_code response)
                                                       :headers (:headers response)
                                                       :body    (util/str->bbuf (:body response))})]
      (when-let [error (::client/error parsed-response)]
        (throw (or (::client/throwable parsed-response)
                   (ex-info "Client Error." parsed-response))))
      (is (= result (::client/result parsed-response))))
    (catch Exception e
      (is (nil?
           {:test-case test-case
            :exception e})))))

(defn test-protocol
  ([protocol]
   (test-protocol protocol "input")
   (test-protocol protocol "output"))
  ([protocol input-or-output]
  (let [filepath (str "botocore/protocols/" input-or-output "/" protocol ".json")]
    (doseq [test (-> filepath io/resource slurp (json/read-str :key-fn keyword))]
      (testing (str input-or-output " of " protocol " : " (:description test))
        (doseq [{:keys [given] :as test-case} (:cases test)
                :let [service (assoc (select-keys test [:metadata :shapes])
                                     :operations {(:name given) given})]]
          (run-test input-or-output service test-case)))))))

(deftest test-protocols
  (with-redefs [util/gen-idempotency-token (constantly "00000000-0000-4000-8000-000000000000")]
    (doseq [protocol ["ec2"
                      "query"
                      "json"
                      "rest-xml"
                      "rest-json"]]
      (test-protocol protocol))))

(comment
  (run-tests)

  )