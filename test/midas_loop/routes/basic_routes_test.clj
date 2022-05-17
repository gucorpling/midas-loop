(ns midas-loop.routes.basic-routes-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [midas-loop.server.handler :refer :all]
            [midas-loop.server.middleware :refer [muuntaja-instance]]
            [muuntaja.core :as m]
            [mount.core :as mount]))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'midas-loop.server.config/env
                 #'midas-loop.server.handler/app-routes)
    (f)))

(deftest test-app
  (testing "main route"
    (let [response ((app nil nil) (request :get "/api/swagger.json"))]
      (is (= 200 (:status response)))))

  (testing "not-found route"
    (let [response ((app nil nil) (request :get "/invalid"))]
      (is (= 404 (:status response)))))

  #_(testing "services"
      (testing "success"
        (let [response ((app) (-> (request :post "/api/math/plus")
                                  (json-body {:x 10, :y 6})))]
          (is (= 200 (:status response)))
          (is (= {:total 16} (m/decode-response-body response)))))

      (testing "parameter coercion error"
        (let [response ((app) (-> (request :post "/api/math/plus")
                                  (json-body {:x 10, :y "invalid"})))]
          (is (= 400 (:status response)))))

      (testing "response coercion error"
        (let [response ((app) (-> (request :post "/api/math/plus")
                                  (json-body {:x -10, :y 6})))]
          (is (= 500 (:status response)))))

      (testing "content negotiation"
        (let [response ((app) (-> (request :post "/api/math/plus")
                                  (body (pr-str {:x 10, :y 6}))
                                  (content-type "application/edn")
                                  (header "accept" "application/transit+json")))]
          (is (= 200 (:status response)))
          (is (= {:total 16} (m/decode-response-body response)))))))
