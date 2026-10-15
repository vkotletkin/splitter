package ru.kotletkin.splitter;

import java.util.Arrays;
import java.util.Objects;

import org.springframework.http.HttpHeaders;

/**
 * Разобранный HTTP-запрос: метод, target, версия, заголовки и тело.
 * <p>
 * Тело копируется на входе и на выходе, заголовки только для чтения.
 * Сравнение идёт по содержимому.
 */
public record ParsedHttpRequest(
        String method,
        String target,
        String version,
        HttpHeaders headers,
        byte[] body) {

    public ParsedHttpRequest {
        method = Objects.requireNonNull(method, "method");
        target = Objects.requireNonNull(target, "target");
        version = Objects.requireNonNull(version, "version");
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(Objects.requireNonNull(headers, "headers"));
        headers = HttpHeaders.readOnlyHttpHeaders(copy);
        body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ParsedHttpRequest(
                String otherMethod,
                String otherTarget,
                String otherVersion,
                HttpHeaders otherHeaders,
                byte[] otherBody))) {
            return false;
        }
        return method.equals(otherMethod)
                && target.equals(otherTarget)
                && version.equals(otherVersion)
                && headers.equals(otherHeaders)
                && Arrays.equals(body, otherBody);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, target, version, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "ParsedHttpRequest[method=" + method
                + ", target=" + target
                + ", version=" + version
                + ", headers=" + headers
                + ", bodyLength=" + body.length + "]";
    }

}
