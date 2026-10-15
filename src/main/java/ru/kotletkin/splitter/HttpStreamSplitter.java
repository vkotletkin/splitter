package ru.kotletkin.splitter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Режет поток склеенных HTTP/1.1-запросов.
 * <p>
 * Граница сообщения — пустая строка после заголовков и Content-Length байт тела.
 * Content-Length в этом потоке иногда завышен и захватывает следующий запрос,
 * приклеенный к телу без разделителя ({@code PAYLOAD-4PUT /client/4 HTTP/1.1}).
 * Такой запрос узнаётся не по подстроке метода, а по полной стартовой строке
 * {@code METHOD target HTTP/1.x} и следующему за ней блоку заголовков.
 * Упоминания вроде {@code PUT /client/15} или {@code POST /archive/contracts}
 * отдельными запросами не становятся: у них нет версии HTTP и заголовков.
 */
public final class HttpStreamSplitter {

    private static final Charset HEADER_CHARSET = StandardCharsets.ISO_8859_1;

    private static final String MISSING_HEADER_TERMINATOR = "Не найден конец заголовков";

    private static final byte[][] METHODS = {
            "CONNECT".getBytes(StandardCharsets.US_ASCII),
            "DELETE".getBytes(StandardCharsets.US_ASCII),
            "OPTIONS".getBytes(StandardCharsets.US_ASCII),
            "PATCH".getBytes(StandardCharsets.US_ASCII),
            "TRACE".getBytes(StandardCharsets.US_ASCII),
            "HEAD".getBytes(StandardCharsets.US_ASCII),
            "POST".getBytes(StandardCharsets.US_ASCII),
            "PUT".getBytes(StandardCharsets.US_ASCII),
            "GET".getBytes(StandardCharsets.US_ASCII)
    };

    private static final Pattern REQUEST_LINE = Pattern.compile(
            "^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT) \\S+ HTTP/1\\.[01]\\r?$");

    private static final Pattern HEADER_LINE = Pattern.compile(
            "^[!#$%&'*+.^_`|~0-9A-Za-z-]+:[ \\t]?.*$");

    private static final Pattern CONTENT_LENGTH = Pattern.compile(
            "(?im)^Content-Length:[ \\t]*(\\d+)[ \\t]*\\r?$");

    private HttpStreamSplitter() {
    }

    public static List<HttpMessage> split(byte[] data) {
        Objects.requireNonNull(data, "data");
        List<HttpMessage> messages = new ArrayList<>();
        int position = 0;

        while (position < data.length) {
            position = skipLineBreaks(data, position);
            if (position >= data.length) {
                break;
            }
            position = readMessage(data, position, messages);
        }

        return List.copyOf(messages);
    }

    private static int readMessage(byte[] data, int position, List<HttpMessage> messages) {
        HeaderBlock headers = parseHeaders(data, position);
        int contentLength = extractContentLength(decode(data, headers.start(), headers.end()));
        long declaredBodyEnd = (long) headers.bodyStart() + contentLength;
        if (declaredBodyEnd > data.length) {
            throw new IllegalArgumentException("Тело выходит за границы файла");
        }

        int bodyEnd = (int) declaredBodyEnd;
        int embedded = findEmbeddedRequest(data, headers.bodyStart(), bodyEnd);
        int messageEnd = embedded >= 0 ? embedded : bodyEnd;
        byte[] body = Arrays.copyOfRange(data, headers.bodyStart(), messageEnd);
        byte[] rawHeaders = Arrays.copyOfRange(data, headers.start(), headers.end());
        messages.add(new HttpMessage(alignContentLength(rawHeaders, body.length), body));
        return messageEnd;
    }

    private static HeaderBlock parseHeaders(byte[] data, int position) {
        int cursor = position;
        int headersEnd = position;
        boolean requestLine = true;

        while (cursor < data.length) {
            Line line = requireLine(data, cursor);
            String text = decode(data, cursor, line.terminatorStart());
            if (isHeaderTerminator(requestLine, text)) {
                return new HeaderBlock(position, headersEnd, line.next());
            }
            rejectInvalidLine(text, requestLine, position, cursor);
            requestLine = false;
            headersEnd = line.terminatorStart();
            cursor = line.next();
        }

        throw headerTerminatorNotFound();
    }

    private static boolean isHeaderTerminator(boolean requestLine, String text) {
        return !requestLine && text.isEmpty();
    }

    private static void rejectInvalidLine(String text, boolean requestLine, int position, int cursor) {
        if (requestLine && !isRequestLine(text)) {
            throw new IllegalArgumentException("Ожидался HTTP-запрос на позиции " + position);
        }
        if (!requestLine && !isHeaderLine(text)) {
            throw new IllegalArgumentException("Некорректный заголовок на позиции " + cursor);
        }
    }

    /**
     * Ищет самый ранний настоящий запрос внутри объявленного тела.
     * Сканируются все смещения, а не начала строк: следующий запрос может быть
     * приклеен к последнему байту тела, например так: '}PUT /client/12 HTTP/1.1'.
     */
    private static int findEmbeddedRequest(byte[] data, int from, int to) {
        for (int offset = from; offset < to; offset++) {
            if (startsWithMethod(data, offset, to) && isRequestStart(data, offset, to)) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean isRequestStart(byte[] data, int offset, int limit) {
        Line requestLine = readLine(data, offset, limit);
        return requestLine != null
                && isRequestLine(decode(data, offset, requestLine.terminatorStart()))
                && hasHeaderBlock(data, requestLine.next(), limit);
    }

    private static boolean hasHeaderBlock(byte[] data, int cursor, int limit) {
        Line header = readLine(data, cursor, limit);
        while (header != null && header.terminatorStart() != cursor) {
            if (!isHeaderLine(decode(data, cursor, header.terminatorStart()))) {
                return false;
            }
            cursor = header.next();
            header = cursor < limit ? readLine(data, cursor, limit) : null;
        }
        return header != null;
    }

    private static boolean startsWithMethod(byte[] data, int offset, int limit) {
        for (byte[] method : METHODS) {
            int end = offset + method.length;
            if (end < limit && data[end] == ' ' && Arrays.equals(data, offset, end, method, 0, method.length)) {
                return true;
            }
        }
        return false;
    }

    private static int extractContentLength(String headers) {
        Matcher matcher = CONTENT_LENGTH.matcher(headers);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Некорректный Content-Length", exception);
        }
    }

    /**
     * После отрезания вклеенного запроса объявленная длина больше фактического тела.
     * Цифры Content-Length заменяются, имя и остальной текст заголовка сохраняются.
     */
    private static byte[] alignContentLength(byte[] headers, int bodyLength) {
        String text = decode(headers, 0, headers.length);
        String aligned = CONTENT_LENGTH.matcher(text).replaceAll(match -> withBodyLength(match, bodyLength));
        if (aligned.equals(text)) {
            return headers;
        }
        return aligned.getBytes(HEADER_CHARSET);
    }

    private static String withBodyLength(MatchResult match, int bodyLength) {
        String line = match.group();
        int digitsStart = match.start(1) - match.start();
        int digitsEnd = match.end(1) - match.start();
        return line.substring(0, digitsStart) + bodyLength + line.substring(digitsEnd);
    }

    private static int skipLineBreaks(byte[] data, int position) {
        while (position < data.length && isLineBreak(data[position])) {
            position++;
        }
        return position;
    }

    private static boolean isLineBreak(byte value) {
        return value == '\n' || value == '\r';
    }

    private static Line requireLine(byte[] data, int from) {
        Line line = readLine(data, from, data.length);
        if (line == null) {
            throw headerTerminatorNotFound();
        }
        return line;
    }

    private static Line readLine(byte[] data, int from, int limit) {
        for (int index = from; index < limit; index++) {
            if (data[index] == '\n') {
                int terminator = (index > from && data[index - 1] == '\r') ? index - 1 : index;
                return new Line(terminator, index + 1);
            }
        }
        return null;
    }

    private static boolean isRequestLine(String text) {
        return REQUEST_LINE.matcher(text).matches();
    }

    private static boolean isHeaderLine(String text) {
        return HEADER_LINE.matcher(text).matches();
    }

    private static IllegalArgumentException headerTerminatorNotFound() {
        return new IllegalArgumentException(MISSING_HEADER_TERMINATOR);
    }

    private static String decode(byte[] data, int from, int to) {
        return new String(data, from, to - from, HEADER_CHARSET);
    }

    private record Line(int terminatorStart, int next) {
    }

    private record HeaderBlock(int start, int end, int bodyStart) {
    }

}
