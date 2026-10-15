package ru.kotletkin.splitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentLengthStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.impl.io.ChunkedInputStream;
import org.apache.hc.core5.http.impl.io.ContentLengthInputStream;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestParser;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.springframework.http.HttpHeaders;

/**
 * Разбирает одно уже отрезанное HTTP-сообщение библиотекой Apache HttpCore.
 * <p>
 * Стартовая строка, заголовки и границы тела (Content-Length или chunked)
 * читает HttpCore. Склейку соседних запросов по-прежнему делает
 * {@link HttpStreamSplitter}: HttpCore верит объявленной длине.
 */
public final class HttpMessageParser {

    private static final int BUFFER_SIZE = 8192;

    private static final byte[] LF_SEPARATOR = "\n\n".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] CRLF_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final String PARSE_FAILED = "Не удалось разобрать HTTP-запрос";

    private HttpMessageParser() {
    }

    public static ParsedHttpRequest parse(HttpMessage message) {
        ByteArrayInputStream input = new ByteArrayInputStream(rawMessage(message));
        SessionInputBufferImpl buffer = new SessionInputBufferImpl(BUFFER_SIZE, StandardCharsets.UTF_8.newDecoder());
        try {
            ClassicHttpRequest request = new DefaultHttpRequestParser().parse(buffer, input);
            if (request == null) {
                throw new IllegalArgumentException(PARSE_FAILED);
            }
            HttpHeaders headers = new HttpHeaders();
            for (Header header : request.getHeaders()) {
                headers.add(header.getName(), header.getValue() == null ? "" : header.getValue());
            }
            return new ParsedHttpRequest(
                    request.getMethod(),
                    target(request),
                    request.getVersion().toString(),
                    headers,
                    readBody(request, buffer, input));
        }
        catch (HttpException | IOException exception) {
            throw new IllegalArgumentException(PARSE_FAILED, exception);
        }
    }

    /**
     * HttpCore по умолчанию отдаёт origin-form ({@code /path?query}), даже если
     * в стартовой строке был absolute-form. Флаг собирает target обратно.
     */
    private static String target(ClassicHttpRequest request) {
        if (request instanceof BasicHttpRequest basic) {
            basic.setAbsoluteRequestUri(true);
        }
        return request.getRequestUri();
    }

    private static byte[] readBody(ClassicHttpRequest request, SessionInputBufferImpl buffer, InputStream input)
            throws HttpException, IOException {
        long length = DefaultContentLengthStrategy.INSTANCE.determineLength(request);
        if (length == ContentLengthStrategy.UNDEFINED) {
            return new byte[0];
        }
        if (length == ContentLengthStrategy.CHUNKED) {
            return new ChunkedInputStream(buffer, input).readAllBytes();
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Тело слишком большое");
        }
        byte[] body = new ContentLengthInputStream(buffer, input, length).readAllBytes();
        if (body.length != length) {
            throw new IllegalArgumentException("Тело короче Content-Length");
        }
        return body;
    }

    private static byte[] rawMessage(HttpMessage message) {
        byte[] headers = message.headers();
        byte[] body = message.body();
        byte[] separator = usesCarriageReturn(headers) ? CRLF_SEPARATOR : LF_SEPARATOR;
        byte[] raw = new byte[headers.length + separator.length + body.length];
        System.arraycopy(headers, 0, raw, 0, headers.length);
        System.arraycopy(separator, 0, raw, headers.length, separator.length);
        System.arraycopy(body, 0, raw, headers.length + separator.length, body.length);
        return raw;
    }

    private static boolean usesCarriageReturn(byte[] headers) {
        for (int index = 0; index < headers.length - 1; index++) {
            if (headers[index] == '\r' && headers[index + 1] == '\n') {
                return true;
            }
        }
        return false;
    }

}
