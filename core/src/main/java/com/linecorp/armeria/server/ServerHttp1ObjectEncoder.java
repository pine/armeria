/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

final class ServerHttp1ObjectEncoder extends Http1ObjectEncoder implements ServerHttpObjectEncoder {
    private final KeepAliveHandler keepAliveHandler;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;
    private final Http1HeaderNaming http1HeaderNaming;

    private boolean shouldSendConnectionCloseHeader;
    private boolean sentConnectionCloseHeader;

    private int lastResponseHeadersId;

    ServerHttp1ObjectEncoder(Channel ch, SessionProtocol protocol, KeepAliveHandler keepAliveHandler,
                             boolean enableDateHeader, boolean enableServerHeader,
                             Http1HeaderNaming http1HeaderNaming) {
        super(ch, protocol);
        assert keepAliveHandler instanceof Http1ServerKeepAliveHandler ||
               keepAliveHandler instanceof NoopKeepAliveHandler;
        this.keepAliveHandler = keepAliveHandler;
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
        this.http1HeaderNaming = http1HeaderNaming;
    }

    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    public void initiateConnectionShutdown() {
        shouldSendConnectionCloseHeader = true;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                        boolean isTrailersEmpty) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        final HttpResponse converted = convertHeaders(headers, endStream, isTrailersEmpty);
        if (headers.status().isInformational()) {
            return write(id, converted, false);
        }
        lastResponseHeadersId = id;

        if (shouldSendConnectionCloseHeader || keepAliveHandler.needToCloseConnection()) {
            converted.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            sentConnectionCloseHeader = true;
        }
        return writeNonInformationalHeaders(id, converted, endStream);
    }

    private HttpResponse convertHeaders(ResponseHeaders headers, boolean endStream, boolean isTrailersEmpty) {
        final int statusCode = headers.status().code();
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        final HttpResponse res;
        if (headers.status().isInformational()) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            ArmeriaHttpUtil.toNettyHttp1ServerHeaders(headers, res.headers(), http1HeaderNaming);
        } else if (endStream) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
            convertHeaders(headers, outHeaders, isTrailersEmpty);

            if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
                if (statusCode == 304) {
                    // 304 response can have the "content-length" header when it is a response to a conditional
                    // GET request. See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
                } else {
                    outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
                }
            } else if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                // NB: Set the 'content-length' only when not set rather than always setting to 0.
                //     It's because a response to a HEAD request can have empty content while having
                //     non-zero 'content-length' header.
                //     However, this also opens the possibility of sending a non-zero 'content-length'
                //     header even when it really has to be zero. e.g. a response to a non-HEAD request
                outHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
        } else {
            res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, false);
            convertHeaders(headers, res.headers(), isTrailersEmpty);
            maybeSetTransferEncoding(res);
        }

        return res;
    }

    private void convertHeaders(HttpHeaders inHeaders, io.netty.handler.codec.http.HttpHeaders outHeaders,
                                boolean isTrailersEmpty) {
        ArmeriaHttpUtil.toNettyHttp1ServerHeaders(inHeaders, outHeaders, http1HeaderNaming);

        if (!isTrailersEmpty && outHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We don't apply chunked encoding when the content-length header is set, which would
            // prevent the trailers from being sent so we go ahead and remove content-length to
            // force chunked encoding.
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }

        if (enableServerHeader && !outHeaders.contains(HttpHeaderNames.SERVER)) {
            outHeaders.add(HttpHeaderNames.SERVER, ArmeriaHttpUtil.SERVER_HEADER);
        }

        if (enableDateHeader && !outHeaders.contains(HttpHeaderNames.DATE)) {
            outHeaders.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
        }
    }

    private static void maybeSetTransferEncoding(HttpMessage out) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        final long contentLength = HttpUtil.getContentLength(out, -1L);
        if (contentLength < 0) {
            // Use chunked encoding.
            outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    @Override
    protected void convertTrailers(HttpHeaders inputHeaders,
                                   io.netty.handler.codec.http.HttpHeaders outputHeaders) {
        ArmeriaHttpUtil.toNettyHttp1ServerTrailers(inputHeaders, outputHeaders, http1HeaderNaming);
    }

    @Override
    protected boolean isPing(int id) {
        return false;
    }

    boolean isSentConnectionCloseHeader() {
        return sentConnectionCloseHeader;
    }

    @Override
    public boolean isResponseHeadersSent(int id, int streamId) {
        return id <= lastResponseHeadersId;
    }

    @Override
    public ChannelFuture writeErrorResponse(int id, int streamId,
                                            ServiceConfig serviceConfig,
                                            RequestHeaders headers,
                                            HttpStatus status,
                                            @Nullable String message,
                                            @Nullable Throwable cause) {

        // Destroy keepAlive handler before writing headers so that ServerHttp1ObjectEncoder sets
        // "Connection: close" to the response headers
        keepAliveHandler().destroy();

        final ChannelFuture future = ServerHttpObjectEncoder.super.writeErrorResponse(
                id, streamId, serviceConfig, headers, status, message, cause);
        // Update the closed ID to prevent the HttpResponseSubscriber from
        // writing additional headers or messages.
        updateClosedId(id);

        return future.addListener(ChannelFutureListener.CLOSE);
    }
}
