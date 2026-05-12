(ns cot.generate-test-form-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [cot.generator :as gen]))

(defn- form-contains?
  [form sym]
  (boolean
   (some #(= sym %)
         (tree-seq coll? seq form))))

(deftest generate-test-form-array-response-test
  (let [operation {:parameters [{:in "query" :name "limit"}]
                   :responses {:200 {:content {:application/json
                                               {:schema {:type "array"
                                                         :items {:$ref "#/components/schemas/Item"}}}}}}
                   }
        form (gen/generate-test-form {} 'handler 'inputs :get "/items" operation)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (form-contains? form 'clojure.core/every?))
    (is (form-contains? form 'ring.mock.request/query-string))))

(deftest generate-test-form-object-response-test
  (let [operation {:responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form {} 'handler 'inputs :get "/status" operation)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (not (form-contains? form 'clojure.core/every?)))))

(deftest generate-test-form-header-params-test
  (let [operation {:parameters [{:in "header" :name "Authorization"}
                                {:in "header" :name "X-Request-Id"}]
                   :responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form {} 'handler 'inputs :get "/secure" operation)]
    (is (form-contains? form 'ring.mock.request/header))
    (is (form-contains? form 'clojure.core/reduce))))

(deftest apply-security-basic-auth-test
  (let [request (gen/apply-security
                 (mock/request :get "/secure")
                 [{:scheme-name "basicAuth"
                   :type "http"
                   :scheme "basic"}]
                 {:basicAuth "dXNlcjpwYXNz"})]
    (is (= "Basic dXNlcjpwYXNz"
           (get-in request [:headers "authorization"])))))

(deftest apply-security-missing-credentials-test
  (let [request (gen/apply-security
                 (mock/request :get "/secure")
                 [{:scheme-name "basicAuth"
                   :type "http"
                   :scheme "basic"}]
                 {})]
    (is (nil? (get-in request [:headers "authorization"])))))

(deftest apply-security-unsupported-and-non-header-apikey-test
  (let [request (gen/apply-security
                 (mock/request :get "/secure")
                 [{:scheme-name "queryKey"
                   :type "apiKey"
                   :in "query"
                   :name "api_key"}
                  {:scheme-name "oauth"
                   :type "oauth2"}]
                 {:queryKey "query-token"
                  :oauth "oauth-token"})]
    (is (nil? (get-in request [:headers "api_key"])))
    (is (= {"host" "localhost"} (:headers request)))))

(deftest apply-security-header-apikey-test
  (let [request (gen/apply-security
                 (mock/request :get "/secure")
                 [{:scheme-name "profileAuth"
                   :type "apiKey"
                   :in "header"
                   :name "X-Profile"}]
                 {:profileAuth "admin"})]
    (is (= "admin" (get-in request [:headers "x-profile"])))))

(deftest operation-security-schemes-top-level-fallback-test
  (let [spec {:security [{:basicAuth []}]
              :components {:securitySchemes
                           {:basicAuth {:type "http"
                                        :scheme "basic"}}}}
        schemes (#'gen/operation-security-schemes spec {})]
    (is (= [{:scheme-name "basicAuth"
             :type "http"
             :scheme "basic"}]
           schemes))))

(deftest generate-test-form-applies-top-level-security-test
  (let [spec {:security [{:basicAuth []}]
              :components {:securitySchemes
                           {:basicAuth {:type "http"
                                        :scheme "basic"}}}}
        operation {:responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form spec 'handler 'inputs :get "/secure" operation)]
    (is (form-contains? form 'cot.generator/apply-security))
    (is (form-contains? form "basicAuth"))))
