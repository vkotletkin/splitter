package ru.kotletkin.splitter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class HttpStreamSplitterTest {

    private static final Pattern REQUEST_LINE = Pattern.compile(
            "(?m)^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT) \\S+ HTTP/1\\.[01]\\r?$");

    private static final Pattern CONTENT_LENGTH = Pattern.compile(
            "(?im)^Content-Length:[ \\t]*(\\d+)[ \\t]*\\r?$");

    private static final Path BINARIES = Path.of("data/binaries");

    private static final String CLIENT_MENTION = "PUT /client/15";

    private static final String ARCHIVE_MENTION = "POST /archive/contracts";

    @Test
    void splitsExampleFileIntoSeparateRequests() throws IOException {
        byte[] data = Files.readAllBytes(Path.of("data/example.http"));

        List<HttpMessage> messages = HttpStreamSplitter.split(data);

        assertEquals(45, messages.size());
        assertEquals(45, messages.stream().map(this::requestLine).distinct().count());
        assertEquals(15, messages.stream().filter(message -> requestLine(message).startsWith("PUT /client/")).count());

        assertEquals("GET http://test.ru/api/v1/users?page=1&limit=20 HTTP/1.1", requestLine(messages.get(0)));
        assertEquals(0, messages.get(0).body().length);
        assertEquals(
                "{\"login\":\"ivan.petrov\",\"password\":\"Qwerty123!\",\"remember\":true}",
                bodyText(messages.get(1)));

        HttpMessage usersGet = find(messages, "GET http://test.ru/api/v1/users/1024 HTTP/1.1");
        assertEquals("PAYLOAD-4", bodyText(usersGet));
        assertEquals("9", contentLength(usersGet));

        HttpMessage client4 = messageAfter(messages, usersGet);
        assertEquals("PUT /client/4 HTTP/1.1", requestLine(client4));
        assertEquals("{\"clientId\":4,\"operation\":\"update\",\"source\":\"embedded\"}", bodyText(client4));

        HttpMessage documentUpload = find(messages, "POST http://test.ru/api/v1/documents/upload HTTP/1.1");
        String document = bodyText(documentUpload);
        assertTrue(document.contains(CLIENT_MENTION));
        assertTrue(document.contains(ARCHIVE_MENTION));
        assertFalse(document.contains("HTTP/1.1"));
        assertEquals("PUT /client/8 HTTP/1.1", requestLine(messageAfter(messages, documentUpload)));

        assertTrue(bodyText(find(messages, "PUT http://test.ru/api/v1/orders/55001/delivery HTTP/1.1"))
                .contains("POST /notifications/send"));
        assertTrue(bodyText(find(messages, "PUT http://test.ru/storage/configs/app.conf HTTP/1.1"))
                .contains("PUT /config/reload"));
        assertTrue(bodyText(find(messages, "PUT http://test.ru/api/v1/articles/310 HTTP/1.1"))
                .contains("PUT /client обновляет клиента"));
        assertEquals(
                "grant_type=refresh_token&refresh_token=def50200a1b2c3d4e5f6&scope=read+write",
                bodyText(find(messages, "POST http://test.ru/oauth/token HTTP/1.1")));
        assertEquals("PUT /client/30 HTTP/1.1", requestLine(messages.get(messages.size() - 1)));

        for (HttpMessage message : messages) {
            assertFalse(REQUEST_LINE.matcher(new String(message.body(), StandardCharsets.ISO_8859_1)).find());
            String declared = contentLength(message);
            if (declared == null) {
                assertEquals(0, message.body().length);
            } else {
                assertEquals(message.body().length, Integer.parseInt(declared));
            }
        }
    }

    @Test
    void cutsRequestGluedToBodyWithoutSeparator() {
        String inner = "PUT /inner HTTP/1.1\nHost: test.ru\nContent-Length: 3\n\nxyz";
        String outerBody = "hello" + inner;
        String raw = "POST /outer HTTP/1.1\nHost: test.ru\nContent-Length: " + outerBody.length() + "\n\n" + outerBody;

        List<HttpMessage> messages = HttpStreamSplitter.split(raw.getBytes(StandardCharsets.US_ASCII));

        assertEquals(2, messages.size());
        assertEquals("POST /outer HTTP/1.1", requestLine(messages.get(0)));
        assertEquals("hello", bodyText(messages.get(0)));
        assertEquals("5", contentLength(messages.get(0)));
        assertEquals("PUT /inner HTTP/1.1", requestLine(messages.get(1)));
        assertEquals("xyz", bodyText(messages.get(1)));
    }

    @Test
    void keepsMethodMentionInsideBody() {
        String body = "Служебная команда: PUT /client/15\nАрхив: POST /archive/contracts";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String raw = "POST /doc HTTP/1.1\nHost: test.ru\nContent-Length: " + bodyBytes.length + "\n\n";
        byte[] data = concat(raw.getBytes(StandardCharsets.US_ASCII), bodyBytes);

        List<HttpMessage> messages = HttpStreamSplitter.split(data);

        assertEquals(1, messages.size());
        assertEquals(body, bodyText(messages.get(0)));
    }

    @Test
    void readsRequestsSeparatedByCrlf() {
        String raw = """
                GET /a HTTP/1.1\r
                Host: test.ru\r
                \r
                \r
                POST /b HTTP/1.1\r
                Host: test.ru\r
                Content-Length: 4\r
                \r
                ping""";

        List<HttpMessage> messages = HttpStreamSplitter.split(raw.getBytes(StandardCharsets.US_ASCII));

        assertEquals(2, messages.size());
        assertEquals("GET /a HTTP/1.1", requestLine(messages.get(0)));
        assertEquals(0, messages.get(0).body().length);
        assertEquals("POST /b HTTP/1.1", requestLine(messages.get(1)));
        assertEquals("ping", bodyText(messages.get(1)));
    }

    @Test
    void splitsBinaryStreamIntoOriginalPayloads() throws IOException {
        List<String> spec = Files.readAllLines(BINARIES.resolve("expected.tsv"));
        List<HttpMessage> messages = binaryMessages();

        assertEquals(spec.size(), messages.size());
        for (int index = 0; index < spec.size(); index++) {
            String[] columns = spec.get(index).split("\t", 3);
            assertEquals(columns[0], requestLine(messages.get(index)));
            byte[] expected = expectedBody(columns[1], columns[2]);
            assertArrayEquals(expected, messages.get(index).body(), columns[0]);
            assertEquals(String.valueOf(expected.length), contentLength(messages.get(index)));
            assertFalse(ascii(expected).contains("HTTP/1."));
        }
    }

    @Test
    void binaryFixturesKeepRealFormatsAndDecoyText() throws IOException {
        byte[] png = binary("sample.png");
        byte[] jpeg = binary("sample.jpg");
        byte[] pdf = binary("sample.pdf");
        byte[] zip = binary("sample.zip");
        byte[] docx = binary("sample.docx");
        byte[] doc = binary("sample.doc");

        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, copy(png, 4));
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, copy(jpeg, 3));
        assertEquals("GIF89a", ascii(binary("sample.gif"), 0, 6));
        assertEquals("BM", ascii(binary("sample.bmp"), 0, 2));
        assertEquals("%PDF-", ascii(pdf, 0, 5));
        assertArrayEquals(new byte[]{'P', 'K', 3, 4}, copy(zip, 4));
        assertArrayEquals(new byte[]{'P', 'K', 3, 4}, copy(docx, 4));
        assertArrayEquals(new byte[]{0x1F, (byte) 0x8B}, copy(binary("sample.tar.gz"), 2));
        assertArrayEquals(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}, copy(doc, 4));

        assertTrue(ascii(png).contains(CLIENT_MENTION));
        assertTrue(ascii(jpeg).contains(CLIENT_MENTION));
        assertTrue(ascii(pdf).contains("FlateDecode"));
        assertTrue(ascii(pdf).contains(CLIENT_MENTION));
        assertTrue(contains(doc, CLIENT_MENTION.getBytes(StandardCharsets.UTF_16LE)));
        assertArrayEquals(png, zipEntry(docx, "word/media/photo.png"));
        assertTrue(ascii(zipEntry(docx, "word/document.xml")).contains(CLIENT_MENTION));
        assertTrue(ascii(zipEntry(zip, "note.txt")).contains(ARCHIVE_MENTION));
    }

    @Test
    void cutsRequestGluedDirectlyToBinaryBody() throws IOException {
        byte[] stream = Files.readAllBytes(Path.of("data/binary-stream.http"));
        byte[] jpeg = binary("sample.jpg");
        byte[] zip = binary("sample.zip");
        List<HttpMessage> messages = HttpStreamSplitter.split(stream);

        String gluedRequest = "PUT /client/44 HTTP/1.1";
        int zipAtBoundary = indexOf(stream, zip, indexOf(stream, zip) + 1);
        assertEquals(gluedRequest, ascii(stream, zipAtBoundary + zip.length, gluedRequest.length()));

        String scanRequest = "POST http://test.ru/upload/scan.jpg HTTP/1.1";
        int scanHeader = indexOf(stream, scanRequest.getBytes(StandardCharsets.US_ASCII));
        Matcher declared = CONTENT_LENGTH.matcher(ascii(stream, scanHeader, 500));
        assertTrue(declared.find());
        assertTrue(Integer.parseInt(declared.group(1)) > jpeg.length);
        assertEquals(String.valueOf(jpeg.length), contentLength(find(messages, scanRequest)));
    }

    @Test
    void rejectsTruncatedBody() {
        byte[] data = "POST /a HTTP/1.1\nHost: test.ru\nContent-Length: 10\n\nhi".getBytes(StandardCharsets.US_ASCII);

        assertThrows(IllegalArgumentException.class, () -> HttpStreamSplitter.split(data));
    }

    private HttpMessage find(List<HttpMessage> messages, String requestLine) {
        return messages.stream()
                .filter(message -> requestLine(message).equals(requestLine))
                .findFirst()
                .orElseThrow();
    }

    private HttpMessage messageAfter(List<HttpMessage> messages, HttpMessage current) {
        return messages.get(messages.indexOf(current) + 1);
    }

    private String requestLine(HttpMessage message) {
        String headers = new String(message.headers(), StandardCharsets.ISO_8859_1);
        int lineEnd = headers.indexOf('\n');
        String line = lineEnd < 0 ? headers : headers.substring(0, lineEnd);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    private String bodyText(HttpMessage message) {
        return new String(message.body(), StandardCharsets.UTF_8);
    }

    private String contentLength(HttpMessage message) {
        String headers = new String(message.headers(), StandardCharsets.ISO_8859_1);
        Matcher matcher = CONTENT_LENGTH.matcher(headers);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<HttpMessage> binaryMessages() throws IOException {
        return HttpStreamSplitter.split(Files.readAllBytes(Path.of("data/binary-stream.http")));
    }

    private static byte[] binary(String name) throws IOException {
        return Files.readAllBytes(BINARIES.resolve(name));
    }

    private static byte[] expectedBody(String kind, String value) throws IOException {
        if ("file".equals(kind)) {
            return binary(value);
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] copy(byte[] data, int length) {
        return Arrays.copyOf(data, length);
    }

    private static String ascii(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static boolean contains(byte[] data, byte[] needle) {
        return indexOf(data, needle) >= 0;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        return indexOf(data, needle, 0);
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        int last = data.length - needle.length;
        for (int offset = Math.max(from, 0); offset <= last; offset++) {
            if (Arrays.equals(data, offset, offset + needle.length, needle, 0, needle.length)) {
                return offset;
            }
        }
        return -1;
    }

    private static byte[] zipEntry(byte[] archive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new AssertionError("В архиве нет " + name);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

}
