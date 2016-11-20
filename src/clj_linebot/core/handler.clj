(ns clj-linebot.core.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clojail.core :refer [sandbox]]
            [clojail.testers :refer [secure-tester-without-def blacklist-objects blacklist-packages blacklist-symbols blacklist-nses blanket]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-params]]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clj-http.client :as client]
            [clojure.algo.monads]
            [clojure.core.async]
            [clojure.data.json]
            [clojure.math.numeric-tower]
            [clojure.test.check]
            [cemerick.pomegranate])
  (:import java.io.StringWriter
           java.util.concurrent.TimeoutException)
  (:gen-class))

(def insecure-tester
  [(blacklist-objects [])
                      ;  clojure.lang.Compiler clojure.lang.Ref clojure.lang.Reflector
                      ;  clojure.lang.Namespace clojure.lang.Var clojure.lang.RT
                      ;  java.io.ObjectInputStream
   (blacklist-packages [])
                        ; "java.lang.reflect"
                        ; "java.security"
                        ; "java.util.concurrent"
                        ; "java.awt"
   (blacklist-symbols
    '#{})
      ;  alter-var-root intern eval catch
      ;  load-string load-reader addMethod ns-resolve resolve find-var
      ;  *read-eval* ns-publics ns-unmap set! ns-map ns-interns the-ns
      ;  push-thread-bindings pop-thread-bindings future-call agent send
      ;  send-off pmap pcalls pvals in-ns System/out System/in System/err
      ;  with-redefs-fn Class/forName
   (blacklist-nses '[clojure.main])
   (blanket "clojail")])

(def clj-linebot-tester
  (conj insecure-tester (blanket "clj-linebot" "compojure" "ring")))

(def sb (sandbox clj-linebot-tester))

(def post-url
  (:post-url env))

(def channel-secret
  (:channel-secret env))

(def channel-access-token
  (:channel-access-token env))

(defn post-to-line [s reply-token]
  (client/post post-url
               {:content-type :json
                :headers {"Authorization" (str "Bearer " channel-access-token)}
                :form-params {:replyToken reply-token
                              :messages [{:type "text"
                                          :text s}]}}))

(defn eval-expr
  "Evaluate the given string"
  [s]
  (try
    (with-open [out (StringWriter.)]
      (let [form (binding [*read-eval* false] (read-string s))
            result (sb form {#'*out* out})]
        {:status true
         :input s
         :form form
         :result result
         :output (.toString out)}))
    (catch Exception e
      {:status false
       :input s
       :result (.getMessage e)})))

(defn format-result [r]
  (if (:status r)
    (str (:input r) "\n"
         "=> " (:form r) "\n"
         (when-let [o (:output r)]
           o)
         (if (nil? (:result r))
           "nil"
           (prn-str (:result r))))
    (str "==> " (or (:form r) (:input r)) "\n"
         (or (:result r) "Unknown Error"))))

(defn eval-and-post [s reply-token]
  (-> s
      eval-expr
      format-result
      (post-to-line reply-token)))

(defn handle-clj [params]
  (let [event (first (:events params))
        type (:type event)
        reply-token (:replyToken event)
        text (get-in event [:message :text])]
    (when (= type "message")
      (eval-and-post text reply-token))
    {:status 200
     :body ""
     :headers {"Content-Type" "text/plain"}}))

(defroutes approutes
  (POST "/callback" req (handle-clj (:params req)))
  (route/not-found "Not Found"))

(def app (-> approutes
             (wrap-defaults api-defaults)
             wrap-json-params))

(defn -main [& args]
  (run-jetty app {:port (Integer/parseInt (or (:port env)
                                              "3000"))}))
