package com.jurislpo.juristrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request body larger than {@value #MAXIMUM_BODY_BYTES} bytes with 413 {@code PAYLOAD_TOO_LARGE}, before anything reads it
 * (the sign-in endpoints are open to anybody, and JSON bodies are read whole). A body that announces its size is refused at once; one
 * without a {@code Content-Length} (chunked) fails when it grows past the limit while it is read. Only the roster sync (JurisOne's service token)
 * takes a larger body, {@value #MAXIMUM_ROSTER_BODY_BYTES} bytes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAXIMUM_BODY_BYTES = 64 * 1024;
    /** The roster sync takes up to 5000 employees with their schedules; it needs JurisOne's service token, which is checked before the body is read. */
    public static final int MAXIMUM_ROSTER_BODY_BYTES = 16 * 1024 * 1024;
    private static final String MESSAGE = "The request is too large.";

    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Thrown while a body is read when it grows past the limit; the exception handler answers it as 413. */
    public static class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super(MESSAGE);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        int maximumBytes = RequestAuthenticationFilter.isRosterSyncRequest(request) ? MAXIMUM_ROSTER_BODY_BYTES : MAXIMUM_BODY_BYTES;
        if (request.getContentLengthLong() > maximumBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, Problems.PAYLOAD_TOO_LARGE, MESSAGE,
                    request.getRequestURI()));
            return;
        }
        chain.doFilter(request.getContentLengthLong() < 0 ? new LimitedRequest(request, maximumBytes) : request, response);
    }

    /** Counts the bytes read from a body of unknown length. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int maximumBytes;
        private ServletInputStream limitedStream;

        LimitedRequest(HttpServletRequest request, int maximumBytes) {
            super(request);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedStream == null) limitedStream = new LimitedStream(super.getInputStream(), maximumBytes);
            return limitedStream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int maximumBytes;
        private long bytesRead;

        LimitedStream(ServletInputStream delegate, int maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        private int counted(int count) throws IOException {
            if (count > 0) {
                bytesRead += count;
                if (bytesRead > maximumBytes) throw new BodyTooLargeException();
            }
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) counted(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return counted(delegate.read(buffer, offset, length));
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
