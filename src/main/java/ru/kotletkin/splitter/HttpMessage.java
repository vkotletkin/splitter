package ru.kotletkin.splitter;

import java.util.Arrays;
import java.util.Objects;

/**
 * Один HTTP-запрос.
 * <p>
 * {@code headers} — строка запроса и заголовки без разделяющей пустой строки.
 * {@code body} — тело. Если внутри объявленного Content-Length был следующий
 * запрос, тело обрезано до него, а Content-Length в заголовках приведён к фактической длине.
 * Массивы копируются на входе и на выходе, сравнение идёт по содержимому.
 */
public record HttpMessage(byte[] headers, byte[] body) {

    public HttpMessage {
        headers = Arrays.copyOf(Objects.requireNonNull(headers, "headers"), headers.length);
        body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
    }

    @Override
    public byte[] headers() {
        return Arrays.copyOf(headers, headers.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HttpMessage(byte[] otherHeaders, byte[] otherBody))) {
            return false;
        }
        return Arrays.equals(headers, otherHeaders) && Arrays.equals(body, otherBody);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(headers), Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpMessage[headers=" + Arrays.toString(headers) + ", body=" + Arrays.toString(body) + "]";
    }
}
