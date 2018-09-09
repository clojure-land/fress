(ns fress.wasm-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [fress.test-macros :as tmac])
  (:require [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cljs.test :refer-macros [deftest is testing async are run-tests] :as test]
            [cargo.cargo :as cargo]
            [fress.api :as api]
            [fress.wasm :as wasm-api]
            [fress.util :as util :refer [expected byte-array log]]))

(def path (js/require "path"))

(def cfg
  {:project-name "wasm-test"
   :dir (path.join (tmac/root) "resources" "wasm-test")
   :target :wasm
   :release? true
   :rustflags {:allow [
                       :non_snake_case
                       :unused_parens
                       :unused_variables

                       :unused_imports
                       :dead_code

                       :non_camel_case_types
                       ]}})

(defonce module (atom nil))

(defn build []
  (js/console.clear)
  (take! (cargo/build! cfg)
    (fn [[e buffer]]
      (if e
        (cargo/report-error e)
        (let [importOptions #js{}]
          (take! (cargo/init-module buffer importOptions)
            (fn [[e compiled]]
              (if e
                (cargo/report-error e)
                (do
                  (reset! module (.. compiled -instance)))))))))))

(declare mod-tests)

(deftest wasm-test
  (async done
    (set! cargo/*verbose* false)
    (take! (cargo/build! cfg)
      (fn [[e buffer]]
        (if-not (is (and (nil? e) (instance? js/Buffer buffer)))
          (done)
          (take! (cargo/init-module buffer #js{})
            (fn [[e compiled]]
              (if-not (is (and (nil? e) (instance? js/WebAssembly.Instance (.. compiled -instance))))
                (done)
                (do
                  (mod-tests (reset! module (.. compiled -instance)))
                  (set! cargo/*verbose* true)
                  (done))))))))))

(defn echo
  ([](echo "hello from javascript"))
  ([any]
   (if-let [Mod @module]
     (let [bytes (api/write any)
           write-ptr (wasm-api/write-bytes Mod bytes)
           read-ptr ((.. Mod -exports -echo) write-ptr (alength bytes))]
       (wasm-api/read-all Mod read-ptr))
     (throw (js/Error "missing module")))))

(defn get-err
  ([]
   (if-let [Mod @module]
     (let [read-ptr ((.. Mod -exports -get_err))]
       (wasm-api/read-all Mod read-ptr))
     (throw (js/Error "missing module")))))


(defn mod-tests [Mod])



; :stringify-keys!