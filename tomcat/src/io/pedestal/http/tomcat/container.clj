; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.tomcat.container
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (javax.servlet AsyncContext ServletOutputStream)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

(defn write-channel
  [^ServletOutputStream os ^ReadableByteChannel body ^ByteBuffer buffer]
  (loop []
    (let [c (.read body buffer)]
      (if (< 0 c)
        (do
          (.write os (.array buffer) 0 c)
          (.clear buffer)
          (recur))))))

(extend-protocol container/WriteNIOByteBody
  HttpServletResponse
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [servlet-req ^HttpServletRequest (:servlet-request context)
          _ (.setAttribute servlet-req "org.apache.catalina.ASYNC_SUPPORTED" true)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^ServletOutputStream (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (write-channel os body (ByteBuffer/allocate (.getBufferSize servlet-response)))
                                (.close body)
                                (async/put! resume-chan context)
                                (async/close! resume-chan))
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)
                                (.complete ac))))))

  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [servlet-req ^HttpServletRequest (:servlet-request context)
          _ (.setAttribute servlet-req "org.apache.catalina.ASYNC_SUPPORTED" true)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^ServletOutputStream (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (when (.isReady os)
                                  (.write os (.array body))
                                  (async/put! resume-chan context)
                                  (async/close! resume-chan)
                                  #_(.complete ac)))  ;; Calling [asyncComplete()] is not valid for a request with Async state [DISPATCHED]
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)
                                (.complete ac)))))))
