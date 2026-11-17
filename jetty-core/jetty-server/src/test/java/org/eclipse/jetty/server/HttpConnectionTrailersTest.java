//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * <p>Tests that the per-request state kept by {@code HttpConnection}, in particular
 * the request trailers, does not leak from one request to the next request that is
 * carried by the same persistent (keep-alive) HTTP/1.1 connection.</p>
 */
public class HttpConnectionTrailersTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.setIdleTimeout(60000);
        _server.addConnector(_connector);
        _server.setHandler(new TrailersEchoHandler());
        _server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (_server != null)
            _server.stop();
        _server = null;
        _connector = null;
    }

    /**
     * A second request without trailers, sent on the same connection as a first
     * request that carried trailers, must not see the trailers of the first request.
     */
    @Test
    public void testTrailersDoNotLeakToNextRequestWithoutTrailers() throws Exception
    {
        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                POST /one HTTP/1.1\r
                Host: localhost\r
                Trailer: Secret-Trailer\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello1\r
                0\r
                Secret-Trailer: secret-value\r
                \r
                """);

            String response1 = endPoint.getResponse();
            assertThat(response1, notNullValue());
            assertThat(response1, containsString("HTTP/1.1 200"));
            assertThat(response1, containsString("content=hello1"));
            // The first request really did carry the trailer.
            assertThat(response1, containsString("trailers=[secret-trailer=secret-value]"));

            // Second request on the same connection, with no trailers at all.
            endPoint.addInput("""
                POST /two HTTP/1.1\r
                Host: localhost\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello2\r
                0\r
                \r
                """);

            String response2 = endPoint.getResponse();
            assertThat(response2, notNullValue());
            assertThat(response2, containsString("HTTP/1.1 200"));
            assertThat(response2, containsString("content=hello2"));
            // The trailers of the previous request must not be visible here.
            assertThat(response2, containsString("trailers=[]"));
            assertThat(response2, not(containsString("secret-trailer")));
            assertThat(response2, not(containsString("secret-value")));
        }
    }

    /**
     * A second request that carries its own trailers, sent on the same connection as
     * a first request that carried trailers, must only see its own trailers.
     */
    @Test
    public void testTrailersDoNotLeakToNextRequestWithOwnTrailers() throws Exception
    {
        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                POST /one HTTP/1.1\r
                Host: localhost\r
                Trailer: Secret-Trailer\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello1\r
                0\r
                Secret-Trailer: secret-value\r
                \r
                """);

            String response1 = endPoint.getResponse();
            assertThat(response1, notNullValue());
            assertThat(response1, containsString("trailers=[secret-trailer=secret-value]"));

            endPoint.addInput("""
                POST /two HTTP/1.1\r
                Host: localhost\r
                Trailer: Own-Trailer\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello2\r
                0\r
                Own-Trailer: own-value\r
                \r
                """);

            String response2 = endPoint.getResponse();
            assertThat(response2, notNullValue());
            assertThat(response2, containsString("HTTP/1.1 200"));
            assertThat(response2, containsString("content=hello2"));
            // Only its own trailer, not the one of the previous request.
            assertThat(response2, containsString("trailers=[own-trailer=own-value]"));
            assertThat(response2, not(containsString("secret-trailer")));
            assertThat(response2, not(containsString("secret-value")));
        }
    }

    /**
     * Same leak, but with the requests pipelined: the trailers of the first request
     * must not be reported by the two following requests.
     */
    @Test
    public void testTrailersDoNotLeakToPipelinedRequests() throws Exception
    {
        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                POST /one HTTP/1.1\r
                Host: localhost\r
                Trailer: Secret-Trailer\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello1\r
                0\r
                Secret-Trailer: secret-value\r
                \r
                POST /two HTTP/1.1\r
                Host: localhost\r
                Transfer-Encoding: chunked\r
                \r
                6\r
                hello2\r
                0\r
                \r
                POST /three HTTP/1.1\r
                Host: localhost\r
                Content-Length: 6\r
                \r
                hello3""");

            String response1 = endPoint.getResponse();
            assertThat(response1, notNullValue());
            assertThat(response1, containsString("trailers=[secret-trailer=secret-value]"));

            String response2 = endPoint.getResponse();
            assertThat(response2, notNullValue());
            assertThat(response2, containsString("content=hello2"));
            assertThat(response2, containsString("trailers=[]"));
            assertThat(response2, not(containsString("secret")));

            String response3 = endPoint.getResponse();
            assertThat(response3, notNullValue());
            assertThat(response3, containsString("content=hello3"));
            assertThat(response3, containsString("trailers=[]"));
            assertThat(response3, not(containsString("secret")));
        }
    }

    private static class TrailersEchoHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // The content must be fully consumed before the trailers are available.
            String content = Content.Source.asString(request);
            HttpFields trailers = request.getTrailers();
            String reported = trailers == null ? "" : trailers.stream()
                .map(field -> field.getLowerCaseName() + "=" + field.getValue())
                .sorted()
                .collect(Collectors.joining(","));
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
            Content.Sink.write(response, true, "content=%s;trailers=[%s]".formatted(content, reported), callback);
            return true;
        }
    }
}
