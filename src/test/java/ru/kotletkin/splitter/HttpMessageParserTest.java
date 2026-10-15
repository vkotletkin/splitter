package ru.kotletkin.splitter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpMessageParserTest {

    @Test
    void parsesExampleRequests() throws IOException {
        List<HttpMessage> messages = HttpStreamSplitter.split(Files.readAllBytes(Path.of("data/example.http")));

        assertEquals(45, messages.size());
        for (HttpMessage message : messages) {
            ParsedHttpRequest request = HttpMessageParser.parse(message);
            assertTrue(text(message.headers()).startsWith(requestLine(request)));
            assertEquals("test.ru", request.headers().getFirst("Host"));
            assertArrayEquals(message.body(), request.body());
        }

        ParsedHttpRequest users = HttpMessageParser.parse(messages.get(0));
        assertEquals("GET", users.method());
        assertEquals("http://test.ru/api/v1/users?page=1&limit=20", users.target());
        assertEquals("HTTP/1.1", users.version());
        assertEquals(0, users.body().length);

        ParsedHttpRequest login = HttpMessageParser.parse(messages.get(1));
        assertEquals("POST", login.method());
        assertEquals("application/json; charset=utf-8", login.headers().getFirst("Content-Type"));
        assertEquals(
                "{\"login\":\"ivan.petrov\",\"password\":\"Qwerty123!\",\"remember\":true}",
                text(login.body()));

        ParsedHttpRequest document = messages.stream()
                .map(HttpMessageParser::parse)
                .filter(request -> request.target().equals("http://test.ru/api/v1/documents/upload"))
                .findFirst()
                .orElseThrow();
        assertEquals("Договор поставки №15", document.headers().getFirst("X-Document-Title"));
    }

    @Test
    void parsesBinaryPayloads() throws IOException {
        List<String> spec = Files.readAllLines(Path.of("data/binaries/expected.tsv"));
        List<HttpMessage> messages = HttpStreamSplitter.split(Files.readAllBytes(Path.of("data/binary-stream.http")));

        assertEquals(spec.size(), messages.size());
        for (int index = 0; index < spec.size(); index++) {
            String requestLine = spec.get(index).split("\t", 3)[0];
            ParsedHttpRequest request = HttpMessageParser.parse(messages.get(index));
            assertEquals(requestLine, requestLine(request));
            assertArrayEquals(messages.get(index).body(), request.body(), requestLine);
        }
    }

    @Test
    void readsChunkedBody() {
        byte[] headers = """
                POST /chunk HTTP/1.1\r
                Host: test.ru\r
                Transfer-Encoding: chunked""".getBytes(StandardCharsets.US_ASCII);
        byte[] body = "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest request = HttpMessageParser.parse(new HttpMessage(headers, body));

        assertEquals("POST", request.method());
        assertEquals("/chunk", request.target());
        assertEquals("hello", text(request.body()));
    }

    private static String requestLine(ParsedHttpRequest request) {
        return request.method() + " " + request.target() + " " + request.version();
    }

    private static String text(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

}
