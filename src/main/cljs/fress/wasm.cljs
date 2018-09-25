(ns fress.wasm
  (:require [fress.reader :as r]
            [fress.writer :as w]
            [fress.impl.codes :as codes]
            [fress.impl.buffer :as buf]
            [fress.impl.raw-input :as rawIn]
            [fress.util :as util :refer [log]]))

(defprotocol IFressWasmModule
  (get-view [Mod])
  (get-memory [Mod])
  (get-exports [Mod])
  (get-imports [Mod])
  (alloc [Mod len]) ;=> ptr
  (dealloc
    [Mod fptr]
    [Mod ptr len])
  (copy-bytes [Mod ptr len])
  (write-bytes [Mod bytes])
  (read
    [Mod ptr]
    [Mod ptr opts])
  (write
    [Mod any]
    [Mod any opts])
  (call
    [Mod export-name]
    [Mod export-name obj]
    [Mod export-name obj opts]))

(deftype FatPtr [ptr len])

(defn- module-read ;=> [?err ?ok]
  "Given a WASM module, a pointer, and opts, read off a fressian object and
   automatically free the used memory. Call this synchronously after
   obtaining the ptr and before any other calls on the same module/memory
     + currently opts is :handlers only
       - no :checksum? or :name->map-ctor (not in rust yet)
   => [?err ?[result..]]"
  [Mod ptr {:keys [handlers]}]
  (assert (util/valid-pointer? ptr) (str "wasm/read given invalid pointer : '" (pr-str ptr) "'"))
  (let [memory (or (.. Mod -exports -memory) (.. Mod -imports -memory))
        _(assert (some? memory))
        view (js/Uint8Array. (.. Mod -exports -memory -buffer))
        rdr (r/reader view :offset ptr :handlers handlers)
        ret (if (== codes/ERROR (aget view ptr))
              [(r/readObject rdr)]
              [nil (r/readObject rdr)])
        bytes_read (rawIn/getBytesRead (get rdr :raw-in))]
    ((.. Mod -exports -fress_dealloc) ptr bytes_read)
    ret))

(defonce ^:private _buffer (buf/with_capacity 128))

(defn- module-write ;=> FatPtr
  "Given a WASM module, object, and opts, write the object into wasm memory
   and return a tuple containing a pointer and the length of the bytes written.
   The pointer+length should be given synchronously to an exported wasm
   function to claim and deserialize fressian data.
     + opts
       - :handlers {type write-handler}
       - :stringify-keys bool
       - no :record->name, :checksum?
   => FatPtr<pointer, length>"
  [Mod obj {:keys [handlers stringify-keys]}]
  (let [writer (w/writer _buffer :handlers handlers)
        _ (binding [w/*write-raw-utf8* false
                    w/*stringify-keys* (or ^boolean w/*stringify-keys* ^boolean stringify-keys)]
            (w/writeObject writer obj))
        byte-length (buf/getBytesWritten _buffer)
        ptr ((.. Mod -exports -fress_alloc) byte-length)
        view (js/Uint8Array. (.. Mod -exports -memory -buffer))]
    (buf/flushTo _buffer view ptr)
    (buf/reset _buffer)
    (FatPtr. ptr byte-length)))

(defn module-write-bytes ;=> FatPtr
  "Given a WASM module and a byte array, write the bytes into memory and return
   a tuple containing a pointer and the length of the bytes written. The
   pointer+length should be given synchronously to an exported wasm function to
   claim and deserialize fressian data.
   => FatPtr<pointer, length>"
  [Mod bytes]
  (assert (goog.isArrayLike bytes))
  (let [ptr ((.. Mod -exports -fress_alloc) (alength bytes))
        view (js/Uint8Array. (.. Mod -exports -memory -buffer))]
    (.set view bytes ptr)
    (FatPtr. ptr (alength bytes))))

;; should we carry state on local module via bindings?
(defonce ^:private _panic_ptr (atom nil))
(defonce ^:private _call_sentinel #js{})

(defn module-call ;=> [err], [nil], [nil ok]
  ""
  ([Mod export-name]
   (module-call Mod export-name _call_sentinel nil))
  ([Mod export-name obj]
    (module-call Mod export-name obj nil))
  ([Mod export-name obj opts] ;=> need to distinguish read and write opts
   (assert (string? export-name))
   (let [f (goog.object.get (.-exports Mod) export-name nil)]
     (if (some? f)
       (try
         (if (identical? obj _call_sentinel)
           (if-let [ptr (f)]
             (module-read Mod ptr nil)
             [nil])
           (let [fptr (if (instance? FatPtr obj)
                        obj
                        (module-write Mod obj opts))]
             (assert (instance? FatPtr fptr))
             (if-let [ptr (f (.-ptr fptr) (.-len fptr))]
               (module-read Mod ptr opts)
               [nil])))
         (catch js/Error e ;=> wasm runtime-error
           (let [ptr @_panic_ptr
                 _ (reset! _panic_ptr nil)
                 [err panic :as res] (module-read Mod ptr nil)]
             (if err
               res
               [{:type :panic :msg panic}]))))
       (throw (js/Error. (str "missing exported fn '" export-name "'")))))))

(defn attach-protocol! [Mod]
  (let []
    (specify! Mod
      IFressWasmModule
      (get-imports [_] (.-imports Mod))
      (get-exports [_] (.-exports Mod))
      (get-memory [_]
        (or (.. Mod -exports -memory) (.. Mod -imports -env -memory)))
      (get-view [this]
        (js/Uint8Array. (.-buffer (get-memory this))))
      (alloc [_ byte-length]
        ((.. Mod -exports -fress_alloc) byte-length)) ;=> ptr
      (dealloc
       ([Mod fptr]
        (assert (instance? FatPtr fptr) "fress.wasm/dealloc arity-2 requires a FatPtr")
        ((.. Mod -exports -fress_dealloc) (.-ptr fptr) (.-len fptr)))
       ([Mod ptr len]
         (assert (util/valid-pointer? ptr) (str "dealloc given invalid pointer : '" (pr-str ptr) "'"))
         (assert (and (number? len) (int? len) (<= 0 len)) (str "dealloc given bad length: '" (pr-str len) "'"))
         ((.. Mod -exports -fress_dealloc) ptr len)))
      (copy-bytes [this ptr len] ;=> u8-array
        (assert (util/valid-pointer? ptr) (str "copy-bytes given invalid pointer : '" (pr-str ptr) "'"))
        (assert (and (number? len) (int? len) (<= 0 len)) (str "copy-bytes given bad length: '" (pr-str len) "'"))
        (.slice (get-view this) ptr (+ ptr len)))
      (read ;=> [?err ?ok]
       ([this ptr] (module-read this ptr nil))
       ([this ptr opts] (module-read this ptr opts)))
      (write ;=> FatPtr
        ([this any] (module-write this any nil))
        ([this any opts] (module-write this any opts)))
      (write-bytes [this bytes] ;=> FatPtr
        (module-write-bytes this bytes))
      (call ;=> [?err ?ok]
       ([Mod export-name] (module-call Mod export-name))
       ([Mod export-name obj] (module-call Mod export-name obj))
       ([Mod export-name obj opts] (module-call Mod export-name obj opts))))))

(defn assert-fress-mod! [Mod]
  (assert (instance? js/WebAssembly.Instance Mod))
  (assert (some? (.. Mod -exports -fress_init)))
  (assert (some? (.. Mod -exports -fress_alloc)))
  (assert (some? (.. Mod -exports -fress_dealloc)))
  (assert (some? (.. Mod -exports -memory))))

(defn panic-hook [ptr] (reset! _panic_ptr ptr))

(defn instantiate
  "instantiates wasm module and adds IFressWasmModule"
  ([array-buffer] (instantiate array-buffer #js{"env" #js{"js_panic_hook" panic-hook}})) ;panic hook!
  ([array-buffer importOptions]
   (js/Promise.
    (fn [_resolve reject]
      (.then (js/WebAssembly.instantiate array-buffer importOptions)
        (fn [module]
          (try
            (assert-fress-mod! (.-instance module))
            (let [Mod (attach-protocol! (.-instance module))]
              ((.. Mod -exports -fress_init))
              (_resolve Mod))
            (catch js/Error e
              (reject e))))
        (fn [reason] (reject reason))))))) ;=>compile-error