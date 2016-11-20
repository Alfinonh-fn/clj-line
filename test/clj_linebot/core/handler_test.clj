(ns clj-linebot.core.handler-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [clj-linebot.core.handler :as sut :refer :all]
            [clj-http.client :as cli]))

(deftest test-evaluation
  (testing "authorized expression"
    (let [expr  "(+ 1 2)"
          result (eval-expr expr)]
      (is (= {:status true
              :input expr
              :form '(+ 1 2)
              :result 3
              :output ""}
             result))))

  (testing "unauthorized evaluation"
    (let [expr  "(ring.adapter.jetty/run-jetty app {:port 9999})"
          result (eval-expr expr)]
      (is (= {:status false
              :input expr
              :result "You tripped the alarm! ring.adapter.jetty is bad!"}
             result)))))

(deftest test-app
  (with-redefs [sut/post-url "http://example.com"
                sut/command-token "ABCdefGHIjklMNO"
                cli/post (fn [url {{:keys [channel text]} :form-params}] [channel text])]

    (testing "request without token"
      (let [response (app (mock/request :post "/clj" {:text "(+ 1 2)" :channel_name "general" :user_name "john"}))]
        (is (= (:status response) 403))))

    (testing "request with wrong token"
      (let [response (app (mock/request :post "/clj" {:token "SEkR3T" :text "(+ 1 2)" :channel_name "general" :user_name "john"}))]
        (is (= (:status response) 403))))

    (testing "request with valid token"
      (let [response (app (mock/request :post "/clj" {:token "ABCdefGHIjklMNO" :text "(+ 1 2)" :channel_name "general" :user_name "john"}))]
        (is (= (:status response) 200))))

    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= (:status response) 404))))))
