(ns io.pedestal.http.tomcat.container
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (javax.servlet.AsyncContext)
           (javax.servlet.http HttpServletRequest)
           (org.apache.catalina.connector CoyoteOutputStream)))

(extend-protocol container/WriteNIOByteBody
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [servlet-req ^HttpServletRequest (:servlet-request context)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^CoyoteOutputStream (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                ;; needs impl
                                )
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)
                                (.complete ac))))))

  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [servlet-req ^HttpServletRequest (:servlet-request context)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^CoyoteOutputStream (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (when (.isReady os)
                                  (.write os body)
                                  (async/put! resume-chan context)
                                  (async/close! resume-chan)
                                  (.complete ac)))
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)
                                (.complete ac)))))))
