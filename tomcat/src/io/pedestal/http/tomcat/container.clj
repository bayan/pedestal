(ns io.pedestal.http.tomcat.container
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (javax.servlet AsyncContext ServletOutputStream)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

(extend-protocol container/WriteNIOByteBody
  HttpServletResponse
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [servlet-req ^HttpServletRequest (:servlet-request context)
          _ (.setAttribute servlet-req "org.apache.catalina.ASYNC_SUPPORTED" true)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^ServletOutputStream (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                ;; needs impl
                                )
                              (onError [this throwable]
                                (prn ::write-byte-buffer-body "onError" (.getMessage throwable))
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
                                (prn ::write-byte-buffer-body "onError" (.getMessage throwable))
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)
                                (.complete ac)))))))
